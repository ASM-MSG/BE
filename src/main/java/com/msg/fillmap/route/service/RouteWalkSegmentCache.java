package com.msg.fillmap.route.service;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.route.service.TmapWalkClient.WalkPath;

/**
 * 좌표쌍 보행 경로 캐시 (MSG-483 §도메인 로직 1, FR-8·NFR-OPS-09). Redis 키
 * {@code route:walk:seg:{startLat}:{startLng}:{endLat}:{endLng}}(Double.toString 정규형 — Java 와 JSON 의
 * double 왕복이 값을 보존해 같은 세그먼트는 같은 키다), 값은 좌표열과 거리의 JSON 직렬화다. 저장은
 * SET ... EX 86400 단일 명령이라 값과 TTL 이 원자로 함께 걸리고, TTL 만료는 Redis 의 능동 삭제라 TMap
 * 약관의 24시간 보관 상한을 저장소가 스스로 지킨다. 성공 응답만 캐시한다 — 실패를 캐시하면 일시 장애의
 * 직선 폴백이 24시간 고착된다.
 *
 * Redis 오류는 조회 미스 취급·저장 무시다 — 캐시는 없어도 안전한 절감 장치라, 닫으면 잃는 것(멀쩡한
 * 세그먼트의 실패)이 열어서 생기는 비용(계수되는 호출 1건)보다 크다. 한도 카운터(RouteWalkDailyLimiter)의
 * 차단 폴백과 방향이 반대인 근거다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteWalkSegmentCache {

	private static final String KEY_PREFIX = "route:walk:seg:";
	private static final Duration TTL = Duration.ofHours(24);

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;

	/** 조회 — 미스, Redis 오류, 역직렬화 실패 전부 null(미스 취급)이다. */
	public WalkPath get(double startLat, double startLng, double endLat, double endLng) {
		try {
			String json = redisTemplate.opsForValue().get(key(startLat, startLng, endLat, endLng));
			return json == null ? null : objectMapper.readValue(json, WalkPath.class);
		} catch (Exception e) {
			// 좌표 값은 로그에 남기지 않는다 — 클래스명만 (지표 로그 규칙).
			log.warn("[route-walk] 캐시 조회 실패 — 미스 취급, TMap 호출 진행: cause={}", e.getClass().getSimpleName());
			return null;
		}
	}

	/** 저장 — 실패는 무시하고 응답은 정상 진행한다 (그 호출 1건은 여전히 한도 카운터가 지킨다). */
	public void put(double startLat, double startLng, double endLat, double endLng, WalkPath walkPath) {
		try {
			redisTemplate.opsForValue()
				.set(key(startLat, startLng, endLat, endLng), objectMapper.writeValueAsString(walkPath), TTL);
		} catch (Exception e) {
			log.warn("[route-walk] 캐시 저장 실패 — 무시: cause={}", e.getClass().getSimpleName());
		}
	}

	private static String key(double startLat, double startLng, double endLat, double endLng) {
		return KEY_PREFIX + startLat + ":" + startLng + ":" + endLat + ":" + endLng;
	}
}
