package com.msg.fillmap.notification.consumer;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.extern.slf4j.Slf4j;

import com.msg.fillmap.notification.config.NotificationProperties;
import com.msg.fillmap.notification.entity.Notification;
import com.msg.fillmap.notification.entity.NotificationStatus;
import com.msg.fillmap.notification.entity.PushToken;
import com.msg.fillmap.notification.repository.NotificationRepository;
import com.msg.fillmap.notification.repository.PushTokenRepository;
import com.msg.fillmap.notification.sender.NotificationSender;
import com.msg.fillmap.notification.sender.NotificationSender.SendResult;
import com.msg.fillmap.notification.service.NotificationPreferenceService;

/**
 * 발송 컨슈머 (MSG-179 — 스펙 §도메인 로직 처리 순서 축자). 행 조회(종결이면 즉시 종료 — FR-6 2차)
 * → retry_count+1 → 설정 필터(PREF_OFF) → 전송률 제한(RATE_LIMITED) → 토큰 조회(NO_TOKEN) → 발송.
 * 전부 실패는 예외로 던져 DefaultErrorHandler 백오프 재시도 → 상한 소진 시 recoverer 가 DEAD 기록 (D4).
 *
 * 단계별 쓰기는 개별 트랜잭션(TransactionTemplate) — 메서드 전체를 묶으면 실패 시 retry_count 증가까지
 * 롤백돼 "몇 번 만에 성공했는지"(D4)가 사라진다. 부분 커밋은 at-least-once + 멱등(D5)이 흡수한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "fillmap.notification", name = "enabled")
public class NotificationConsumer {

	// 일 경계 = KST 자정 (glossary 스트릭 규칙과 동일). 판정 파라미터는 앱이 UTC 로 변환해 바인딩 (D8).
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final NotificationRepository notificationRepository;
	private final PushTokenRepository pushTokenRepository;
	private final NotificationPreferenceService notificationPreferenceService;
	private final NotificationSender notificationSender;
	private final NotificationProperties properties;
	private final TransactionTemplate tx;
	// 발송 결과 계측 (MSG-343) — 태그는 고정 조합만 선등록 (D1). dead 는 recoverer(NotificationConfig)가 센다.
	private final Counter sentCounter;
	private final Map<String, Counter> skippedCounters;

	public NotificationConsumer(NotificationRepository notificationRepository,
		PushTokenRepository pushTokenRepository, NotificationPreferenceService notificationPreferenceService,
		NotificationSender notificationSender, NotificationProperties properties,
		PlatformTransactionManager txManager, MeterRegistry meterRegistry) {
		this.notificationRepository = notificationRepository;
		this.pushTokenRepository = pushTokenRepository;
		this.notificationPreferenceService = notificationPreferenceService;
		this.notificationSender = notificationSender;
		this.properties = properties;
		this.tx = new TransactionTemplate(txManager);
		this.sentCounter = Counter.builder("notification.outcome")
			.tag("result", "sent").tag("reason", "none").register(meterRegistry);
		// skip 사유(D8 대문자 코드) → 소문자 고정 태그값 매핑 (스펙 표) — 미지 사유는 증가 없이 무시한다.
		this.skippedCounters = Map.of(
			NotificationRepository.SKIP_REASON_PREF_OFF, skippedCounter(meterRegistry, "pref_off"),
			NotificationRepository.SKIP_REASON_RATE_LIMITED, skippedCounter(meterRegistry, "rate_limited"),
			NotificationRepository.SKIP_REASON_NO_TOKEN, skippedCounter(meterRegistry, "no_token"));
	}

	private static Counter skippedCounter(MeterRegistry meterRegistry, String reason) {
		return Counter.builder("notification.outcome")
			.tag("result", "skipped").tag("reason", reason).register(meterRegistry);
	}

	@KafkaListener(topics = "${fillmap.notification.topic}", groupId = "${fillmap.notification.consumer-group}")
	public void consume(String message) {
		long id = Long.parseLong(message);
		Notification notification = notificationRepository.findById(id).orElse(null);
		if (notification == null) {
			log.warn("outbox 행 없음 — skip: notificationId={}", id);
			return;
		}
		if (notification.getStatus() != NotificationStatus.PENDING
			&& notification.getStatus() != NotificationStatus.PUBLISHED) {
			// 이미 SENT·SKIPPED·DEAD — Kafka 재전달(리밸런싱·오프셋 커밋 전 크래시)의 재발송 차단 (FR-6 2차).
			return;
		}
		tx.executeWithoutResult(status -> notificationRepository.incrementRetryCount(id));
		if (!notificationPreferenceService.isEnabled(notification.getUserId(), notification.getCategory())) {
			skip(id, NotificationRepository.SKIP_REASON_PREF_OFF);
			return;
		}
		if (rateLimited(notification)) {
			skip(id, NotificationRepository.SKIP_REASON_RATE_LIMITED);
			return;
		}
		List<PushToken> tokens = pushTokenRepository.findAllByUserId(notification.getUserId());
		if (tokens.isEmpty()) {
			skip(id, NotificationRepository.SKIP_REASON_NO_TOKEN);
			return;
		}
		SendResult result = notificationSender.send(
			tokens.stream().map(PushToken::getFcmToken).toList(),
			notification.getTitle(), notification.getBody());
		if (!result.invalidTokens().isEmpty()) {
			// FR-5 즉시 정리 — FCM 응답 경로에는 "현재 사용자"가 없어 소유 검증 없는 시스템 삭제 (D10).
			tx.executeWithoutResult(status ->
				pushTokenRepository.deleteAllByTokens(result.invalidTokens().toArray(new String[0])));
		}
		if (result.successCount() < 1) {
			// 전부 실패 → 재시도 경로 (D4). 전부 무효 토큰이었다면 다음 시도가 NO_TOKEN 으로 수렴한다.
			throw new IllegalStateException("FCM 발송 전부 실패: notificationId=" + id);
		}
		// 전이 UPDATE 커밋 후, 실제 갱신 1행일 때만 증가 (MSG-343 D5·완료 조건 5) — 발송 중 탈퇴
		// CASCADE 로 행이 지워지면 0행 갱신이라 전이가 없고, 계상도 하지 않는다 (Codex 2R).
		Integer sentRows = tx.execute(status -> notificationRepository.markSent(id));
		if (sentRows != null && sentRows == 1) {
			sentCounter.increment();
		}
	}

	/** 전송률 제한 (D8·FR-12) — HOTZONE·REMIND 일 상한, 나머지(BADGE·VIDEO·WEEKLY·FRIEND·MODERATION 등) 무제한. 카운트는 DB 1문장. */
	private boolean rateLimited(Notification notification) {
		Integer perDay = switch (notification.getCategory()) {
			case HOTZONE -> properties.rateLimit().hotzonePerDay();
			case REMIND -> properties.rateLimit().remindPerDay();
			case BADGE -> null;   // 사용자 액션 직결·희소 — 무제한 (PRD FR-12 확정)
			case VIDEO -> null;   // 자기 영상 결과 통지 — 업로드 수만큼만 발생, 도배 벡터 없음 (MSG-313 FR-9)
			case WEEKLY -> null;  // 이벤트 키가 주 단위라 트리거가 주 1건 이상 못 만든다 (MSG-315 D6)
			case FRIEND -> null;  // 상대별 하루 1건은 기록 단계 event_key 가 이미 막고, 다수 상대의 요청은 정당 (MSG-416 D5)
			case MODERATION -> null;  // 제재 통지는 희소하고 사용자 행동 직결 — 뱃지 획득과 같은 결 (MSG-417 5절)
			// 알림이 기기 로컬 생성이라 서버 발송 경로가 없고, 하루 5건 상한(PRD FR-4)도 앱 상수라 서버가 세지
			// 않는다. 이 case 에 런타임이 도달하면 그 자체가 결함이다 — outbox 에 이 카테고리 행이 생겼다는 뜻 (MSG-418).
			case MISSION_NEARBY -> null;
		};
		if (perDay == null) {
			return false;
		}
		LocalDateTime threshold = LocalDate.now(KST).atStartOfDay(KST)
			.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
		return notificationRepository.countSentSince(
			notification.getUserId(), notification.getCategory().name(), threshold) >= perDay;
	}

	private void skip(long id, String reason) {
		Integer updated = tx.execute(status -> notificationRepository.markSkipped(id, reason));
		Counter counter = skippedCounters.get(reason);
		if (counter != null && updated != null && updated == 1) {
			counter.increment();
		}
	}
}
