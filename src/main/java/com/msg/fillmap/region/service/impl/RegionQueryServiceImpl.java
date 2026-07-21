package com.msg.fillmap.region.service.impl;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.region.exception.RegionErrorCode;
import com.msg.fillmap.region.repository.RegionProjection;
import com.msg.fillmap.region.repository.RegionRepository;
import com.msg.fillmap.region.service.RegionQueryService;
import com.msg.fillmap.region.service.RegionView;

/**
 * 역지오코딩 조회 구현 (MSG-93). 좌표 plausibility 검증(MSG-66 D7 정책 재사용) 후 단건 ST_Covers 조회를 돌린다.
 * no-match 는 예외가 아니라 Optional.empty(§D3), 서비스범위 밖 좌표만 INVALID_COORDINATE(6400).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RegionQueryServiceImpl implements RegionQueryService {

	// 서비스 좌표 범위(한국 대략) — 이 밖이면 INVALID_COORDINATE. lat/lon 유효범위(±90/±180)를 포괄한다.
	private static final double MIN_LAT = 33.0;
	private static final double MAX_LAT = 39.0;
	private static final double MIN_LON = 124.0;
	private static final double MAX_LON = 132.0;

	private final RegionRepository regionRepository;

	@Override
	public Optional<RegionView> resolveByPoint(double lat, double lon) {
		validateCoordinate(lat, lon);
		return regionRepository.findContainingRegion(lat, lon).map(RegionQueryServiceImpl::toView);
	}

	private void validateCoordinate(double lat, double lon) {
		if (Double.isNaN(lat) || Double.isNaN(lon)
			|| lat < MIN_LAT || lat > MAX_LAT || lon < MIN_LON || lon > MAX_LON) {
			throw new ApiException(RegionErrorCode.INVALID_COORDINATE);
		}
	}

	private static RegionView toView(RegionProjection projection) {
		return new RegionView(projection.getRegionCode(), projection.getRegionName(), projection.getParentCode());
	}
}
