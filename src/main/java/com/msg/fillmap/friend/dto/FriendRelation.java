package com.msg.fillmap.friend.dto;

import com.msg.fillmap.friend.entity.Friendship;
import com.msg.fillmap.friend.entity.FriendshipStatus;

/**
 * 미리보기 응답의 관계 상태 5값 (MSG-391 D-3). 영속 상태 enum FriendshipStatus(2값)와 관심사가 다른
 * 응답 관점 값 집합이라 dto 에 둔다. 판정은 {@link #of} 한 곳 — preview 와 request 가 같이 소비해
 * 같은 관계 상태에서 두 API 가 다른 답을 내지 않는 것을 구조로 보장한다 (FR-10, D-2).
 */
public enum FriendRelation {

	SELF,
	NONE,
	OUTGOING_PENDING,
	INCOMING_PENDING,
	FRIENDS;

	/** 쌍 행(무잠금·잠금 무관)과 조회자 id 로 관계를 판정한다. SELF 는 호출 전에 가른다 (D-4). */
	public static FriendRelation of(Long viewerId, Friendship pairOrNull) {
		if (pairOrNull == null) {
			return NONE;
		}
		if (pairOrNull.getStatus() == FriendshipStatus.ACCEPTED) {
			return FRIENDS;
		}
		return pairOrNull.getRequesterId().equals(viewerId) ? OUTGOING_PENDING : INCOMING_PENDING;
	}
}
