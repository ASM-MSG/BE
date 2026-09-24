package com.msg.fillmap.notification.service;

import java.time.LocalDateTime;

import com.msg.fillmap.notification.entity.NotificationCategory;
import com.msg.fillmap.notification.entity.NotificationTarget;

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

	/**
	 * 딥링크 이동 대상을 함께 기록한다 (MSG-432 FR-1). target 이 null 이면 대상 없음 — 5인자와 같다.
	 * 대상은 앱이 이미 API 로 조회할 수 있는 공개 식별자만 쓴다 (비기능 보안).
	 */
	void record(Long userId, NotificationCategory category, String eventKey, String title, String body,
		NotificationTarget target);

	/** 회차 시작 정각까지의 현재 구독자에게 멱등 기록한다. 호출자의 발송 트랜잭션에 참여한다. 대상은 그 회차다 (MSG-432). */
	void recordEventStart(long occurrenceId, LocalDateTime startsAt, String eventKey, String title, String body);
}
