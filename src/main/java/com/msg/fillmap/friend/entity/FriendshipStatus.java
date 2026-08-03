package com.msg.fillmap.friend.entity;

/**
 * 친구 관계 상태 — PENDING(요청 대기)·ACCEPTED(친구)만 영속된다 (MSG-185 §D3).
 * DB CHECK(chk_friendships_status)는 REJECTED·BLOCKED 도 허용하지만, 거절은 행 DELETE 라
 * 저장 지점이 없고 차단은 후속 티켓에서 필요 시 상수를 추가한다 — 넓은 CHECK 에 좁은 enum 은 무위반.
 */
public enum FriendshipStatus {
	PENDING,
	ACCEPTED
}
