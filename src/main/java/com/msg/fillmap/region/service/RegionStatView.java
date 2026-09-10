package com.msg.fillmap.region.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 행정동별 수집률 내부 뷰 (MSG-156). RegionView(MSG-93) 선례대로 리포지토리 프로젝션을 컨트롤러/DTO 로 그대로
 * 흘리지 않고 서비스가 이 뷰로 감싼다 — HTTP 응답 DTO(RegionStatResponseDto) 변환은 컨트롤러 책임.
 * progressRate 는 표시 100 clamp 값(§D4), updatedAt 은 region_stats 캐시 기준 시각.
 */
public record RegionStatView(
	String regionCode,
	String regionName,
	String parentCode,
	Integer collectedCount,
	Integer totalCount,
	BigDecimal progressRate,
	LocalDateTime updatedAt
) {
}
