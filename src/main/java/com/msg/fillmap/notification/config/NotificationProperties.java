package com.msg.fillmap.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 알림 발송 파이프라인 설정 (MSG-179). AiProperties 관례의 record 바인딩 — @ConfigurationPropertiesScan 이
 * 자동 등록한다. enabled 기본 false — 로컬/CI 는 Kafka·FCM 없이 그대로 green, dev/prod 만 env 로 켠다 (D11).
 *
 * @param enabled        파이프라인 게이트 (릴레이·컨슈머·스테일 배치·FCM 빈 활성화)
 * @param topic          Kafka 토픽 — 프로파일이 env prefix(dev./prod.)로 오버라이드해 격리한다 (D1)
 * @param consumerGroup  컨슈머 그룹 — 토픽과 같은 방식의 env 격리 (D1)
 * @param staleTokenDays last_used_at 무갱신 삭제 기준 일수 (D10 — Firebase 토큰 신선도 권장 2개월)
 */
@ConfigurationProperties(prefix = "fillmap.notification")
public record NotificationProperties(
	@DefaultValue("false") boolean enabled,
	@DefaultValue("notification.send") String topic,
	@DefaultValue("fillmap-notification") String consumerGroup,
	@DefaultValue Relay relay,
	@DefaultValue RateLimit rateLimit,
	@DefaultValue("60") int staleTokenDays,
	@DefaultValue Fcm fcm
) {

	/** 릴레이 폴러 (D13) — fixedDelay 주기와 PENDING 배치 크기. */
	public record Relay(
		@DefaultValue("5000") long pollIntervalMs,
		@DefaultValue("100") int batchSize
	) {
	}

	/** 전송률 일 상한 (D8·FR-12) — BADGE 는 무제한이라 키 자체가 없다. 일 경계는 KST 자정. */
	public record RateLimit(
		@DefaultValue("1") int hotzonePerDay,
		@DefaultValue("1") int remindPerDay
	) {
	}

	/** FCM 서비스 계정 키 (D9) — 경로만 설정으로, 파일은 레포 밖. enabled=true 인데 비어 있으면 기동 실패. */
	public record Fcm(String credentialsPath) {
	}
}
