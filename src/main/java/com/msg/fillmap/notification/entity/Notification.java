package com.msg.fillmap.notification.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 알림 outbox 겸 발송 기록 row (notifications, MSG-179). 행 하나가 요청→결과의 전 수명 —
 * 쓰기는 전부 native(record INSERT·상태 갱신)라 리포지토리 앵커 겸 조회 매핑만 담당한다 (PushToken 선례).
 */
@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private NotificationCategory category;

	@Column(name = "event_key", nullable = false, length = 100)
	private String eventKey;

	@Column(nullable = false, length = 100)
	private String title;

	@Column(nullable = false, length = 255)
	private String body;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private NotificationStatus status;

	@Column(name = "retry_count", nullable = false)
	private int retryCount;

	@Column(name = "last_error", length = 255)
	private String lastError;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "sent_at")
	private LocalDateTime sentAt;
}
