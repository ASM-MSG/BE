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

	public HotZoneProperties {
		// topK <= 0 이면 ZREVRANGE end 인덱스(topK-1)가 음수가 돼 "캡 없음 — 전체 조회"로 뒤집힌다.
		// 설정 오류는 런타임이 아니라 기동 시점에 잡는다 (ProdRequiredEnvValidator fail-fast 관행).
		if (topK <= 0) {
			throw new IllegalArgumentException("fillmap.hotzone.top-k 는 1 이상이어야 합니다: " + topK);
		}
	}
}
