package com.msg.fillmap.grid.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.grid.exception.GridErrorCode;
import com.msg.fillmap.grid.repository.GridRepository;
import com.msg.fillmap.grid.repository.OccupiedGridProjection;
import com.msg.fillmap.grid.service.GridCellView;
import com.msg.fillmap.grid.service.GridQueryService;
import com.msg.fillmap.grid.service.OccupiedGridView;
import com.msg.fillmap.grid.service.ViewportStrategy;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GridQueryServiceImpl implements GridQueryService {

	// viewport 한 변의 최대 위경도 span(도). 초과 시 과도한 스캔으로 보고 VIEWPORT_TOO_LARGE.
	// 대규모 범위 + 커서 페이지네이션은 MSG-90 소관 — 본 티켓은 상한 내 기본 viewport만 다룬다.
	private static final double MAX_VIEWPORT_SPAN_DEG = 0.5;

	private final GridRepository gridRepository;

	@Override
	public GridCellView getCell(long userId, String gridId) {
		validateGridId(gridId);
		Integer videoCount = gridRepository.findVideoCount(userId, gridId).orElse(null);
		if (videoCount == null) {
			return new GridCellView(gridId, false, 0);
		}
		return new GridCellView(gridId, true, videoCount);
	}

	@Override
	public List<OccupiedGridView> getOccupiedInViewport(long userId, ViewportBounds bounds) {
		return getOccupiedInViewport(userId, bounds, ViewportStrategy.DEFAULT);
	}

	@Override
	public List<OccupiedGridView> getOccupiedInViewport(long userId, ViewportBounds bounds, ViewportStrategy strategy) {
		validateBounds(bounds);
		List<OccupiedGridProjection> occupied = switch (strategy) {
			case A -> queryByRange(userId, bounds);
			case B -> gridRepository.findOccupiedByIntersects(
				userId, bounds.swLng(), bounds.swLat(), bounds.neLng(), bounds.neLat());
		};
		return occupied.stream()
			.map(p -> new OccupiedGridView(p.getGridId(), p.getGridY(), p.getGridX()))
			.toList();
	}

	private List<OccupiedGridProjection> queryByRange(long userId, ViewportBounds bounds) {
		// bbox 남서/북동 코너를 GridEncoder(단일 진실 원천)로 grid_y/grid_x 정수 인덱스로 환산한다.
		GridIndex sw = GridEncoder.decode(GridEncoder.encode(bounds.swLat(), bounds.swLng()));
		GridIndex ne = GridEncoder.decode(GridEncoder.encode(bounds.neLat(), bounds.neLng()));
		return gridRepository.findOccupiedInRange(userId, sw.gridY(), ne.gridY(), sw.gridX(), ne.gridX());
	}

	private void validateGridId(String gridId) {
		try {
			GridEncoder.decode(gridId);
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
