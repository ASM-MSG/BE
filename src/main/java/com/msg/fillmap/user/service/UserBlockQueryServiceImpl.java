package com.msg.fillmap.user.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.user.repository.UserBlockRepository;

/**
 * 차단 판정 leaf 구현 (MSG-569 D-5). 의존은 리포지토리 하나뿐이라 어떤 서비스 순환에도 낄 수 없다 —
 * 이 클래스에 서비스 의존을 추가하면 leaf 성질이 깨지고 순환이 되돌아온다(FriendshipQueryServiceImpl 동형).
 */
@Service
@RequiredArgsConstructor
public class UserBlockQueryServiceImpl implements UserBlockQueryService {

	private final UserBlockRepository userBlockRepository;

	@Override
	@Transactional(readOnly = true)
	public boolean isBlockedEitherWay(Long userId, Long otherUserId) {
		return userBlockRepository.existsEitherWay(userId, otherUserId);
	}
}
