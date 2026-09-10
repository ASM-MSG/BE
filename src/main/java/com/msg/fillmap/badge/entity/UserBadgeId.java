package com.msg.fillmap.badge.entity;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * user_badges 복합 기본키 (user_id, badge_id) — UserGridId 패턴. 이 PK 가 중복 지급(FR-3)의
 * 최후 방어선이다(지급은 ON CONFLICT DO NOTHING — MSG-239 §D5).
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserBadgeId implements Serializable {

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "badge_id", nullable = false)
	private Long badgeId;

	public UserBadgeId(Long userId, Long badgeId) {
		this.userId = userId;
		this.badgeId = badgeId;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof UserBadgeId that)) {
			return false;
		}
		return Objects.equals(userId, that.userId) && Objects.equals(badgeId, that.badgeId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(userId, badgeId);
	}
}
