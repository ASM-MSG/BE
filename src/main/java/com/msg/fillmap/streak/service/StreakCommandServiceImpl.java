package com.msg.fillmap.streak.service;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.badge.dto.EarnedBadgeResponseDto;
import com.msg.fillmap.badge.entity.BadgeConditionType;
import com.msg.fillmap.badge.service.BadgeAwardService;
import com.msg.fillmap.streak.repository.StreakRepository;

/**
 * 스트릭 집계 구현 (MSG-200 §D7). UPSERT 한 문장 → current_count 읽기 → award 배선 순.
 * 같은 날 no-op 이어도 award 는 호출한다 — 이미 보유한 티어는 엔진의 미보유 필터(239 §D5)가
 * 0건으로 끝내므로 비용은 인덱스드 SELECT 1회, 분기 추가가 오히려 과하다(§도메인 로직 2).
 */
@Service
@RequiredArgsConstructor
public class StreakCommandServiceImpl implements StreakCommandService {

	private final StreakRepository streakRepository;
	private final BadgeAwardService badgeAwardService;

	@Override
	@Transactional
	public List<EarnedBadgeResponseDto> recordUpload(long userId) {
		streakRepository.upsertOnUpload(userId);
		int current = streakRepository.findCurrentCount(userId);
		return badgeAwardService.award(userId, BadgeConditionType.STREAK_DAYS, BigDecimal.valueOf(current));
	}
}
