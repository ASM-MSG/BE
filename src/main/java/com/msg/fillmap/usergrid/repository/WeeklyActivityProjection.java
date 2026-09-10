package com.msg.fillmap.usergrid.repository;

/**
 * 주간 활동 집계 프로젝션 (findWeeklyActivity 네이티브 조회 결과, MSG-315 D3). row 가 있으면
 * 이번 주 활동이 있는 사용자라는 뜻이라 대상 판정이 곧 이 조회다(FR-6). videoCount ≥ gridCount ≥ 0 이
 * 불변식 — 이번 주 새 도감 행은 그 격자의 첫 영상이 이번 주 미삭제 업로드라는 뜻이기 때문이다(D5).
 */
public interface WeeklyActivityProjection {

	Long getUserId();

	Integer getGridCount();

	Integer getVideoCount();
}
