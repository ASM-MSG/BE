package com.msg.fillmap.region.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.region.exception.RegionErrorCode;
import com.msg.fillmap.region.repository.RegionRepository;
import com.msg.fillmap.region.repository.RegionStatProjection;
import com.msg.fillmap.region.service.RegionStatView;
import com.msg.fillmap.region.service.RegionStatsQueryService;

/**
 * 행정동별 수집률 조회 구현 (MSG-156 §도메인 로직). parentCode 실존 검증(§D3) 후 순수 SELECT 로 region_stats 를 읽는다 —
 * geospatial 0. no-data(유효 parentCode·수집 0)는 예외가 아니라 빈 리스트, 실존하지 않는 parentCode 만 REGION_NOT_FOUND(6404).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RegionStatsQueryServiceImpl implements RegionStatsQueryService {

	private final RegionRepository regionRepository;

	@Override
	public List<RegionStatView> findStats(long userId, String parentCode, boolean collectedOnly) {
		if (parentCode != null && !regionRepository.existsByParentCode(parentCode)) {
			throw new ApiException(RegionErrorCode.REGION_NOT_FOUND);
		}
		return regionRepository.findStats(userId, parentCode, collectedOnly).stream()
			.map(RegionStatsQueryServiceImpl::toView)
			.toList();
	}

	private static RegionStatView toView(RegionStatProjection p) {
		return new RegionStatView(
			p.getRegionCode(), p.getRegionName(), p.getParentCode(),
			p.getCollectedCount(), p.getTotalCount(), p.getProgressRate(), p.getUpdatedAt());
	}
}
