package com.msg.fillmap.notification.entity;

/**
 * 알림 카테고리 (MSG-179) — V21 notifications 의 CHECK (category IN ('BADGE', 'HOTZONE', 'REMIND')) 와 일치.
 * HOTZONE = glossary "핫구역".
 */
public enum NotificationCategory {
	BADGE,
	HOTZONE,
	REMIND
}
