package com.msg.fillmap.mission.repository;

/**
 * findCompleted 네이티브 조회 결과 프로젝션 (MSG-223 §D2 2단계). 응답 동봉(FR-19)에 필요한 컬럼만
 * 노출한다 — 내부 판정값(target_count 등)은 select 하지 않는다(EarnedBadgeResponseDto 선례).
 */
public interface CompletedMissionProjection {

	Long getMissionId();

	String getTitle();

	String getType();
}
