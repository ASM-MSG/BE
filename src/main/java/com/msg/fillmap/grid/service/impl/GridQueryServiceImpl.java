package com.msg.fillmap.grid.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.global.geo.KoreaCoordinates;
import com.msg.fillmap.grid.GridCursor;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.grid.GridEncoder.GridRange;
import com.msg.fillmap.grid.dto.RegionUnit;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.grid.exception.GridErrorCode;
import com.msg.fillmap.grid.repository.GridRepository;
import com.msg.fillmap.grid.repository.OccupiedGridProjection;
import com.msg.fillmap.grid.service.GridCellView;
import com.msg.fillmap.grid.service.GridQueryService;
import com.msg.fillmap.grid.service.OccupiedGridPage;
import com.msg.fillmap.grid.service.OccupiedGridView;
import com.msg.fillmap.grid.service.RegionAggregateView;
import com.msg.fillmap.region.service.RegionQueryService;
import com.msg.fillmap.region.service.RegionView;
import com.msg.fillmap.zone.service.ZoneCellName;
import com.msg.fillmap.zone.service.ZoneNameQueryService;
import com.msg.fillmap.zone.service.ZoneNameResolver;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GridQueryServiceImpl implements GridQueryService {

	// viewport 한 변의 최대 위경도 span(도). 초과 시 과도한 스캔으로 보고 VIEWPORT_TOO_LARGE.
	// MSG-134 확정: bbox 가 줌을 담고 span 상한 0.5° 유지(level 파라미터 없음).
	private static final double MAX_VIEWPORT_SPAN_DEG = 0.5;

	// 페이지 size 상한 (MSG-90 Q2). 기본값 1000 은 컨트롤러 defaultValue 소관.
	private static final int MAX_PAGE_SIZE = 5000;

	// WGS84 좌표 유효 범위 — 서비스 범위(KoreaCoordinates)가 아니라 좌표계 자체의 정의역이다.
	private static final double MIN_LATITUDE_DEG = -90.0;
	private static final double MAX_LATITUDE_DEG = 90.0;
	private static final double MIN_LONGITUDE_DEG = -180.0;
	private static final double MAX_LONGITUDE_DEG = 180.0;

	private final GridRepository gridRepository;
	private final ZoneNameQueryService zoneNameQueryService;
	private final RegionQueryService regionQueryService;

	@Override
	public GridCellView getCell(long userId, String gridId) {
		GridIndex index = validateGridId(gridId);
		// 격자는 논리 개념이라 grids row·점령 여부와 무관하게 이름이 계산된다 (MSG-341 FR-4)
		ZoneCellName name = zoneNameQueryService.resolver().name(index.gridY(), index.gridX());
		String regionName = resolveRegionName(gridId);
		Integer videoCount = gridRepository.findVideoCount(userId, gridId).orElse(null);
		if (videoCount == null) {
			return new GridCellView(gridId, false, 0, name.zoneName(), name.zoneCell(), regionName);
		}
		return new GridCellView(gridId, true, videoCount, name.zoneName(), name.zoneCell(), regionName);
	}

	/**
	 * 격자 중심점을 다시 판정해 행정동 이름을 얻는다 (MSG-349). 미점령 격자는 grids row 자체가 없을 수 있어
	 * 저장 라벨(grids.region_code)을 읽을 수 없는 유일한 경로다 — 판정 술어가 저장 라벨과 같아
	 * (ST_Covers 후 region_code 순 단일화) 두 출처의 답이 갈리지 않는다.
	 * 서비스 범위(한국) 밖 좌표면 resolveByPoint 가 INVALID_COORDINATE(6400)를 던지므로 호출하지 않고
	 * null 로 둔다 — getCell 은 형식만 맞으면 200 을 주는 API 라, 이 변경으로 새 에러가 새면 안 된다
	 * (RegionStatsQueryServiceImpl.findStatByGrid 와 같은 가드).
	 */
	private String resolveRegionName(String gridId) {
		GridPoint center = GridEncoder.center(gridId);
		if (KoreaCoordinates.isOutOfService(center.lat(), center.lon())) {
			return null;
		}
		return regionQueryService.resolveByPoint(center.lat(), center.lon())
			.map(RegionView::regionName)
			.orElse(null);
	}

	@Override
	public List<OccupiedGridView> getOccupiedInViewport(long userId, ViewportBounds bounds) {
		validateBounds(bounds);
		return toViews(queryByRange(userId, bounds));
	}

	@Override
	public OccupiedGridPage getOccupiedInViewport(long userId, ViewportBounds bounds, String cursor, int size) {
		validateBounds(bounds);
		if (size < 1 || size > MAX_PAGE_SIZE) {
			throw new ApiException(GridErrorCode.INVALID_PAGE_SIZE);
		}
		// lookahead(size + 1)로 다음 페이지 존재를 판정 — 빈 마지막 페이지를 만들지 않는다.
		List<OccupiedGridProjection> rows = queryPage(userId, bounds, cursor, size + 1);
		boolean hasNext = rows.size() > size;
		List<OccupiedGridProjection> pageRows = hasNext ? rows.subList(0, size) : rows;
		String nextCursor = null;
		if (hasNext) {
			OccupiedGridProjection last = pageRows.get(pageRows.size() - 1);
			nextCursor = GridCursor.encode(last.getGridY(), last.getGridX());
		}
		return new OccupiedGridPage(toViews(pageRows), nextCursor);
	}

	@Override
	public List<RegionAggregateView> getOccupiedAggregatesInViewport(
		long userId, ViewportBounds bounds, RegionUnit unit) {
		validateBounds(bounds, unit.getMaxSpanDeg());
		// 개별 조회(queryByRange)와 같은 환산·같은 술어를 써야 총합이 어긋나지 않는다 (MSG-356 FR-3).
		GridRange range = GridEncoder.viewportRange(bounds);
		return gridRepository.aggregateOccupiedInRange(
				userId, range.minGridY(), range.maxGridY(), range.minGridX(), range.maxGridX(),
				unit.getCodePrefixLength(), unit.getNameTokenIndex())
			.stream()
			.map(p -> new RegionAggregateView(
				p.getRegionCode(), p.getName(), p.getLat(), p.getLng(), p.getCount().intValue()))
			.toList();
	}

	private List<OccupiedGridProjection> queryPage(long userId, ViewportBounds bounds, String cursor, int limit) {
		GridRange range = GridEncoder.viewportRange(bounds);
		if (cursor == null) {
			return gridRepository.findOccupiedPage(
				userId, range.minGridY(), range.maxGridY(), range.minGridX(), range.maxGridX(), limit);
		}
		GridCursor decoded = decodeCursor(cursor);
		return gridRepository.findOccupiedPageAfter(
			userId, range.minGridY(), range.maxGridY(), range.minGridX(), range.maxGridX(),
			decoded.gridY(), decoded.gridX(), limit);
	}

	private GridCursor decodeCursor(String cursor) {
		try {
			return GridCursor.decode(cursor);
		} catch (RuntimeException e) {
			throw new ApiException(GridErrorCode.INVALID_CURSOR, e);
		}
	}

	private List<OccupiedGridProjection> queryByRange(long userId, ViewportBounds bounds) {
		// bbox 꼭짓점 4점을 GridEncoder(단일 진실 원천)로 grid_y/grid_x 정수 인덱스 범위로 환산한다.
		GridRange range = GridEncoder.viewportRange(bounds);
		return gridRepository.findOccupiedInRange(
			userId, range.minGridY(), range.maxGridY(), range.minGridX(), range.maxGridX());
	}

	private List<OccupiedGridView> toViews(List<OccupiedGridProjection> rows) {
		// 리졸버는 항목 수와 무관하게 매핑 진입 전 1회만 받는다 — 항목당 zones 조회(N+1) 봉쇄 (MSG-341 FR-8)
		ZoneNameResolver resolver = zoneNameQueryService.resolver();
		return rows.stream()
			.map(p -> {
				ZoneCellName name = resolver.name(p.getGridY(), p.getGridX());
				// regionName 은 같은 쿼리의 조인 컬럼이다 — 항목당 추가 조회 없음 (MSG-349 비기능 성능)
				return new OccupiedGridView(p.getGridId(), p.getGridY(), p.getGridX(),
					name.zoneName(), name.zoneCell(), p.getRegionName());
			})
			.toList();
	}

	private GridIndex validateGridId(String gridId) {
		try {
			return GridEncoder.decode(gridId);
		} catch (RuntimeException e) {
			throw new ApiException(GridErrorCode.INVALID_GRID_ID, e);
		}
	}

	private void validateBounds(ViewportBounds bounds) {
		validateBounds(bounds, MAX_VIEWPORT_SPAN_DEG);
	}

	/**
	 * 뷰포트 공통 검증 — 개별 조회(0.5°)와 집계 조회(단위별 상한)가 같은 술어를 쓴다.
	 * 좌표 자체 검증이 맨 앞이다: NaN 은 어떤 비교도 false 라 뒤집힘·상한 검사를 그대로 통과하고(fail-open),
	 * 무한대는 격자 인덱스 환산에서 전 범위 스캔이 되며, 위도 100 처럼 유한해도 좌표계에 없는 값은
	 * 조용한 빈 결과가 아니라 명시 거절이어야 한다 (MSG-356 Codex 리뷰 반영). 1e308 급 유한값이
	 * Proj4J 경도 정규화(반복 감산)를 사실상 무한 루프로 만드는 문제도 같은 검사가 막는다 (MSG-347 fix 합류).
	 */
	private void validateBounds(ViewportBounds bounds, double maxSpanDeg) {
		if (!isValidCoordinates(bounds)) {
			throw new ApiException(GridErrorCode.INVALID_VIEWPORT);
		}
		if (bounds.swLat() > bounds.neLat() || bounds.swLng() > bounds.neLng()) {
			throw new ApiException(GridErrorCode.INVALID_VIEWPORT);
		}
		double latSpan = bounds.neLat() - bounds.swLat();
		double lngSpan = bounds.neLng() - bounds.swLng();
		if (latSpan > maxSpanDeg || lngSpan > maxSpanDeg) {
			throw new ApiException(GridErrorCode.VIEWPORT_TOO_LARGE);
		}
	}

	/**
	 * 네 좌표가 모두 WGS84 유효 범위(위도 ±90, 경도 ±180) 안인지. 범위 비교가 NaN·±무한대까지 함께 걸러낸다
	 * (NaN 은 두 비교가 모두 false, 무한대는 한쪽을 넘는다).
	 */
	private boolean isValidCoordinates(ViewportBounds bounds) {
		return isValidLat(bounds.swLat()) && isValidLat(bounds.neLat())
			&& isValidLng(bounds.swLng()) && isValidLng(bounds.neLng());
	}

	private boolean isValidLat(double lat) {
		return lat >= MIN_LATITUDE_DEG && lat <= MAX_LATITUDE_DEG;
	}

	private boolean isValidLng(double lng) {
		return lng >= MIN_LONGITUDE_DEG && lng <= MAX_LONGITUDE_DEG;
	}
}
