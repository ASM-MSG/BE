package com.msg.fillmap.streak.repository;

/**
 * 리마인드 대상 프로젝션 (findRemindTargets 네이티브 조회 결과, MSG-181 D8). currentCount 는
 * 리마인드 문구 재료("{n}일 연속 기록 중") — row 가 있으면 current_count ≥ 1 이 불변식이다.
 */
public interface StreakRemindProjection {

	Long getUserId();

	Integer getCurrentCount();
}
