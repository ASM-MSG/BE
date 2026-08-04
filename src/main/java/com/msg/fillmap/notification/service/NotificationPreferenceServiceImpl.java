package com.msg.fillmap.notification.service;

import org.springframework.stereotype.Service;

import com.msg.fillmap.notification.entity.NotificationCategory;

/**
 * 전부-허용 기본 구현 (MSG-179 D7) — opt-out 기본 전부-on(FR-7 기본값과 일치).
 * MSG-180 이 이 Impl 내부만 실제 설정 조회로 교체한다 — 컨슈머(호출부)는 무변경.
 */
@Service
public class NotificationPreferenceServiceImpl implements NotificationPreferenceService {

	@Override
	public boolean isEnabled(Long userId, NotificationCategory category) {
		return true;
	}
}
