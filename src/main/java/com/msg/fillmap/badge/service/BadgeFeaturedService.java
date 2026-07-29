package com.msg.fillmap.badge.service;

import java.util.List;

import com.msg.fillmap.badge.dto.FeaturedBadgeResponseDto;

/**
 * 대표 뱃지 집합 교체 (MSG-239 §D6 — FR-8). 선택/해제/순서 변경이 교체(replace-set) 하나로 끝난다.
 */
public interface BadgeFeaturedService {

	/**
	 * 대표 뱃지 집합을 badgeIds 로 교체한다 — 배열 순서 = 표시 순서(rank 1·2), 빈 배열 = 전부 해제.
	 * 중복 badgeId 는 7400, 미획득(미존재 포함) 뱃지는 7403 으로 전체 롤백(부분 적용 없음).
	 *
	 * @return 적용된 대표 뱃지 (rank 순)
	 */
	List<FeaturedBadgeResponseDto> replaceFeatured(long userId, List<Long> badgeIds);
}
