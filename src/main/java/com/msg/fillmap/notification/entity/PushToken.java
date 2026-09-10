package com.msg.fillmap.notification.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * FCM 푸시 토큰 row (push_tokens, MSG-178). fcm_token 자연키 PK (Grid 엔티티 선례) —
 * 쓰기는 전부 native(UPSERT·DELETE)라 리포지토리 앵커 겸 조회 매핑만 담당한다.
 */
@Entity
@Table(name = "push_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PushToken {

	@Id
	@Column(name = "fcm_token", length = 512)
	private String fcmToken;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private PushPlatform platform;

	@Column(name = "app_version", length = 20)
	private String appVersion;

	@Column(name = "last_used_at", nullable = false)
	private LocalDateTime lastUsedAt;
}
