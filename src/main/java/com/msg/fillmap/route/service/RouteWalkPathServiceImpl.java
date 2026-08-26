package com.msg.fillmap.route.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.route.dto.RouteWalkPathRequestDto;
import com.msg.fillmap.route.dto.RouteWalkPathRequestDto.SegmentDto;
import com.msg.fillmap.route.dto.RouteWalkPathResponseDto;
import com.msg.fillmap.route.dto.RouteWalkPathResponseDto.PathPointDto;
import com.msg.fillmap.route.dto.RouteWalkPathResponseDto.WalkSegmentDto;
import com.msg.fillmap.route.exception.RouteErrorCode;
import com.msg.fillmap.route.service.RouteWalkDailyLimiter.Acquisition;
import com.msg.fillmap.route.service.TmapWalkClient.Coordinate;
import com.msg.fillmap.route.service.TmapWalkClient.TmapUnreachableException;
import com.msg.fillmap.route.service.TmapWalkClient.WalkPath;

/**
 * 보행 경로 조회 구현 (MSG-483 §도메인 로직). 처리 순서는 세그먼트 검증(14402) → 플래그 게이트(14504) →
 * 세그먼트를 순서대로 하나씩, 캐시 조회 → 미스면 일 한도 선점 → TMap 호출 → 캐시 저장이다. 전 구간
 * 읽기 전용이고 저장 의존이 없다 (FR-ROUTE-09 와 같은 구조).
 *
 * 상시 빈이다 — TmapWalkClient 만 플래그 조건부라 ObjectProvider 로 받고 부재 시 14504 를 던진다
 * (RouteRecommendServiceImpl 선례). 세그먼트 실패는 전부 200 안의 {@code resolved: false} 로 수렴한다 —
 * TmapUnreachableException 도 여기서 삼킨다(전파되면 핸들러 폴백 500). 사용자별 요청 제한은 두지
 * 않는다 — 추천 1회당 1회로 자연 결합되고, 반복은 캐시가 흡수하며, 전역 일 한도가 최종 방어선이다
 * (§도메인 로직 4).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouteWalkPathServiceImpl implements RouteWalkPathService {

	/** 지점 상한 8 — 세그먼트 최대 7개에 출발지 구간 1개 (FR-ROUTE-13 파생). */
	private static final int MAX_SEGMENTS = 8;

	// 한국 서비스 범위 (SRS 2.4) — 뷰포트 14400 과 같은 방식의 시행. WGS84 정의역만 보면 해외 좌표로 일
	// 한도를 소진시키는 경로가 열린다. 범위 비교가 NaN(어떤 비교도 false)·±무한대까지 함께 걸러낸다.
	private static final double MIN_LATITUDE_DEG = 33.0;
	private static final double MAX_LATITUDE_DEG = 39.0;
	private static final double MIN_LONGITUDE_DEG = 124.0;
	private static final double MAX_LONGITUDE_DEG = 132.0;

	private static final WalkSegmentDto FAILED_SEGMENT = new WalkSegmentDto(false, null, null);

	private final ObjectProvider<TmapWalkClient> walkClientProvider;
	private final RouteWalkSegmentCache segmentCache;
	private final RouteWalkDailyLimiter dailyLimiter;

	@Override
	public RouteWalkPathResponseDto walkPaths(RouteWalkPathRequestDto request) {
		validateSegments(request.segments());
		TmapWalkClient walkClient = walkClientProvider.getIfAvailable();
		if (walkClient == null) {
			// 플래그 꺼짐(기본)의 명시적 비활성 응답 — 404 를 주면 FE 가 기능 없음과 기능 꺼짐을 구분하지 못한다.
			throw new ApiException(RouteErrorCode.ROUTE_WALK_DISABLED);
		}
		int hits = 0;
		int calls = 0;
		int failed = 0;
		long dailyUsed = -1;	// 이번 요청에서 선점이 없었거나(전 세그먼트 캐시 히트) Redis 오류면 -1 로 남는다
		boolean limitDenied = false;	// 한도 도달 후 남은 미스는 호출도 카운터 증가도 없이 실패 (§도메인 로직 2)
		boolean unreachable = false;	// 응답 자체가 없는 실패 후 남은 미스는 호출 없이 실패 — 지연 상한 (§도메인 로직 3)
		List<WalkSegmentDto> results = new ArrayList<>(request.segments().size());
		for (SegmentDto segment : request.segments()) {
			// 캐시 조회는 단락·한도 소진 중에도 한다 — 캐시 히트는 외부 호출이 아니라 계속 성공으로 준다.
			WalkPath cached = segmentCache.get(
				segment.startLat(), segment.startLng(), segment.endLat(), segment.endLng());
			if (cached != null) {
				hits++;
				results.add(resolved(cached));
				continue;
			}
			if (limitDenied || unreachable) {
				failed++;
				results.add(FAILED_SEGMENT);
				continue;
			}
			Acquisition acquisition = dailyLimiter.tryAcquire();
			if (acquisition.dailyUsed() >= 0) {
				dailyUsed = acquisition.dailyUsed();
			}
			if (!acquisition.allowed()) {
				// 두 번째 요청 내 단락 — 리미터는 INCR 먼저라 거부된 선점도 카운터를 올린다. 한도성 거부
				// (dailyUsed >= 0) 후 재호출하면 카운터가 부풀어 스펙 196행("카운터 증가도 없이")과 daily_used
				// 지표가 무의미해진다. Redis 오류 거부(-1)는 카운터 무영향이라 단락이 필수는 아니지만, 같은
				// 플래그로 접어 죽은 Redis 의 연결 타임아웃이 세그먼트 수 배로 쌓이는 것도 함께 막는다.
				limitDenied = true;
				failed++;
				results.add(FAILED_SEGMENT);
				continue;
			}
			calls++;
			WalkPath walkPath;
			try {
				walkPath = walkClient.fetch(
					segment.startLat(), segment.startLng(), segment.endLat(), segment.endLng());
			} catch (TmapUnreachableException e) {
				// 여기서 삼켜야 200 + resolved:false 로 수렴한다 — 핸들러까지 가면 500 이다 (FR-ROUTE-17).
				unreachable = true;
				failed++;
				results.add(FAILED_SEGMENT);
				continue;
			}
			if (walkPath == null) {
				// 상태 코드가 있는 실패·형태 위반 — 그 세그먼트만 실패하고 다음을 계속 진행한다.
				failed++;
				results.add(FAILED_SEGMENT);
				continue;
			}
			segmentCache.put(segment.startLat(), segment.startLng(), segment.endLat(), segment.endLng(), walkPath);
			results.add(resolved(walkPath));
		}
		// 지표 로그 (NFR-OPS-09 운영 감시) — 좌표와 TMap 응답 원문은 남기지 않는다. daily_used 가 한도 소진
		// 시점의 실측 재료다.
		log.info("[route-walk] segments={} hits={} calls={} failed={} daily_used={}",
			request.segments().size(), hits, calls, failed, dailyUsed);
		return new RouteWalkPathResponseDto(results);
	}

	private static WalkSegmentDto resolved(WalkPath walkPath) {
		List<PathPointDto> path = new ArrayList<>(walkPath.path().size());
		for (Coordinate coordinate : walkPath.path()) {
			path.add(new PathPointDto(coordinate.lat(), coordinate.lng()));
		}
		return new WalkSegmentDto(true, path, walkPath.distanceMeters());
	}

	/**
	 * 세그먼트 검증 — 목록 형식 위반 전부가 14402 한 코드로 수렴한다 (§API, FE 분기 단일화). 원소 null 은
	 * JSON [null] 이 크기 검증만 통과해 null 역참조 500 으로 새는 것을 막고(Codex 4라운드 적발), 좌표
	 * null(필드 누락)도 같은 코드로 거른다 — 이 검증 통과 후에만 좌표 언박싱이 안전하다.
	 * 출발과 도착이 같은 좌표는 거부하지 않고 TMap 결과에 맡긴다 (실패하면 실패 표시).
	 */
	private void validateSegments(List<SegmentDto> segments) {
		if (segments == null || segments.isEmpty() || segments.size() > MAX_SEGMENTS) {
			throw new ApiException(RouteErrorCode.INVALID_WALK_SEGMENTS);
		}
		for (SegmentDto segment : segments) {
			if (segment == null
				|| !isValidLat(segment.startLat()) || !isValidLng(segment.startLng())
				|| !isValidLat(segment.endLat()) || !isValidLng(segment.endLng())) {
				throw new ApiException(RouteErrorCode.INVALID_WALK_SEGMENTS);
			}
		}
	}

	private static boolean isValidLat(Double lat) {
		return lat != null && lat >= MIN_LATITUDE_DEG && lat <= MAX_LATITUDE_DEG;
	}

	private static boolean isValidLng(Double lng) {
		return lng != null && lng >= MIN_LONGITUDE_DEG && lng <= MAX_LONGITUDE_DEG;
	}
}
