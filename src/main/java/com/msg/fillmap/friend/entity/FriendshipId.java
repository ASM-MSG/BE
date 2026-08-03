package com.msg.fillmap.friend.entity;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * friendships 복합 기본키 (requester_id, addressee_id) — 관계의 정체성은 방향 있는 유저 쌍이다 (V1).
 * 수락/거절이 (requesterId, 나) 로 조회하므로 이 키 자체가 수신자 본인 검증을 강제한다 (MSG-185 FR-13).
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FriendshipId implements Serializable {

	@Column(name = "requester_id", nullable = false)
	private Long requesterId;

	@Column(name = "addressee_id", nullable = false)
	private Long addresseeId;

	public FriendshipId(Long requesterId, Long addresseeId) {
		this.requesterId = requesterId;
		this.addresseeId = addresseeId;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof FriendshipId that)) {
			return false;
		}
		return Objects.equals(requesterId, that.requesterId) && Objects.equals(addresseeId, that.addresseeId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(requesterId, addresseeId);
	}
}
