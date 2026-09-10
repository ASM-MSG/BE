package com.msg.fillmap.notification.relay;

import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.msg.fillmap.notification.repository.NotificationRepository;

/**
 * 알림 백로그 Gauge (MSG-343 D3) — 60초 주기 DB 폴링으로 PENDING·PUBLISHED 건수를 AtomicLong 에
 * 담고 그 값을 notification.backlog 로 노출한다. scrape 시점 조회를 쓰지 않는 이유: 수집 주기와
 * DB 부하가 결합되고 scrape 가 멎으면 값도 멎는다. 60초 근거: 백로그는 분 단위 추세 지표라
 * 릴레이 폴링(5초)보다 촘촘할 이유가 없다. 스케줄링은 NotificationConfig 의 @EnableScheduling 재사용 —
 * 알림 비활성 환경(로컬·CI)에서는 빈이 뜨지 않아 Gauge 도 노출되지 않는다(릴레이·컨슈머와 같은 게이트).
 */
@Component
@ConditionalOnProperty(prefix = "fillmap.notification", name = "enabled")
public class NotificationBacklogMetrics {

	private final NotificationRepository notificationRepository;
	private final AtomicLong pending = new AtomicLong();
	private final AtomicLong published = new AtomicLong();

	public NotificationBacklogMetrics(NotificationRepository notificationRepository, MeterRegistry meterRegistry) {
		this.notificationRepository = notificationRepository;
		Gauge.builder("notification.backlog", pending, AtomicLong::get)
			.tag("status", "pending").register(meterRegistry);
		Gauge.builder("notification.backlog", published, AtomicLong::get)
			.tag("status", "published").register(meterRegistry);
	}

	@Scheduled(fixedDelay = 60_000)
	public void refresh() {
		pending.set(notificationRepository.countPending());
		published.set(notificationRepository.countPublished());
	}
}
