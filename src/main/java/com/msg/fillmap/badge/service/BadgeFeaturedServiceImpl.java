package com.msg.fillmap.badge.service;

import java.util.HashSet;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.badge.dto.FeaturedBadgeResponseDto;
import com.msg.fillmap.badge.exception.BadgeErrorCode;
import com.msg.fillmap.badge.repository.UserBadgeRepository;
import com.msg.fillmap.global.exception.ApiException;

/**
 * 전 해제 → 요청 순서대로 rank 지정 (MSG-239 §D6). 상한 2 는 V9 partial UNIQUE 가 물리 강제하므로
 * 앱 코드 카운트 검사가 없고(DB 제약 > 앱 코드), 동시 교체는 advisory lock 으로 사용자 단위 직렬화한다.
 */
@Service
@RequiredArgsConstructor
public class BadgeFeaturedServiceImpl implements BadgeFeaturedService {

	private final UserBadgeRepository userBadgeRepository;

	@Override
	@Transactional
	public List<FeaturedBadgeResponseDto> replaceFeatured(long userId, List<Long> badgeIds) {
		if (new HashSet<>(badgeIds).size() != badgeIds.size()) {
			throw new ApiException(BadgeErrorCode.INVALID_FEATURED_BADGES);
		}
		userBadgeRepository.acquireFeaturedLock(userId);
		userBadgeRepository.clearFeatured(userId);
		for (int rank = 1; rank <= badgeIds.size(); rank++) {
			if (userBadgeRepository.setFeaturedRank(userId, badgeIds.get(rank - 1), rank) == 0) {
				// 획득한 적 없는(또는 존재하지 않는) 뱃지 — 예외로 트랜잭션 전체 롤백, 부분 적용 없음.
				throw new ApiException(BadgeErrorCode.BADGE_NOT_EARNED);
			}
		}
		return userBadgeRepository.findFeatured(userId).stream()
			.map(FeaturedBadgeResponseDto::from)
			.toList();
	}
}
