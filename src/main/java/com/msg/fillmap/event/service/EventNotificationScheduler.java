package com.msg.fillmap.event.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.extern.slf4j.Slf4j;

import com.msg.fillmap.event.entity.EventNotificationSubscription;
import com.msg.fillmap.event.entity.EventOccurrence;
import com.msg.fillmap.event.repository.EventNotificationSubscriptionRepository;
import com.msg.fillmap.event.repository.EventOccurrenceRepository;
import com.msg.fillmap.event.repository.EventSeriesRepository;
import com.msg.fillmap.notification.entity.NotificationCategory;
import com.msg.fillmap.notification.service.NotificationCommandService;

/**
 * 행사 생명주기 경계의 부수효과 (MSG-442, FR-EVENT-06). 한 틱에 두 단계를 순서대로 한다:
 * 시작 알림 발송과 종료 구독 자동 해제.
 * <p>
 * **빈은 조건 없이 항상 뜬다.** {@code fillmap.notification.enabled} 로 클래스를 게이트하면 안 된다 —
 * 그 속성의 기본값이 false 라(local·CI 가 이 상태) 스케줄러가 통째로 죽으면 구독 API 는 열려 있는데 종료
 * 구독 해제가 영원히 돌지 않아 행이 무한 축적된다. 정리는 발송 게이트 밖이어야 하므로, 게이트는 발송
 * 단계 안에서만 검사한다. 스케줄링 인프라 자체는 {@code EventSchedulingConfig} 가 무조건 제공한다.
 * <p>
 * 두 단계 모두 멱등이다. 발송은 outbox 의 {@code uq_notifications_user_event}(user_id + event_key)가
 * 재기록을 흡수하고(WeeklySummaryScheduler 선례), 해제는 DELETE 라 본성상 재실행 안전하다(PRD §10).
 */
@Slf4j
@Component
public class EventNotificationScheduler {

	/**
	 * 시작 알림 스캔 상한 — dedupe 만으로도 중복은 없지만, 열흘짜리 LIVE 회차를 매 틱 다시 훑어 전 구독자
	 * 무효 insert 를 반복하지 않기 위한 창이다. 서버가 24시간 연속으로 죽어 창을 통째로 놓치면 시작 알림은
	 * 유실을 허용한다 — 넛지는 보장이 아니라 기회다 (MSG-314 D3 승계).
	 */
	private static final int START_WINDOW_HOURS = 24;

	/** 시작 알림 문구 (MSG-442 발송 표 확정값). 일정 변경 알림은 트리거가 시더라 EventSeeder 가 갖는다. */
	private static final String START_BODY = "행사가 시작됐어요. 현장 영상을 올려보세요";

	private final EventOccurrenceRepository occurrenceRepository;
	private final EventNotificationSubscriptionRepository subscriptionRepository;
	private final EventSeriesRepository seriesRepository;
	private final NotificationCommandService notificationCommandService;
	private final TransactionTemplate tx;
	private final boolean notificationEnabled;
	private final Clock clock;

	@Autowired
	public EventNotificationScheduler(
		EventOccurrenceRepository occurrenceRepository,
		EventNotificationSubscriptionRepository subscriptionRepository,
		EventSeriesRepository seriesRepository,
		NotificationCommandService notificationCommandService,
		PlatformTransactionManager txManager,
		@Value("${fillmap.notification.enabled:false}") boolean notificationEnabled
	) {
		this(occurrenceRepository, subscriptionRepository, seriesRepository, notificationCommandService,
			txManager, notificationEnabled, Clock.systemUTC());
	}

	/** 발송 창·종료 경계 검증용 고정 Clock 주입 (WeeklySummaryScheduler 선례). */
	public EventNotificationScheduler(
		EventOccurrenceRepository occurrenceRepository,
		EventNotificationSubscriptionRepository subscriptionRepository,
		EventSeriesRepository seriesRepository,
		NotificationCommandService notificationCommandService,
		PlatformTransactionManager txManager,
		boolean notificationEnabled,
		Clock clock
	) {
		this.occurrenceRepository = occurrenceRepository;
		this.subscriptionRepository = subscriptionRepository;
		this.seriesRepository = seriesRepository;
		this.notificationCommandService = notificationCommandService;
		this.tx = new TransactionTemplate(txManager);
		this.notificationEnabled = notificationEnabled;
		this.clock = clock;
	}

	/**
	 * 첫 틱을 폴 주기만큼 미룬다. 기본값이면 기동 직후가 아니라 5분 뒤부터 도는데, 운영 영향은 그 지연
	 * 하나뿐이고(둘 다 멱등하고 시간에 쫓기지 않는 작업이다) 대신 테스트 컨텍스트 수명 안에서는 이 빈이
	 * 한 번도 돌지 않는다. 빈이 조건 없이 뜨는 설계라 모든 {@code @SpringBootTest} 가 이 스케줄러를 함께
	 * 띄우고, 실시각 틱이 고정 시각 픽스처의 회차를 종료로 판정해 남의 테스트 데이터를 지울 수 있다.
	 */
	@Scheduled(
		fixedDelayString = "${fillmap.event.lifecycle.poll-interval-ms:300000}",
		initialDelayString = "${fillmap.event.lifecycle.poll-interval-ms:300000}")
	public void tick() {
		LocalDateTime now = LocalDateTime.now(clock);
		if (notificationEnabled) {
			notifyStarted(now);
		}
		releaseEndedSubscriptions(now);
	}

	/**
	 * 시작 알림 발송 — 시작했고 아직 끝나지 않았으며 발송 창 안인 회차의 구독자에게 1회씩 기록한다.
	 * 시작 후에 구독한 사용자({@code created_at > starts_at})는 행사가 진행 중임을 이미 알고 눌렀으므로
	 * 뒤늦은 "시작됐어요"를 보내지 않는다.
	 * <p>
	 * 이벤트 키에 startsAt 을 넣는 이유는 일정 변경으로 시작이 미뤄지면 새 시각 도래 때 알림이 다시 나가야
	 * 하기 때문이다 — 일정 버전마다 키가 달라 dedupe 가 버전 단위로 걸린다.
	 */
	private void notifyStarted(LocalDateTime now) {
		List<EventOccurrence> occurrences = tx.execute(status -> occurrenceRepository
			.findStartNotificationCandidates(now, now.minusHours(START_WINDOW_HOURS)));
		if (occurrences == null || occurrences.isEmpty()) {
			return;
		}
		int failed = 0;
		Exception lastError = null;
		for (EventOccurrence occurrence : occurrences) {
			String eventKey = "EVENT_START:%s:%d".formatted(
				occurrence.getOccurrenceKey(), occurrence.getStartsAt().toEpochSecond(ZoneOffset.UTC));
			for (EventNotificationSubscription subscription : subscribersBefore(occurrence)) {
				try {
					notificationCommandService.record(subscription.getId().getUserId(), NotificationCategory.EVENT,
						eventKey, occurrence.getTitle(), START_BODY);
				} catch (Exception e) {
					failed++;   // 사용자 단위 격리 — 한 명 실패가 나머지 구독자를 막지 않는다
					lastError = e;
				}
			}
		}
		if (failed > 0) {
			log.warn("행사 시작 알림 기록 실패 {}건 — 다음 틱 재실행이 재시도, dedupe 가 중복 흡수", failed, lastError);
		}
	}

	/** 시작 시각 이전에 구독한 사용자만. 조회는 회차 단위 파생 쿼리라 회차 수만큼만 돈다. */
	private List<EventNotificationSubscription> subscribersBefore(EventOccurrence occurrence) {
		List<EventNotificationSubscription> subscriptions = tx.execute(status ->
			subscriptionRepository.findAllByIdEventOccurrenceId(occurrence.getId()));
		if (subscriptions == null) {
			return List.of();
		}
		return subscriptions.stream()
			.filter(subscription -> !subscription.getCreatedAt().isAfter(occurrence.getStartsAt()))
			.toList();
	}

	/**
	 * 종료 구독 자동 해제 (FR-16, PRD §4.2 유예부터 자동 OFF). 이 삭제는 저장소 정리다 — 사용자에게 보이는
	 * OFF 전환은 {@code EventNotificationServiceImpl} 의 파생식이 종료 시각에 즉시 수행하므로 정리 지연이
	 * 노출 상태에 영향을 주지 않는다.
	 * <p>
	 * 재시드와의 경쟁은 advisory lock 으로 직렬화한다: 정리 트랜잭션이 구 ends_at 스냅숏으로 도는 사이
	 * 시더가 일정 연장을 커밋하면 되살아난 회차의 구독이 전멸하고 그 일정 변경 알림도 수신자 0 으로
	 * 유실된다. 락 획득 후의 DELETE 는 연장이 커밋된 ends_at 을 읽으므로 연장된 회차는 자연히 no-op 이다.
	 * 순서가 반대라면(정리가 먼저 커밋) 종료 경계 통과 = 해제라는 의미론대로 해제가 정당하게 남는다.
	 */
	private void releaseEndedSubscriptions(LocalDateTime now) {
		Integer released = tx.execute(status -> {
			seriesRepository.acquireEventWriteLock();
			return subscriptionRepository.deleteByOccurrenceEndedAtOrBefore(now);
		});
		if (released != null && released > 0) {
			log.info("종료된 행사 구독 {}건 자동 해제", released);
		}
	}
}
