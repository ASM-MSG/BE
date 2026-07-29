package com.msg.fillmap.badge.service;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.badge.dto.EarnedBadgeResponseDto;
import com.msg.fillmap.badge.entity.BadgeConditionType;
import com.msg.fillmap.badge.repository.BadgeRepository;
import com.msg.fillmap.badge.repository.EligibleBadgeProjection;
import com.msg.fillmap.badge.repository.UserBadgeRepository;

/**
 * 뱃지 판정·지급 엔진 (MSG-239). "조건을 충족했는데 아직 못 받은 뱃지"를 조회한 뒤 지급 INSERT 하는
 * 2단 구조 — 대부분의 호출은 1단에서 빈 결과로 끝나 비용이 조회 하나다. 동시 요청 경합으로 상대
 * 트랜잭션이 같은 뱃지를 먼저 지급하는 극단 케이스는 응답에 한 번 더 실릴 수 있을 뿐이고(중복 row 는
 * user_badges PK 가 차단) 무해해서 수용한다. 상세 결정: docs/MSG-239.md §D5.
 */
@Service
@RequiredArgsConstructor
public class BadgeAwardServiceImpl implements BadgeAwardService {

	private final BadgeRepository badgeRepository;
	private final UserBadgeRepository userBadgeRepository;

	@Override
	@Transactional
	public List<EarnedBadgeResponseDto> award(long userId, BadgeConditionType type, BigDecimal metric) {
		List<EligibleBadgeProjection> candidates = badgeRepository.findEligible(type.name(), metric, userId);
		if (candidates.isEmpty()) {
			return List.of();
		}
		for (EligibleBadgeProjection candidate : candidates) {
			userBadgeRepository.insertIgnoreConflict(userId, candidate.getBadgeId());
		}
		return candidates.stream().map(EarnedBadgeResponseDto::from).toList();
	}
}
