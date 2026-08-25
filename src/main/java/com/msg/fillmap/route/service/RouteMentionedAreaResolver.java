package com.msg.fillmap.route.service;

import java.util.List;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.region.service.RegionQueryService;
import com.msg.fillmap.region.service.RegionQueryService.MentionedRegionMatch;
import com.msg.fillmap.route.dto.RouteRecommendResponseDto.MentionedAreaDto;
import com.msg.fillmap.zone.dto.ZoneResponseDto;
import com.msg.fillmap.zone.service.ZoneQueryService;

/**
 * 언급 지역 신호 판정 (MSG-468 §도메인 로직 1~4). 해석이 돌려준 지역 이름을 서버 보유 데이터(zones·regions)에
 * 대조해 뷰포트와의 관계(MOVE·ZOOM_OUT·무신호)를 정한다. 통칭(zones.name) 유일 일치가 행정구역 동명을
 * 이기는 2단 판정이고(PRD FR-1 확정 — 읍·면 동명 때문에 평면 유일성이면 "서면"이 구조적으로 죽는다),
 * 신호의 이름·좌표는 전부 서버 데이터에서만 나온다(데이터 정합). 조회는 zones 1 + regions 최대 1 — 두 문장
 * 이내다(비기능 성능). 판정 실패는 예외까지 무신호로 삼킨다 — 신호는 부가 정보라 추천을 실패로 바꾸지
 * 않는다(FR-3, §실패 비전파).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteMentionedAreaResolver {

	/** 축소 제안 임계 (§도메인 로직 3) — 가로세로 모두 절반을 못 담으면 0.5² = 0.25 아래로 떨어진다. */
	static final double ZOOM_OUT_COVERAGE_RATIO = 0.25;

	private static final String KIND_MOVE = "MOVE";
	private static final String KIND_ZOOM_OUT = "ZOOM_OUT";

	private final ZoneQueryService zoneQueryService;
	private final RegionQueryService regionQueryService;

	/** 판정 진입점 — 무신호는 null. 도중 예외는 warn 만 남기고 무신호로 삼킨다 (§실패 비전파). */
	public MentionedAreaDto resolve(String region, ViewportBounds viewport) {
		try {
			return doResolve(region, viewport);
		} catch (RuntimeException e) {
			// 예외 메시지에 대조 입력(지역 이름)이 실릴 수 있어 클래스명만 남긴다 — 지역 이름 비기록 규칙.
			log.warn("[route] 언급 지역 판정 실패 — 무신호로 삼킨다: cause={}", e.getClass().getSimpleName());
			return null;
		}
	}

	/** 2단 판정 (§도메인 로직 1) — 통칭 유일 일치 우선, zone 일치가 없을 때만 행정구역 그룹 유일성을 본다. */
	private MentionedAreaDto doResolve(String region, ViewportBounds viewport) {
		if (region == null || region.isBlank()) {
			return null;	// 지역 무언급 (FR-2)
		}
		String name = region.trim();
		List<ZoneResponseDto> zones = zoneQueryService.getZones().stream()
			.filter(zone -> name.equals(zone.name()))
			.toList();
		if (zones.size() > 1) {
			return null;	// 동명 통칭 (FR-2) — 행정구역 대조로 넘어가지 않는다
		}
		if (zones.size() == 1) {
			// 통칭 채택 — 행정구역 동명 그룹은 세지 않는다 (PRD FR-1, regions 조회도 하지 않는다).
			return zoneSignal(zones.getFirst(), viewport);
		}
		List<MentionedRegionMatch> matches = regionQueryService.matchMentionedRegions(name, viewport);
		if (matches.size() != 1) {
			return null;	// 0 = 대조 실패 (FR-3), 2 이상 = 동명 다수 (FR-2)
		}
		MentionedRegionMatch match = matches.getFirst();
		Bbox bbox = new Bbox(match.minLat(), match.minLng(), match.maxLat(), match.maxLng());
		// 행정구역 겹침은 계약의 overlapsViewport(실제 경계, ST_Intersects)다 — 외접 사각형이면 김해 뷰포트가
		// 부산 사각형 안에 들어 틀린 종류(ZOOM_OUT)가 나간다 (§도메인 로직 2).
		return signal(match.name(), match.centerLat(), match.centerLng(), bbox, match.overlapsViewport(), viewport);
	}

	/** 구역은 격자 사각형이 실체 — WGS84 외접 사각형으로 환산하고 겹침도 사각형 교차로 판정한다(판정 목적에 충분). */
	private static MentionedAreaDto zoneSignal(ZoneResponseDto zone, ViewportBounds viewport) {
		Bbox bbox = zoneBbox(zone);
		return signal(zone.name(), (bbox.minLat() + bbox.maxLat()) / 2, (bbox.minLng() + bbox.maxLng()) / 2,
			bbox, overlaps(bbox, viewport), viewport);
	}

	/**
	 * 구역 격자 사각형의 WGS84 외접 사각형 — 네 모서리 셀의 {@link GridEncoder#bbox} 점을 모두 모아 min/max 로
	 * 잡는다. 5179 셀은 위경도 평면에서 기울어져 남서·북동 두 점으로 복원할 수 없다 (격자 계산 규칙, MSG-347).
	 */
	private static Bbox zoneBbox(ZoneResponseDto zone) {
		double minLat = Double.POSITIVE_INFINITY;
		double minLng = Double.POSITIVE_INFINITY;
		double maxLat = Double.NEGATIVE_INFINITY;
		double maxLng = Double.NEGATIVE_INFINITY;
		for (int gridY : new int[] {zone.minGridY(), zone.maxGridY()}) {
			for (int gridX : new int[] {zone.minGridX(), zone.maxGridX()}) {
				for (GridPoint point : GridEncoder.bbox(gridY + "_" + gridX)) {
					minLat = Math.min(minLat, point.lat());
					minLng = Math.min(minLng, point.lon());
					maxLat = Math.max(maxLat, point.lat());
					maxLng = Math.max(maxLng, point.lon());
				}
			}
		}
		return new Bbox(minLat, minLng, maxLat, maxLng);
	}

	/** 종류 판정 (§도메인 로직 2) — 안 겹치면 MOVE, 겹치면 담김 비율 25% 미만에서 ZOOM_OUT, 이상이면 무신호. */
	private static MentionedAreaDto signal(String name, double centerLat, double centerLng, Bbox bbox,
		boolean overlaps, ViewportBounds viewport) {
		if (!overlaps) {
			return area(name, centerLat, centerLng, bbox, KIND_MOVE);
		}
		if (coverageRatio(bbox, viewport) < ZOOM_OUT_COVERAGE_RATIO) {
			return area(name, centerLat, centerLng, bbox, KIND_ZOOM_OUT);
		}
		return null;	// 화면이 지역을 충분히 담고 있다 (FR-6)
	}

	/** 판정에 쓴 외접 사각형 그대로를 응답에 싣는다 — kind 무관 상시 동봉, FE 축척 재료 (FR-4). */
	private static MentionedAreaDto area(String name, double centerLat, double centerLng, Bbox bbox, String kind) {
		return new MentionedAreaDto(name, centerLat, centerLng,
			bbox.minLat(), bbox.minLng(), bbox.maxLat(), bbox.maxLng(), kind);
	}

	/**
	 * 담김 비율 = (뷰포트 ∩ 지역 외접 사각형) 넓이 / 지역 외접 사각형 넓이 — 도 단위 평면 산술.
	 * 두 사각형이 같은 위도대라 경도 축척이 분자·분모에서 상쇄되므로 비율 판정에는 충분하다 (§도메인 로직 2).
	 */
	private static double coverageRatio(Bbox bbox, ViewportBounds viewport) {
		double overlapLat = Math.min(viewport.neLat(), bbox.maxLat()) - Math.max(viewport.swLat(), bbox.minLat());
		double overlapLng = Math.min(viewport.neLng(), bbox.maxLng()) - Math.max(viewport.swLng(), bbox.minLng());
		if (overlapLat <= 0 || overlapLng <= 0) {
			return 0;
		}
		return (overlapLat * overlapLng) / ((bbox.maxLat() - bbox.minLat()) * (bbox.maxLng() - bbox.minLng()));
	}

	/** 넓이 있는 사각형 교차만 겹침으로 친다 — 변끼리 닿기만 한 배치는 MOVE 쪽이 맞다. */
	private static boolean overlaps(Bbox bbox, ViewportBounds viewport) {
		return Math.min(viewport.neLat(), bbox.maxLat()) > Math.max(viewport.swLat(), bbox.minLat())
			&& Math.min(viewport.neLng(), bbox.maxLng()) > Math.max(viewport.swLng(), bbox.minLng());
	}

	/** WGS84 외접 사각형 값객체 — 행정구역(ST_Extent)과 구역(격자 환산) 두 출처를 같은 산술로 다루기 위한 것. */
	private record Bbox(double minLat, double minLng, double maxLat, double maxLng) {
	}
}
