package com.msg.fillmap.event.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.event.dto.EventNotificationResponseDto;
import com.msg.fillmap.event.entity.EventNotificationSubscriptionId;
import com.msg.fillmap.event.entity.EventOccurrence;
import com.msg.fillmap.event.entity.EventStatus;
import com.msg.fillmap.event.exception.EventErrorCode;
import com.msg.fillmap.event.repository.EventNotificationSubscriptionRepository;
import com.msg.fillmap.event.repository.EventOccurrenceRepository;
import com.msg.fillmap.global.exception.ApiException;

/**
 * 행사 알림 구독 구현 (MSG-442, FR-EVENT-06).
 * <p>
 * **노출 상태는 파생값이다**: 구독 행 존재 AND 상태가 예정·진행 중. 행 존재만 보면 종료 시각부터
 * 스케줄러의 다음 정리 틱까지 헤더가 ON 으로 보여 PRD §4.2 "유예부터 자동 OFF" 와 모순된다. 스케줄러의
 * 행 삭제는 정리이지 상태 전환이 아니고, 상태 전환은 이 파생식 하나가 담당한다.
 * <p>
 * 노출 전 예정 회차는 존재를 숨긴다 — 조회 네 경로(MSG-439)와 같은 술어
 * ({@link EventOccurrence#isVisibleAt})를 쓴다. 갈라지면 순차 id 대입만으로 미공개 행사의 존재가 드러난다.
 */
@Service
@Transactional(readOnly = true)
public class EventNotificationServiceImpl implements EventNotificationService {

	/** 구독이 의미를 갖는 상태 — 종료(유예·아카이브)부터는 받을 알림이 없다 (PRD §4.2). */
	private static final Set<EventStatus> SUBSCRIBABLE = Set.of(EventStatus.UPCOMING, EventStatus.LIVE);

	private final EventOccurrenceRepository occurrenceRepository;
	private final EventNotificationSubscriptionRepository subscriptionRepository;
	private final Clock clock;

	/** 프로덕션 생성자 — starts_at·ends_at 이 UTC 저장이라 기본존(로컬 KST)이면 상태 판정이 9시간 어긋난다. */
	@Autowired
	public EventNotificationServiceImpl(EventOccurrenceRepository occurrenceRepository,
		EventNotificationSubscriptionRepository subscriptionRepository) {
		this(occurrenceRepository, subscriptionRepository, Clock.systemUTC());
	}

	/** 상태 경계 검증용 고정 Clock 주입 (EventQueryServiceImpl 선례). */
	public EventNotificationServiceImpl(EventOccurrenceRepository occurrenceRepository,
		EventNotificationSubscriptionRepository subscriptionRepository, Clock clock) {
		this.occurrenceRepository = occurrenceRepository;
		this.subscriptionRepository = subscriptionRepository;
		this.clock = clock;
	}

	@Override
	@Transactional
	public EventNotificationResponseDto updateSubscription(long userId, long occurrenceId, boolean enabled) {
		LocalDateTime now = LocalDateTime.now(clock);
		EventOccurrence occurrence = occurrenceRepository.findById(occurrenceId)
			.filter(found -> found.isVisibleAt(now))
			.orElseThrow(() -> new ApiException(EventErrorCode.EVENT_NOT_FOUND));

		if (!enabled) {
			// OFF 는 상태와 무관하게 항상 성공한다 — 구독 해제를 막을 이유가 없다
			subscriptionRepository.deleteSubscription(userId, occurrenceId);
			return new EventNotificationResponseDto(false);
		}
		if (!SUBSCRIBABLE.contains(occurrence.statusAt(now))) {
			// 종료 후 ON 은 상태표와 모순이고(유예부터 자동 OFF), 시작 알림이 이미 지난 구독은 받을 것이 없다
			throw new ApiException(EventErrorCode.EVENT_INTERACTION_LOCKED);
		}
		subscriptionRepository.insertSubscription(userId, occurrenceId);
		return new EventNotificationResponseDto(true);
	}

	@Override
	public boolean isSubscribed(Long userId, Long occurrenceId) {
		return isSubscribed(userId, occurrenceId, LocalDateTime.now(clock));
	}

	@Override
	public boolean isSubscribed(Long userId, Long occurrenceId, LocalDateTime now) {
		if (userId == null) {
			return false;
		}
		return occurrenceRepository.findById(occurrenceId)
			.filter(occurrence -> SUBSCRIBABLE.contains(occurrence.statusAt(now)))
			.filter(occurrence -> subscriptionRepository.existsById(
				new EventNotificationSubscriptionId(userId, occurrenceId)))
			.isPresent();
	}
}
