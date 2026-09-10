package com.msg.fillmap.user.entity;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * user_blocks 복합 기본키 (blocker_id, blocked_id) — 차단은 방향이 있는 관계라 A→B 와 B→A 가 별개 행이다
 * (MSG-569 D-1). 같은 방향 재차단이 이 PK 에 걸려 ON CONFLICT DO NOTHING 으로 흡수된다.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserBlockId implements Serializable {

	@Column(name = "blocker_id", nullable = false)
	private Long blockerId;

	@Column(name = "blocked_id", nullable = false)
	private Long blockedId;

	public UserBlockId(Long blockerId, Long blockedId) {
		this.blockerId = blockerId;
		this.blockedId = blockedId;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof UserBlockId that)) {
			return false;
		}
		return Objects.equals(blockerId, that.blockerId) && Objects.equals(blockedId, that.blockedId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(blockerId, blockedId);
	}
}
