package com.msg.fillmap.usergrid.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.usergrid.repository.CollectionGridProjection;
import com.msg.fillmap.usergrid.repository.CollectionSummaryProjection;
import com.msg.fillmap.usergrid.repository.FriendCollectionGridProjection;
import com.msg.fillmap.usergrid.repository.RegionVideoProjection;
import com.msg.fillmap.usergrid.repository.UserGridRepository;
import com.msg.fillmap.usergrid.service.CollectionGridView;
import com.msg.fillmap.usergrid.service.CollectionSummaryView;
import com.msg.fillmap.usergrid.service.FriendCollectionGridView;
import com.msg.fillmap.usergrid.service.GridOccupantView;
import com.msg.fillmap.usergrid.service.RegionVideoView;
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
	 * regionName 은 쿼리가 grids·regions 조인으로 이미 채운 값을 그대로 통과시킨다(무귀속이면 null, MSG-167 §D4).
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
			thumbnailUrlPresigner.presign(projection.getCoverThumbnailKey()),
			projection.getRegionName()
		);
	}

	@Override
	public List<RegionVideoView> getRegionVideos(long userId, String regionCode) {
		return userGridRepository.getRegionVideos(userId, regionCode).stream()
			.map(this::toView)
			.toList();
	}

	/**
	 * thumbnailKey 를 presigned GET URL 로 바꾼다 — key 가 null(READY 이전)이면 null(§D3). gridId 는
	 * 항목별 격자 라벨용으로 그대로 통과시킨다(Open Q2). 나머지 필드는 projection 값을 옮긴다.
	 */
	private RegionVideoView toView(RegionVideoProjection projection) {
		return new RegionVideoView(
			projection.getVideoId(),
			projection.getGridId(),
			thumbnailUrlPresigner.presign(projection.getThumbnailKey()),
			projection.getProcessingStatus(),
			projection.getDurationSec(),
			projection.getCreatedAt()
		);
	}

	@Override
	public List<FriendCollectionGridView> getCollectionGridsForFriend(long ownerUserId) {
		return userGridRepository.getCollectionGridsForFriend(ownerUserId).stream()
			.map(this::toView)
			.toList();
	}

	/**
	 * 본인 갤러리의 toView 와 같은 산식(gridY/gridX 는 GridEncoder.decode, 썸네일 key 는 presign)이되 영상 ID 를
	 * 담지 않는다 — 친구 응답은 표시 전용이라 비공개 영상의 존재가 id 로 새면 안 된다 (MSG-186 D6).
	 * thumbnailKey 가 null(재생 허용 영상 없음)이면 presign 이 null 을 그대로 흘려 격자 사실만 남는다.
	 */
	private FriendCollectionGridView toView(FriendCollectionGridProjection projection) {
		GridIndex index = GridEncoder.decode(projection.getGridId());
		return new FriendCollectionGridView(
			projection.getGridId(),
			(int) index.gridY(),
			(int) index.gridX(),
			projection.getFirstCollectedAt(),
			projection.getLastUploadedAt(),
			projection.getVideoCount(),
			thumbnailUrlPresigner.presign(projection.getThumbnailKey()),
			projection.getRegionName()
		);
	}

	@Override
	public List<GridOccupantView> getGridOccupants(String gridId) {
		return userGridRepository.getGridOccupants(gridId).stream()
			.map(projection -> new GridOccupantView(projection.getUserId(), projection.getRegionName()))
			.toList();
	}
}
