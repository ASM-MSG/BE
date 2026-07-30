package com.msg.fillmap.badge.entity;

/**
 * 뱃지 판정 축 (badges.condition_type — V9 CHECK 와 동기, MSG-239 §D5).
 * MVP 활성 축은 TOTAL_GRIDS·UPLOAD_COUNT·REGION_PERCENT 3종이고, STREAK_DAYS(MSG-200)·
 * MISSION_COUNT(미션 판정 엔진 티켓)·SPECIAL(오픈 준비 티켓)은 훅·시딩과 함께 활성화된다(§D2).
 */
public enum BadgeConditionType {
	REGION_PERCENT,
	TOTAL_GRIDS,
	STREAK_DAYS,
	UPLOAD_COUNT,
	MISSION_COUNT,
	SPECIAL
}
