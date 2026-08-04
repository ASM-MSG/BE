package com.msg.fillmap.notification.service;

import com.msg.fillmap.notification.entity.NotificationCategory;

/**
 * 알림 발송 요청 진입점 (MSG-179 D6) — MSG-181 트리거가 호출한다. 게이트 없이 상시 빈 —
 * 기록은 DB insert 뿐이라 발송이 꺼져 있어도 이력은 쌓인다(켜면 릴레이가 밀린 PENDING 을 발행).
 */
public interface NotificationCommandService {

	/**
	 * 알림 요청을 outbox 에 기록한다. 반드시 호출자의 비즈니스 트랜잭션 안에서 호출할 것 —
	 * 같은 커밋이어야 FR-3(원자성)이 성립한다. 같은 (userId, eventKey) 재기록은 무시된다 (FR-6).
	 */
	void record(Long userId, NotificationCategory category, String eventKey, String title, String body);
}
