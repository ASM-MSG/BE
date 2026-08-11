package com.msg.fillmap.friend.entity;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 친구 관계 (friendships — V1 기존 테이블의 첫 매핑, MSG-185). 불변식: **행 존재 = 활성 관계**(§D3) —
 * 거절·친구 삭제는 행 DELETE 라 REJECTED 가 영속되지 않고, ACCEPTED 행 1개가 양방향 관계를 표현한다.
 * 동일 쌍은 방향 무관 최대 1행 — 애플리케이션의 양방향 조회(findPair)가 강제한다.
 */
@Entity
@Table(name = "friendships")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Friendship {

	@EmbeddedId
	private FriendshipId id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private FriendshipStatus status;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "responded_at")
	private LocalDateTime respondedAt;

	private Friendship(Long requesterId, Long addresseeId) {
		this.id = new FriendshipId(requesterId, addresseeId);
		this.status = FriendshipStatus.PENDING;
	}

	/** 친구 요청 생성 — PENDING (FR-4). */
	public static Friendship request(Long requesterId, Long addresseeId) {
		return new Friendship(requesterId, addresseeId);
	}

	/** 수락 — ACCEPTED 승격 + 응답 시각 기록 (FR-10). 거절은 행 DELETE 라 전이 메서드가 없다 (§D3). */
	public void accept() {
		this.status = FriendshipStatus.ACCEPTED;
		this.respondedAt = LocalDateTime.now(ZoneOffset.UTC);
	}

	public Long getRequesterId() {
		return id.getRequesterId();
	}

	public Long getAddresseeId() {
		return id.getAddresseeId();
	}
}
