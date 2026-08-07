package com.msg.fillmap.grid.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridCursor;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.grid.exception.GridErrorCode;
import com.msg.fillmap.grid.repository.GridRepository;
import com.msg.fillmap.grid.repository.OccupiedGridProjection;
import com.msg.fillmap.grid.service.GridCellView;
import com.msg.fillmap.grid.service.GridQueryService;
import com.msg.fillmap.grid.service.OccupiedGridPage;
import com.msg.fillmap.grid.service.OccupiedGridView;
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

	private final GridRepository gridRepository;
	private final ZoneNameQueryService zoneNameQueryService;

	@Override
	public GridCellView getCell(long userId, String gridId) {
		GridIndex index = validateGridId(gridId);
		// 격자는 논리 개념이라 grids row·점령 여부와 무관하게 이름이 계산된다 (MSG-341 FR-4)
		ZoneCellName name = zoneNameQueryService.resolver().name(index.gridY(), index.gridX());
		Integer videoCount = gridRepository.findVideoCount(userId, gridId).orElse(null);
		if (videoCount == null) {
			return new GridCellView(gridId, false, 0, name.zoneName(), name.zoneCell());
		}
		return new GridCellView(gridId, true, videoCount, name.zoneName(), name.zoneCell());
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

	private List<OccupiedGridProjection> queryPage(long userId, ViewportBounds bounds, String cursor, int limit) {
		GridIndex sw = GridEncoder.decode(GridEncoder.encode(bounds.swLat(), bounds.swLng()));
		GridIndex ne = GridEncoder.decode(GridEncoder.encode(bounds.neLat(), bounds.neLng()));
		if (cursor == null) {
			return gridRepository.findOccupiedPage(userId, sw.gridY(), ne.gridY(), sw.gridX(), ne.gridX(), limit);
		}
		GridCursor decoded = decodeCursor(cursor);
		return gridRepository.findOccupiedPageAfter(
			userId, sw.gridY(), ne.gridY(), sw.gridX(), ne.gridX(), decoded.gridY(), decoded.gridX(), limit);
	}

	private GridCursor decodeCursor(String cursor) {
		try {
			return GridCursor.decode(cursor);
		} catch (RuntimeException e) {
			throw new ApiException(GridErrorCode.INVALID_CURSOR, e);
		}
	}

	private List<OccupiedGridProjection> queryByRange(long userId, ViewportBounds bounds) {
		// bbox 남서/북동 코너를 GridEncoder(단일 진실 원천)로 grid_y/grid_x 정수 인덱스로 환산한다.
		GridIndex sw = GridEncoder.decode(GridEncoder.encode(bounds.swLat(), bounds.swLng()));
		GridIndex ne = GridEncoder.decode(GridEncoder.encode(bounds.neLat(), bounds.neLng()));
		return gridRepository.findOccupiedInRange(userId, sw.gridY(), ne.gridY(), sw.gridX(), ne.gridX());
	}

	private List<OccupiedGridView> toViews(List<OccupiedGridProjection> rows) {
		// 리졸버는 항목 수와 무관하게 매핑 진입 전 1회만 받는다 — 항목당 zones 조회(N+1) 봉쇄 (MSG-341 FR-8)
		ZoneNameResolver resolver = zoneNameQueryService.resolver();
		return rows.stream()
			.map(p -> {
				ZoneCellName name = resolver.name(p.getGridY(), p.getGridX());
				return new OccupiedGridView(p.getGridId(), p.getGridY(), p.getGridX(),
					name.zoneName(), name.zoneCell());
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
		if (bounds.swLat() > bounds.neLat() || bounds.swLng() > bounds.neLng()) {
			throw new ApiException(GridErrorCode.INVALID_VIEWPORT);
		}
		double latSpan = bounds.neLat() - bounds.swLat();
		double lngSpan = bounds.neLng() - bounds.swLng();
		if (latSpan > MAX_VIEWPORT_SPAN_DEG || lngSpan > MAX_VIEWPORT_SPAN_DEG) {
			throw new ApiException(GridErrorCode.VIEWPORT_TOO_LARGE);
		}
	}
}
