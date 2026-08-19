package com.msg.fillmap.notification.entity;

/**
 * 알림 카테고리 (MSG-179) — notifications(V35)·notification_opt_outs(V36) 두 테이블 CHECK 의 합집합과 일치.
 * HOTZONE = glossary "핫구역". VIDEO = 영상 처리 종결 통지 (MSG-313). WEEKLY = 주간 활동 요약 (MSG-315).
 * FRIEND = 친구 요청 접수·수락 통지 (MSG-416). MODERATION = 블라인드 전환·해제 소유자 통지 (MSG-417) —
 * 수신 거부 불가라 설정 표면(응답 목록·PATCH·스키마 허용값)에서 제외된다 (FR-7, opt_outs CHECK 에도 없음).
 * MISSION_NEARBY = 근접 미션 알림 설정 (MSG-418) — 알림이 기기 로컬 생성이라 서버 발송 경로가 없는 최초의
 * 카테고리다. notifications CHECK 가 아니라 notification_opt_outs CHECK 에만 실린다 (MODERATION 의 거울상).
 */
public enum NotificationCategory {
	BADGE,
	HOTZONE,
	REMIND,
	VIDEO,
	WEEKLY,
	FRIEND,
	MODERATION,
	MISSION_NEARBY
}
