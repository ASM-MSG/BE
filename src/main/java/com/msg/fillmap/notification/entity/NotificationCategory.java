package com.msg.fillmap.notification.entity;

/**
 * 알림 카테고리 (MSG-179) — V25 notifications 의 CHECK (category IN ('BADGE', 'HOTZONE', 'REMIND', 'VIDEO')) 와 일치.
 * HOTZONE = glossary "핫구역". VIDEO = 영상 처리 종결 통지 (MSG-313).
 */
public enum NotificationCategory {
	BADGE,
	HOTZONE,
	REMIND,
	VIDEO
}
