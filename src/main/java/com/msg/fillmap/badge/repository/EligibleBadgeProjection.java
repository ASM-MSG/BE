package com.msg.fillmap.badge.repository;

/**
 * findEligible 네이티브 조회 결과 프로젝션 (MSG-239 §D5). 응답 동봉(FR-9)에 필요한 컬럼만 노출한다 —
 * conditionType·condition_value 는 select 하지 않는다(위키 방침·FE 불요, WHERE 절에서만 사용).
 */
public interface EligibleBadgeProjection {

	Long getBadgeId();

	String getCode();

	String getName();

	String getDescription();

	String getIconUrl();
}
