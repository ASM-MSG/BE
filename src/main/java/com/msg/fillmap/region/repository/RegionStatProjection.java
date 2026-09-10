package com.msg.fillmap.region.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 행정동별 수집률 네이티브 조회 결과 프로젝션 (MSG-156). region_stats 와 regions 조인 결과의 컬럼만 노출한다 —
 * RegionProjection(MSG-93) 선례대로 인터페이스 프로젝션이라 RegionStats 엔티티를 만들지 않는다(§데이터 모델).
 * 네이티브 쿼리는 별칭을 각 getter 프로퍼티명으로 맞춘다. progressRate 는 표시단 100 clamp 값(§D4).
 */
public interface RegionStatProjection {

	String getRegionCode();

	String getRegionName();

	String getParentCode();

	Integer getCollectedCount();

	Integer getTotalCount();

	BigDecimal getProgressRate();

	LocalDateTime getUpdatedAt();
}
