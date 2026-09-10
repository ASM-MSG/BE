package com.msg.fillmap.notification.service;

import com.msg.fillmap.notification.dto.NotificationPageResponseDto;
import com.msg.fillmap.notification.dto.NotificationUnreadCountResponseDto;

/**
 * 알림함 조회·읽음 (MSG-434). 발송 기록으로만 쌓이던 notifications 위에 얹은 조회 계층으로,
 * 발송 동작(outbox 릴레이·Kafka 소비·FCM 전송)은 건드리지 않는다.
 */
public interface NotificationInboxService {

	/**
	 * 알림 목록 한 페이지 (FR-1·FR-2). 최근 30일 이내 생성분 중 수신 거부로 스킵된 알림을 뺀
	 * 내 알림을 최신순으로 반환한다. 목록 조회는 읽음 상태를 바꾸지 않는다 (읽음은 행 탭 시점에만).
	 *
	 * @param cursor 직전 응답의 nextCursor. null 이면 첫 페이지
	 * @param size 페이지 크기 — 0 이하면 20, 50 초과면 50 으로 클램프한다 (D-3)
	 */
	NotificationPageResponseDto getInbox(long userId, Long cursor, int size);

	/** 안읽음 개수 (FR-6) — 목록과 같은 노출 조건. 안읽은 알림이 없으면 0 이다. */
	NotificationUnreadCountResponseDto getUnreadCount(long userId);

	/**
	 * 개별 읽음 (FR-4) — 이미 읽은 알림도 성공하고 최초 읽음 시각이 보존된다 (멱등).
	 * 없거나 타인 소유면 구분 없이 10404 다 (FR-7).
	 */
	void markRead(long userId, long notificationId);

	/** 전체 읽음 (FR-5) — 안읽은 알림이 0건이어도 성공한다 (멱등). */
	void markAllRead(long userId);
}
