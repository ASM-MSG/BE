package com.msg.fillmap.notification.service;

import com.msg.fillmap.notification.dto.NotificationPreferenceResponseDto;
import com.msg.fillmap.notification.entity.NotificationCategory;

/**
 * 알림 수신 설정 (MSG-179 D7 필터 지점 + MSG-180 설정 API). isEnabled 는 컨슈머가 발송 전에
 * 조회하고, 나머지 둘은 설정 화면 REST 전용이다 — 시그니처·컨슈머 호출부 무변경(D7 규약).
 */
public interface NotificationPreferenceService {

	/** 사용자가 이 카테고리 알림을 받는지. (기존 — 시그니처·컨슈머 호출부 무변경, 179 D7) */
	boolean isEnabled(Long userId, NotificationCategory category);

	/** 설정 대상 카테고리 6종(MODERATION 제외 — 수신 거부 불가, MSG-417 FR-7)의 수신 상태 — 저장 행이 없는 카테고리는 on (FR-7 opt-out). */
	NotificationPreferenceResponseDto getPreferences(Long userId);

	/** 단건 토글 (멱등) — category 는 대소문자 무시 파싱, 목록 외면 10420. 변경 후 전체 상태 반환. */
	NotificationPreferenceResponseDto update(Long userId, String category, boolean enabled);
}
