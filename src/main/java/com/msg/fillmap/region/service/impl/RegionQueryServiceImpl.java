package com.msg.fillmap.region.service.impl;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.global.geo.KoreaCoordinates;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.region.exception.RegionErrorCode;
import com.msg.fillmap.region.repository.RegionMentionProjection;
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

	private final RegionRepository regionRepository;

	@Override
	public Optional<RegionView> resolveByPoint(double lat, double lon) {
		validateCoordinate(lat, lon);
		return regionRepository.findContainingRegion(lat, lon).map(RegionQueryServiceImpl::toView);
	}

	@Override
	public Optional<RegionView> resolveNearestByPoint(double lat, double lon) {
		validateCoordinate(lat, lon);
		return regionRepository.findNearestRegion(lat, lon).map(RegionQueryServiceImpl::toView);
	}

	@Override
	public Optional<RegionView> resolveNearestByPoint(double lat, double lon, double maxDistanceMeters) {
		validateCoordinate(lat, lon);
		return regionRepository.findNearestRegionWithin(lat, lon, maxDistanceMeters)
			.map(RegionQueryServiceImpl::toView);
	}

	@Override
	public List<MentionedRegionMatch> matchMentionedRegions(String name, ViewportBounds viewport) {
		return regionRepository.matchMentionedRegions(
				name, viewport.swLat(), viewport.swLng(), viewport.neLat(), viewport.neLng())
			.stream()
			.map(RegionQueryServiceImpl::toMatch)
			.toList();
	}

	private void validateCoordinate(double lat, double lon) {
		if (KoreaCoordinates.isOutOfService(lat, lon)) {
			throw new ApiException(RegionErrorCode.INVALID_COORDINATE);
		}
	}

	private static RegionView toView(RegionProjection projection) {
		return new RegionView(projection.getRegionCode(), projection.getRegionName(), projection.getParentCode());
	}

	private static MentionedRegionMatch toMatch(RegionMentionProjection projection) {
		return new MentionedRegionMatch(
			projection.getName(), projection.getCenterLat(), projection.getCenterLng(),
			projection.getMinLat(), projection.getMinLng(), projection.getMaxLat(), projection.getMaxLng(),
			projection.getOverlapsViewport());
	}
}
