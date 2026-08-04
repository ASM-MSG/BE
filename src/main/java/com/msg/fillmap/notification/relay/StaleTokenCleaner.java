package com.msg.fillmap.notification.relay;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.extern.slf4j.Slf4j;

import com.msg.fillmap.notification.config.NotificationProperties;
import com.msg.fillmap.notification.repository.PushTokenRepository;

/**
 * 스테일 푸시 토큰 일 배치 (MSG-179 D10). last_used_at 이 stale-token-days(기본 60일 — Firebase 토큰
 * 신선도 권장 2개월) 무갱신인 행을 트래픽 최저 시간대(KST 04시)에 삭제한다. 클라 규약이 "로그인 직후
 * POST"(MSG-178)라 활성 사용자는 앱 기동마다 갱신된다 — 60일 무갱신 = 죽은 토큰일 확률이 압도적.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "fillmap.notification", name = "enabled")
public class StaleTokenCleaner {

	private final PushTokenRepository pushTokenRepository;
	private final NotificationProperties properties;
	private final TransactionTemplate tx;

	public StaleTokenCleaner(PushTokenRepository pushTokenRepository, NotificationProperties properties,
		PlatformTransactionManager txManager) {
		this.pushTokenRepository = pushTokenRepository;
		this.properties = properties;
		this.tx = new TransactionTemplate(txManager);
	}

	@Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
	public void clean() {
		// threshold 는 앱 UTC 계산 (D10) — 세션 TZ 캐스트 스큐(MSG-222 실측) 재발 방지.
		LocalDateTime threshold = LocalDateTime.now(ZoneOffset.UTC).minusDays(properties.staleTokenDays());
		Integer deleted = tx.execute(status -> pushTokenRepository.deleteStale(threshold));
		if (deleted != null && deleted > 0) {
			log.info("스테일 푸시 토큰 삭제: {}건 (threshold={})", deleted, threshold);
		}
	}
}
