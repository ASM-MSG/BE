package com.msg.fillmap.notification.service;

import com.msg.fillmap.notification.entity.NotificationCategory;

/**
 * 알림 수신 설정 필터 지점 (MSG-179 D7) — 컨슈머가 발송 전에 조회한다. 상시 빈.
 */
public interface NotificationPreferenceService {

	/** 사용자가 이 카테고리 알림을 받는지. MSG-180 이 실제 설정 조회로 구현을 교체한다. */
	boolean isEnabled(Long userId, NotificationCategory category);
}
