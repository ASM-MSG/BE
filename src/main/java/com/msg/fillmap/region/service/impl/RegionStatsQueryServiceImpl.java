package com.msg.fillmap.region.service.impl;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.global.geo.KoreaCoordinates;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.region.exception.RegionErrorCode;
import com.msg.fillmap.region.repository.RegionRepository;
import com.msg.fillmap.region.repository.RegionStatProjection;
import com.msg.fillmap.region.service.RegionQueryService;
import com.msg.fillmap.region.service.RegionStatView;
import com.msg.fillmap.region.service.RegionStatsQueryService;

/**
 * 행정동별 수집률 조회 구현 (MSG-156 리스트 + MSG-153 §도메인 로직 단건). findStats 는 parentCode 실존 검증(§D3) 후
 * 순수 SELECT 로 region_stats 를 읽는다. 단건(by-point/by-grid)은 같은 region 패키지의 resolveByPoint 로 행정동을 판정한 뒤
 * findStatByRegion 으로 그 행정동의 수집률을 낸다(수집 없으면 0% 합성 — §D6). geospatial 은 resolveByPoint 안의 단건 1회뿐.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RegionStatsQueryServiceImpl implements RegionStatsQueryService {

	private final RegionRepository regionRepository;
	private final RegionQueryService regionQueryService;

	@Override
	public List<RegionStatView> findStats(long userId, String parentCode, boolean collectedOnly) {
		if (parentCode != null && !regionRepository.existsByParentCode(parentCode)) {
			throw new ApiException(RegionErrorCode.REGION_NOT_FOUND);
		}
		return regionRepository.findStats(userId, parentCode, collectedOnly).stream()
			.map(RegionStatsQueryServiceImpl::toView)
			.toList();
	}

	@Override
	public Optional<RegionStatView> findStatByPoint(long userId, double lat, double lon) {
		// by-point 는 사용자 좌표라 서비스 범위 밖이면 resolveByPoint 가 6400 을 던진다(§API ②).
		return resolveStat(userId, lat, lon);
	}

	@Override
	public Optional<RegionStatView> findStatByGrid(long userId, String gridId) {
		GridPoint center = decodeCenter(gridId);
		// by-grid 는 에러 없이 200 + null 만 낸다(§API ③) — 이상한/범위 밖 gridId 는 무귀속으로 흡수해 6400 을 내지 않는다.
		if (center == null || KoreaCoordinates.isOutOfService(center.lat(), center.lon())) {
			return Optional.empty();
		}
		return resolveStat(userId, center.lat(), center.lon());
	}

	private Optional<RegionStatView> resolveStat(long userId, double lat, double lon) {
		return regionQueryService.resolveByPoint(lat, lon)
			.flatMap(region -> regionRepository.findStatByRegion(userId, region.regionCode()))
			.map(RegionStatsQueryServiceImpl::toView);
	}

	/** gridId → 격자 중심점. 형식이 이상해 디코드가 실패하면 null(§D6 — by-grid 는 200 + null). */
	private static GridPoint decodeCenter(String gridId) {
		if (gridId == null || gridId.isBlank()) {
			return null;
		}
		try {
			return GridEncoder.center(gridId);
		} catch (IllegalArgumentException | IndexOutOfBoundsException e) {
			return null;
		}
	}

	private static RegionStatView toView(RegionStatProjection p) {
		return new RegionStatView(
			p.getRegionCode(), p.getRegionName(), p.getParentCode(),
			p.getCollectedCount(), p.getTotalCount(), p.getProgressRate(), p.getUpdatedAt());
	}
}
