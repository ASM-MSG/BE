package com.msg.fillmap.grid.cache;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 뷰포트 점령 격자 조회 캐시 (부하 실험용 스위치). MSG-134 는 "Redis 캐시(MSG-89)는 with/without 비교로
 * 수치화한다"고 남겼고, 이 프로퍼티가 그 비교의 변수다. 기본은 {@code NONE} — 실험 밖에서는 아무것도 바꾸지 않는다.
 *
 * @param mode      NONE · LOCAL(인프로세스 Caffeine) · REDIS(공유) · TWO_LEVEL(Caffeine → Redis)
 * @param ttl       캐시 수명. MSG-134 D3 의 freshness 허용치(stale ≤ 30s)가 상한이다
 * @param snapCells 캐시 단위. 뷰포트 정수 범위를 이 배수 블록으로 바깥쪽 반올림해 키를 만든다 — 이웃한 팬이
 *                  같은 항목을 맞히게 한다. 0 이면 요청 범위 그대로(사실상 재요청만 맞는다)
 */
@ConfigurationProperties(prefix = "fillmap.grid.viewport-cache")
public record ViewportCacheProperties(
	@DefaultValue("NONE") Mode mode,
	@DefaultValue("PT30S") Duration ttl,
	@DefaultValue("64") int snapCells
) {

	public enum Mode { NONE, LOCAL, REDIS, TWO_LEVEL }
}
