package com.msg.fillmap.friend.service;

/**
 * 친구 관계 판정 전용 read 계약 (MSG-312). friendships 테이블만 읽는 leaf 로, 다른 서비스를 하나도
 * 참조하지 않는다 — friend·video 양쪽이 이것을 주입해 두 서비스가 서로를 안 보게 만드는 것이 존재 이유다.
 *
 * <p>이전에는 판정이 {@code FriendService.isFriend} 에 있었고, video 가 재생 판정(MSG-285)에 그것을 쓰는
 * 한편 friend 는 친구 격자 영상 목록(MSG-187)을 video 에 위임해 두 서비스가 서로를 생성자 주입하는
 * 순환이 생겼다. 스프링이 기동에서 BeanCurrentlyInCreationException 을 던져 friend 쪽을 ObjectProvider
 * 지연 조회로 우회해 뒀는데, 지연 조회는 기동 시 순환 검출을 무력화해 다음 순환은 런타임에야 드러난다.
 * 판정을 leaf 로 내려 그 우회를 걷어냈다.
 */
public interface FriendshipQueryService {

	/**
	 * 두 사용자가 ACCEPTED 친구인가 — 방향 무관 대칭 판정 (MSG-285 FR-4·6). 요청 시점 실시간 조회라
	 * 친구 삭제가 다음 요청부터 즉시 반영된다(캐시·비정규화 없음). PENDING 은 친구가 아니다.
	 */
	boolean isFriend(Long userId, Long otherUserId);
}
