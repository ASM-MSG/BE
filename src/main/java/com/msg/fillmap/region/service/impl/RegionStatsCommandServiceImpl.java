package com.msg.fillmap.region.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.region.repository.RegionRepository;
import com.msg.fillmap.region.service.RegionStatsCommandService;

/**
 * 수집률 캐시 갱신 구현 (MSG-155). 호출자(Owner B)의 점령/롤백 @Transactional 에 참여해(REQUIRED)
 * region_stats recompute UPSERT 를 같은 트랜잭션에서 커밋한다(§D1). 판정·집계는 전부 native SQL 안에서
 * 이뤄지므로 이 구현은 리포지토리 위임 한 줄 — 호출자는 region_code·geospatial 을 모른다(§D3).
 */
@Service
@RequiredArgsConstructor
public class RegionStatsCommandServiceImpl implements RegionStatsCommandService {

	private final RegionRepository regionRepository;

	@Override
	@Transactional
	public void refresh(long userId, String gridId) {
		// 같은 사용자의 동시 점령/롤백 recompute 를 직렬화 — lost update 방지 (트랜잭션 종료 시 자동 해제)
		regionRepository.acquireUserRegionStatsLock(userId);
		regionRepository.refreshRegionStats(userId, gridId);
	}
}
