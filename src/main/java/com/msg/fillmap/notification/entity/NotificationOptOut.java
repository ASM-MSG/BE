package com.msg.fillmap.notification.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 알림 수신 거부 row (notification_opt_outs, MSG-180 D1). 불변식: **행 존재 = 해당 카테고리 off,
 * 행 부재 = on**(FR-7 opt-out) — 행은 생성/삭제만 되고 갱신되지 않는다. 쓰기는 전부
 * native(INSERT ON CONFLICT·DELETE)라 리포지토리 앵커 겸 조회 매핑만 담당한다 (Notification 선례).
 */
@Entity
@Table(name = "notification_opt_outs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationOptOut {

	@EmbeddedId
	private NotificationOptOutId id;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;
}
