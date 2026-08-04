package com.msg.fillmap.notification.entity;

/**
 * 알림 상태 머신 (MSG-179 D4) — V20 notifications 의 CHECK 와 일치.
 * PENDING → 릴레이 발행 → PUBLISHED → 컨슈머 → SENT | SKIPPED | DEAD.
 */
public enum NotificationStatus {
	PENDING,
	PUBLISHED,
	SENT,
	SKIPPED,
	DEAD
}
