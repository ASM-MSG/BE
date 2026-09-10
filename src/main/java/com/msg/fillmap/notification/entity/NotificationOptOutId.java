package com.msg.fillmap.notification.entity;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * notification_opt_outs 복합 기본키 (user_id, category) — 거부의 정체성은 사용자·카테고리 쌍이다 (MSG-180 D2).
 * isEnabled 가 이 키로 existsById 단건 히트한다 (FriendshipId·UserGridId 동형).
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationOptOutId implements Serializable {

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private NotificationCategory category;

	public NotificationOptOutId(Long userId, NotificationCategory category) {
		this.userId = userId;
		this.category = category;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof NotificationOptOutId that)) {
			return false;
		}
		return Objects.equals(userId, that.userId) && category == that.category;
	}

	@Override
	public int hashCode() {
		return Objects.hash(userId, category);
	}
}
