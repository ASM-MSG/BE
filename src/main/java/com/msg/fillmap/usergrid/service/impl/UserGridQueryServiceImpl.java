package com.msg.fillmap.usergrid.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.usergrid.repository.CollectionGridProjection;
import com.msg.fillmap.usergrid.repository.CollectionSummaryProjection;
import com.msg.fillmap.usergrid.repository.UserGridRepository;
import com.msg.fillmap.usergrid.service.CollectionGridView;
import com.msg.fillmap.usergrid.service.CollectionSummaryView;
import com.msg.fillmap.usergrid.service.UserGridQueryService;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserGridQueryServiceImpl implements UserGridQueryService {

	private final UserGridRepository userGridRepository;
	private final ThumbnailUrlPresigner thumbnailUrlPresigner;

	@Override
	public CollectionSummaryView getCollectionSummary(long userId) {
		CollectionSummaryProjection projection = userGridRepository.getCollectionSummary(userId);
		return new CollectionSummaryView(
			projection.getTotalGridCount(),
			projection.getTotalVideoCount(),
			projection.getVisitedRegionCount()
		);
	}

	@Override
	public List<CollectionGridView> getCollectionGrids(long userId) {
		return userGridRepository.getCollectionGrids(userId).stream()
			.map(this::toView)
			.toList();
	}

	/**
	 * gridY/gridX 는 gridId 를 GridEncoder.decode 로 산출한다(grids 조인 회피, D5). 격자 인덱스는
	 * 한국·전지구를 통틀어 int 범위에 넉넉히 들어가므로 int 로 좁혀 응답 DTO(Integer)에 맞춘다.
	 * cover 썸네일 key 는 presigned GET URL 로 바꾼다 — key 가 null(cover 없음·READY 이전)이면 null(D4).
	 */
	private CollectionGridView toView(CollectionGridProjection projection) {
		GridIndex index = GridEncoder.decode(projection.getGridId());
		return new CollectionGridView(
			projection.getGridId(),
			(int) index.gridY(),
			(int) index.gridX(),
			projection.getFirstCollectedAt(),
			projection.getLastUploadedAt(),
			projection.getVideoCount(),
			projection.getCoverVideoId(),
			thumbnailUrlPresigner.presign(projection.getCoverThumbnailKey())
		);
	}
}
