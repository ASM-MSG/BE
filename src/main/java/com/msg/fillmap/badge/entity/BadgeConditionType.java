package com.msg.fillmap.badge.entity;

/**
 * 뱃지 판정 축 (badges.condition_type — V29 CHECK 와 동기, MSG-239 §D5 + MSG-363 §D1).
 * 활성 축은 TOTAL_GRIDS·UPLOAD_COUNT·REGION_PERCENT·STREAK_DAYS(MSG-200)와 미션 종류별 3축
 * EVENT_COUNT·COURSE_COUNT·POPUP_COUNT(MSG-363)이고, SPECIAL(오픈 준비 티켓)은 훅·시딩과 함께
 * 활성화된다(§D2).
 */
public enum BadgeConditionType {
	REGION_PERCENT,
	TOTAL_GRIDS,
	STREAK_DAYS,
	UPLOAD_COUNT,
	/**
	 * 은퇴 축 (MSG-363 §D1) — 미션 종류를 합산하던 옛 축이다. V29 가 이 축의 뱃지 3종에
	 * retired_at 을 채워 신규 지급을 끊었고 새 지급 경로가 없다. 상수를 지우지 않는 이유는
	 * 은퇴한 badges 행이 이 문자열을 계속 들고 있어서다 — Badge.conditionType 이
	 * {@code @Enumerated(STRING)} 이라 상수가 없으면 그 행을 읽는 순간 매핑이 깨진다.
	 */
	MISSION_COUNT,
	EVENT_COUNT,
	COURSE_COUNT,
	POPUP_COUNT,
	SPECIAL
}
