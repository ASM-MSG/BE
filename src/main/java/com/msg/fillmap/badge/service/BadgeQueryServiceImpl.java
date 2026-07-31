package com.msg.fillmap.badge.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.badge.dto.MyBadgeResponseDto;
import com.msg.fillmap.badge.repository.BadgeRepository;
import com.msg.fillmap.badge.repository.MyBadgeProjection;
import com.msg.fillmap.badge.repository.UserBadgeRepository;

/**
 * 목록 SELECT → 미확인 스탬프 → 매핑 (docs/MSG-201.md §D3). 스탬프 UPDATE 가 있어 readOnly 가 아니다 —
 * 확인 처리가 별도 PATCH 로 옮겨지면(기획 확정 시) 이 조회는 readOnly 가 된다. 같은 사용자의 동시 조회
 * 2건이 둘 다 isNew true 를 볼 수 있으나 NEW 표시가 두 번 보일 뿐이라 원자화하지 않는다(§D3 비고).
 */
@Service
@RequiredArgsConstructor
public class BadgeQueryServiceImpl implements BadgeQueryService {

	private final BadgeRepository badgeRepository;
	private final UserBadgeRepository userBadgeRepository;

	@Override
	@Transactional
	public List<MyBadgeResponseDto> findMyBadges(long userId) {
		List<MyBadgeProjection> rows = badgeRepository.findAllWithMyStatus(userId);
		List<Long> newBadgeIds = rows.stream()
			.filter(row -> Boolean.TRUE.equals(row.getIsNew()))
			.map(MyBadgeProjection::getBadgeId)
			.toList();
		if (!newBadgeIds.isEmpty()) {
			// 이번 응답에 실제로 실린 미확인 행만 스탬프 — user 전체 UPDATE 금지 사유는 리포지토리 주석 참조.
			userBadgeRepository.markMyBadgesNotified(userId, newBadgeIds);
		}
		return rows.stream().map(MyBadgeResponseDto::from).toList();
	}
}
