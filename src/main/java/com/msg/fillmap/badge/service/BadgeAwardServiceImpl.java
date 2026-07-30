package com.msg.fillmap.badge.service;

import java.math.BigDecimal;
import java.util.ArrayList;
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
 * 2단 구조 — 대부분의 호출은 1단에서 빈 결과로 끝나 비용이 조회 하나다. 반환 리스트에는 INSERT 가
 * 실제로 성공한(영향 행 1) 뱃지만 담는다: 동시 요청 경합에서 상대 트랜잭션이 같은 뱃지를 먼저
 * 지급했다면 내 INSERT 는 ON CONFLICT 로 0 을 받고, 그 뱃지는 "이 요청으로 새로 획득한" 게 아니므로
 * 응답에서 제외한다. PostgreSQL 은 conflict 판정 전에 선행 트랜잭션 커밋을 기다리므로 이 필터는
 * 경합에서도 안전하다. 상세 결정: docs/MSG-239.md §D5 (+ Codex 교차 리뷰로 성공분만 반환으로 강화).
 */
@Service
@RequiredArgsConstructor
public class BadgeAwardServiceImpl implements BadgeAwardService {

	private final BadgeRepository badgeRepository;
	private final UserBadgeRepository userBadgeRepository;

	@Override
	@Transactional
	public List<EarnedBadgeResponseDto> awardUploadBadges(long userId) {
		return award(userId, BadgeConditionType.UPLOAD_COUNT, userBadgeRepository.countMyVideos(userId));
	}

	@Override
	@Transactional
	public List<EarnedBadgeResponseDto> awardCollectionBadges(long userId, String gridId) {
		List<EarnedBadgeResponseDto> earned = new ArrayList<>(
			award(userId, BadgeConditionType.TOTAL_GRIDS, userBadgeRepository.countMyGrids(userId)));
		// 행정동 없는 격자(바다 등)는 region_stats 에 저장 행이 없어 empty → 수집률 판정 자체를 건너뛴다.
		userBadgeRepository.findMyRegionProgress(userId, gridId)
			.ifPresent(rate -> earned.addAll(award(userId, BadgeConditionType.REGION_PERCENT, rate)));
		return earned;
	}

	@Override
	@Transactional
	public List<EarnedBadgeResponseDto> award(long userId, BadgeConditionType type, BigDecimal metric) {
		List<EligibleBadgeProjection> candidates = badgeRepository.findEligible(type.name(), metric, userId);
		if (candidates.isEmpty()) {
			return List.of();
		}
		List<EarnedBadgeResponseDto> earned = new ArrayList<>(candidates.size());
		for (EligibleBadgeProjection candidate : candidates) {
			if (userBadgeRepository.insertIgnoreConflict(userId, candidate.getBadgeId()) == 1) {
				earned.add(EarnedBadgeResponseDto.from(candidate));
			}
		}
		return earned;
	}
}
