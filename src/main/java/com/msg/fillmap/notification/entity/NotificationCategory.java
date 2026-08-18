package com.msg.fillmap.notification.entity;

/**
 * 알림 카테고리 (MSG-179) — V35 notifications 의
 * CHECK (category IN ('BADGE', 'HOTZONE', 'REMIND', 'VIDEO', 'WEEKLY', 'FRIEND', 'MODERATION')) 와 일치.
 * HOTZONE = glossary "핫구역". VIDEO = 영상 처리 종결 통지 (MSG-313). WEEKLY = 주간 활동 요약 (MSG-315).
 * FRIEND = 친구 요청 접수·수락 통지 (MSG-416). MODERATION = 블라인드 전환·해제 소유자 통지 (MSG-417) —
 * 수신 거부 불가라 설정 표면(응답 목록·PATCH·스키마 허용값)에서 제외된다 (FR-7, opt_outs CHECK 에도 없음).
 */
public enum NotificationCategory {
	BADGE,
	HOTZONE,
	REMIND,
	VIDEO,
	WEEKLY,
	FRIEND,
	MODERATION
}
