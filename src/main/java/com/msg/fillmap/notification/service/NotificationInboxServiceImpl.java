package com.msg.fillmap.notification.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.notification.dto.NotificationPageResponseDto;
import com.msg.fillmap.notification.dto.NotificationPageResponseDto.NotificationItemResponseDto;
import com.msg.fillmap.notification.dto.NotificationUnreadCountResponseDto;
import com.msg.fillmap.notification.entity.Notification;
import com.msg.fillmap.notification.exception.NotificationErrorCode;
import com.msg.fillmap.notification.repository.NotificationRepository;

/**
 * 알림함 구현 (MSG-434). 조회 2종은 같은 노출 조건(리포지토리 INBOX_VISIBLE)을 공유하고, 쓰기 2종은
 * read_at 만 만지는 한 문장 UPDATE 라 outbox 상태 전이와 컬럼이 겹치지 않는다 (D-5 정합).
 * 쓰기는 대상 엔티티를 먼저 로드하지 않고 같은 트랜잭션에서 Notification 을 재사용하지도 않는다
 * (영속성 컨텍스트 stale 방지 — clearAutomatically 불요).
 */
@Service
@RequiredArgsConstructor
public class NotificationInboxServiceImpl implements NotificationInboxService {

	// size 클램프 계약 (D-3, VideoServiceImpl 선례) — 미지정·0 이하는 기본값, 상한 초과만 자른다.
	private static final int PAGE_DEFAULT_SIZE = 20;
	private static final int PAGE_MAX_SIZE = 50;
	// 노출 창 (FR-10) — KST 달력일이 아니라 순수 720시간 롤링 윈도우다 (D-4).
	private static final int INBOX_WINDOW_DAYS = 30;

	private final NotificationRepository notificationRepository;
	private final Clock clock;

	/**
	 * 프로덕션 생성자 — clock 을 Clock.systemUTC() 로 고정해 Lombok 전체 생성자로 위임한다
	 * (BadgeAwardServiceImpl 선례). 전체 생성자는 30일 경계 검증용 고정 클럭 주입에 쓴다.
	 */
	@Autowired
	public NotificationInboxServiceImpl(NotificationRepository notificationRepository) {
		this(notificationRepository, Clock.systemUTC());
	}

	@Override
	@Transactional(readOnly = true)
	public NotificationPageResponseDto getInbox(long userId, Long cursor, int size) {
		int pageSize = size < 1 ? PAGE_DEFAULT_SIZE : Math.min(size, PAGE_MAX_SIZE);
		// 첫 페이지는 커서 상한을 열어 둔다 — 쿼리 메서드를 하나로 유지하려는 치환이다 (D-3).
		long from = cursor != null ? cursor : Long.MAX_VALUE;
		// lookahead 1건으로 hasNext 를 판정하고 초과분은 버린다.
		List<Notification> rows = notificationRepository.findInboxPage(
			userId, threshold(), from, PageRequest.of(0, pageSize + 1));
		boolean hasNext = rows.size() > pageSize;
		List<NotificationItemResponseDto> items = rows.stream()
			.limit(pageSize)
			.map(NotificationItemResponseDto::from)
			.toList();
		Long nextCursor = hasNext ? items.get(items.size() - 1).notificationId() : null;
		return new NotificationPageResponseDto(items, nextCursor, hasNext);
	}

	@Override
	@Transactional(readOnly = true)
	public NotificationUnreadCountResponseDto getUnreadCount(long userId) {
		return new NotificationUnreadCountResponseDto(notificationRepository.countUnread(userId, threshold()));
	}

	@Override
	@Transactional
	public void markRead(long userId, long notificationId) {
		// 0행 = 없거나 타인 소유. 둘을 응답으로 구분하지 않는다 (FR-7) — 술어 하나가 그대로 정책이다.
		if (notificationRepository.markRead(notificationId, userId, LocalDateTime.now(clock)) < 1) {
			throw new ApiException(NotificationErrorCode.NOTIFICATION_NOT_FOUND);
		}
	}

	@Override
	@Transactional
	public void markAllRead(long userId) {
		notificationRepository.markAllRead(userId, LocalDateTime.now(clock));
	}

	private LocalDateTime threshold() {
		return LocalDateTime.now(clock).minusDays(INBOX_WINDOW_DAYS);
	}
}
