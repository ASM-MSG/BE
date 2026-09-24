package com.msg.fillmap.notification.entity;

/**
 * 알림 딥링크 이동 대상의 종류 (MSG-432 FR-4). 화면 매핑은 앱이 이 값으로 정한다 — 서버는 화면 경로를
 * 모른다. 새 종류는 SRS 개정과 V56 CHECK(chk_notifications_target_type) 확장 마이그레이션이 같이 간다.
 */
public enum NotificationTargetType {
	VIDEO,
	GRID,
	BADGE,
	EVENT_OCCURRENCE,
	USER
}
