package com.msg.fillmap.zone.seed;

/**
 * zones.json 한 항목의 시딩 입력 (MSG-234 §D7). zoneKey 가 자연키(멱등 UPSERT), name 은 비유일 표시명.
 * JSON 키는 이 컴포넌트 이름과 그대로 일치한다(엔티티/API 와 통일 — 매핑 누락 방지).
 */
public record ZoneSeed(
	String zoneKey,
	String name,
	String regionCode,
	Integer minGridY,
	Integer maxGridY,
	Integer minGridX,
	Integer maxGridX,
	Integer priority
) {
}
