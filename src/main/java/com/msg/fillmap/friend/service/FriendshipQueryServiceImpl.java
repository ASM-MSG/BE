package com.msg.fillmap.friend.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.friend.repository.FriendshipRepository;

/**
 * 친구 판정 leaf 구현 (MSG-312). 의존은 리포지토리 하나뿐이라 어떤 서비스 순환에도 낄 수 없다 —
 * 이 클래스에 서비스 의존을 추가하면 leaf 성질이 깨지고 순환이 되돌아온다.
 */
@Service
@RequiredArgsConstructor
public class FriendshipQueryServiceImpl implements FriendshipQueryService {

	private final FriendshipRepository friendshipRepository;

	/**
	 * 무잠금 존재 확인 — findPair 는 PESSIMISTIC_WRITE 라 readOnly 트랜잭션에서 PostgreSQL 이 거부한다.
	 * 호출자(재생 판정·친구 열람 가드)가 이미 트랜잭션 안이면 그 트랜잭션에 참여한다.
	 */
	@Override
	@Transactional(readOnly = true)
	public boolean isFriend(Long userId, Long otherUserId) {
		return friendshipRepository.existsAcceptedPair(userId, otherUserId);
	}
}
