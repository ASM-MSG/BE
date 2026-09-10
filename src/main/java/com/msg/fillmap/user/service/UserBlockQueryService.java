package com.msg.fillmap.user.service;

/**
 * 차단 판정 계약 (MSG-569 D-5) — video·event 가 단건 경로(재생·행사 상세·행사 상호작용 서두)에서 주입한다.
 * 목록 쿼리는 SQL 안 NOT EXISTS 로 걸러지므로(D-4) 이 인터페이스는 단건 판정에만 쓴다.
 * UserBlockService 가 이것을 구현하지 않는 이유: UserBlockService → FriendService → VideoService →
 * 이 빈으로 되돌아오는 순환이 생겨서다(FriendshipQueryService 를 leaf 로 뗀 MSG-312 와 같은 상황).
 */
public interface UserBlockQueryService {

	/** 두 사용자 사이에 어느 방향이든 차단 행이 있으면 true. 캐시 없이 요청 시점 실시간 판정이다(AC-13). */
	boolean isBlockedEitherWay(Long userId, Long otherUserId);
}
