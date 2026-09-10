package com.msg.fillmap.notification.relay;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.extern.slf4j.Slf4j;

import com.msg.fillmap.notification.config.NotificationProperties;
import com.msg.fillmap.notification.entity.Notification;
import com.msg.fillmap.notification.repository.NotificationRepository;

/**
 * outbox 릴레이 폴러 (MSG-179 D13, AiBlurPoller 의 fixedDelay 선례 — 폴 중첩 없음). PENDING 을 오래된
 * 순으로 배치 조회해 행별로 Kafka 발행(send().get() 동기 확인) 후 PUBLISHED 전이한다. 발행 실패(브로커
 * 다운)면 상태를 유지하고 **남은 배치를 중단**해 다음 주기에 재시도한다 — 브로커 다운이면 행마다
 * delivery timeout(기본 2분)을 대기해 배치 100행이 스케줄링 스레드(Spring 기본 단일)를 수 시간 점유,
 * StaleTokenCleaner·AiBlurPoller 까지 블록하기 때문. 폴러 자체는 catch 로 죽지 않는다.
 *
 * 사이클 첫머리에 스테일 PUBLISHED(기본 30분 초과)를 PENDING 으로 복구한다 — 컨슈머가 브로커
 * 보존기간보다 오래 다운되면 메시지 소실로 PUBLISHED 가 영구 잔류하기 때문(Codex 3R P2). 복구된
 * 행은 같은 사이클의 findPendingBatch 가 자연 재발행하고, 중복은 컨슈머 멱등(D5 2차)이 흡수한다.
 *
 * ponytail: 단일 앱 인스턴스 전제(dev/prod 각 1, systemd 실측)라 FOR UPDATE SKIP LOCKED 를 안 쓴다 —
 * 앱 스케일아웃 시 폴러 경합이 생기므로 그때 클레임 잠금으로 승격할 것.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "fillmap.notification", name = "enabled")
public class NotificationRelay {

	private final NotificationRepository notificationRepository;
	private final KafkaTemplate<String, String> kafkaTemplate;
	private final NotificationProperties properties;
	private final TransactionTemplate tx;

	public NotificationRelay(NotificationRepository notificationRepository,
		KafkaTemplate<String, String> kafkaTemplate, NotificationProperties properties,
		PlatformTransactionManager txManager) {
		this.notificationRepository = notificationRepository;
		this.kafkaTemplate = kafkaTemplate;
		this.properties = properties;
		this.tx = new TransactionTemplate(txManager);
	}

	@Scheduled(fixedDelayString = "${fillmap.notification.relay.poll-interval-ms:5000}")
	public void relay() {
		resetStalePublished();
		List<Notification> batch = notificationRepository.findPendingBatch(properties.relay().batchSize());
		for (int i = 0; i < batch.size(); i++) {
			Notification notification = batch.get(i);
			try {
				// 메시지 = outbox id 문자열 (thin payload) — 컨슈머가 DB 재조회하므로 스키마 진화 문제가 없다.
				kafkaTemplate.send(properties.topic(), String.valueOf(notification.getId())).get();
				// 발행 성공 후 갱신 — send~markPublished 사이 크래시는 재발행 1회로 끝나고 컨슈머 멱등(D5)이 흡수.
				tx.executeWithoutResult(status -> notificationRepository.markPublished(notification.getId()));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				log.warn("알림 발행 인터럽트 — 잔여 배치 중단, PENDING 유지: notificationId={}", notification.getId(), e);
				return;
			} catch (Exception e) {
				// 남은 행도 어차피 PENDING 유지라 다음 주기가 자연 재시도 — 행별 timeout 대기로 스레드를 점유하지 않는다.
				log.warn("알림 발행 실패 — 이 행 포함 남은 {}건 배치 중단, PENDING 유지(다음 주기 재시도): notificationId={}",
					batch.size() - i, notification.getId(), e);
				return;
			}
		}
	}

	/** threshold 는 앱 UTC 계산 — 세션 TZ 캐스트 스큐(MSG-222 실측) 재발 방지. 복구 발생 = 컨슈머 미달 운영 신호. */
	private void resetStalePublished() {
		LocalDateTime threshold =
			LocalDateTime.now(ZoneOffset.UTC).minusMinutes(properties.relay().stalePublishedMinutes());
		Integer reset = tx.execute(status -> notificationRepository.resetStalePublished(threshold));
		if (reset != null && reset > 0) {
			log.warn("스테일 PUBLISHED {}건 → PENDING 복구 — 컨슈머 미달 신호, 이번 사이클에 재발행된다 (threshold={})",
				reset, threshold);
		}
	}
}
