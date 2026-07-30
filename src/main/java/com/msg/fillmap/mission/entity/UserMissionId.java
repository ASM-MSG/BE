package com.msg.fillmap.mission.entity;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * user_missions 복합 기본키 (user_id, mission_id) — UserBadgeId 패턴 미러(MSG-223 §데이터 모델).
 * 이 PK 가 중복 발급(FR-14)의 최후 방어선이다(발급은 ON CONFLICT DO NOTHING — §D2 3단계).
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserMissionId implements Serializable {

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "mission_id", nullable = false)
	private Long missionId;

	public UserMissionId(Long userId, Long missionId) {
		this.userId = userId;
		this.missionId = missionId;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof UserMissionId that)) {
			return false;
		}
		return Objects.equals(userId, that.userId) && Objects.equals(missionId, that.missionId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(userId, missionId);
	}
}
