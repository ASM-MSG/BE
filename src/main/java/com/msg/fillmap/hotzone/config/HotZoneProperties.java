package com.msg.fillmap.hotzone.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 핫구역 판정 값 (MSG-233 D3) — 초기 데이터를 보고 튜닝할 것이 명백해 프로퍼티로 뺀다.
 * KakaoLocalProperties·AiProperties 관례의 record 바인딩 — @ConfigurationPropertiesScan 이 자동 등록한다.
 *
 * @param topK     전국 상위 핫구역 개수 (합산 ZSET 상위 K)
 * @param minScore 48h 합산 핫스코어 최소 임계 — 미만은 핫구역이 아니다 (FR-10)
 */
@ConfigurationProperties(prefix = "fillmap.hotzone")
public record HotZoneProperties(
	@DefaultValue("50") int topK,
	@DefaultValue("3") int minScore
) {
}
