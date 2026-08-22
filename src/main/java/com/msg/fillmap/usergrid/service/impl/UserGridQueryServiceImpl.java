package com.msg.fillmap.usergrid.service.impl;

import java.time.ZoneOffset;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.response.ErrorCode;
import com.msg.fillmap.usergrid.dto.CollectionGridSort;
import com.msg.fillmap.usergrid.repository.CollectionGridProjection;
import com.msg.fillmap.usergrid.repository.CollectionSummaryProjection;
import com.msg.fillmap.usergrid.repository.FriendCollectionGridProjection;
import com.msg.fillmap.usergrid.repository.RegionVideoProjection;
import com.msg.fillmap.usergrid.repository.UserGridRepository;
import com.msg.fillmap.usergrid.service.CollectionGridPage;
import com.msg.fillmap.usergrid.service.CollectionGridView;
import com.msg.fillmap.usergrid.service.CollectionSummaryView;
import com.msg.fillmap.usergrid.service.FriendCollectionGridView;
import com.msg.fillmap.usergrid.service.GridOccupantView;
import com.msg.fillmap.usergrid.service.RegionVideoView;
import com.msg.fillmap.usergrid.service.UploadHistoryView;
import com.msg.fillmap.usergrid.service.UserGridQueryService;
import com.msg.fillmap.usergrid.support.CollectionGridCursor;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;
import com.msg.fillmap.zone.service.ZoneCellName;
import com.msg.fillmap.zone.service.ZoneNameQueryService;
import com.msg.fillmap.zone.service.ZoneNameResolver;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserGridQueryServiceImpl implements UserGridQueryService {

	/** 전국 갤러리 기본 상한 — 파라미터 없는 기존 호출의 계약값(MSG-153 D3). */
	private static final int DEFAULT_GRID_LIMIT = 30;
	private static final int COLLECTION_GRID_PAGE_SIZE = 20;
	private static final int COLLECTION_GRID_LOOKAHEAD_SIZE = COLLECTION_GRID_PAGE_SIZE + 1;

	private final UserGridRepository userGridRepository;
	private final ThumbnailUrlPresigner thumbnailUrlPresigner;
	private final ZoneNameQueryService zoneNameQueryService;

	@Override
	public CollectionSummaryView getCollectionSummary(long userId) {
		CollectionSummaryProjection projection = userGridRepository.getCollectionSummary(userId);
		return new CollectionSummaryView(
			projection.getTotalGridCount(),
			projection.getTotalVideoCount(),
			projection.getVisitedRegionCount(),
			projection.getCurrentStreak(),
			projection.getMaxStreak(),
			projection.getBadgeCount()
		);
	}

	/**
	 * 구역 이름 리졸버는 항목 매핑 진입 전에 1회만 받는다 (MSG-341 D-1) — 세 목록 조회가 공유하는 규칙이다.
	 * 항목마다 받으면 격자 수만큼 zones 를 다시 읽는 N+1 이 된다(FR-8). 리졸버 생성이 이 readOnly 트랜잭션
	 * 안이라 zones 도 격자 목록과 같은 스냅샷이고, 페이지 중간에 zones 가 바뀌어 항목끼리 기준이 갈리지 않는다.
	 *
	 * limit 결정(MSG-460): 지정하면 그 값(1 미만은 1로 보정), 생략하면 전국 조회는 기존 계약대로 30이고
	 * 행정동 조회는 20이다. sort 는 리포지토리 경계에서 String 으로 좁힌다. enum 을
	 * 네이티브 쿼리에 직접 바인딩하면 Hibernate 6 가 ordinal 로 넘겨 텍스트 비교가 항상 실패한다.
	 */
	@Override
	public List<CollectionGridView> getCollectionGrids(
		long userId, String regionCode, CollectionGridSort sort, Integer limit) {
		Integer effectiveLimit = resolveLimit(regionCode, limit);
		ZoneNameResolver resolver = zoneNameQueryService.resolver();
		return userGridRepository.getCollectionGrids(userId, regionCode, sort.name(), effectiveLimit).stream()
			.map(projection -> toView(projection, resolver))
			.toList();
	}

	@Override
	public CollectionGridPage getCollectionGridPage(long userId, String regionCode, String cursor) {
		CollectionGridCursor decoded = decodeCursor(regionCode, cursor);
		List<CollectionGridProjection> rows = queryCollectionGridPage(userId, regionCode, decoded);
		boolean hasNext = rows.size() > COLLECTION_GRID_PAGE_SIZE;
		List<CollectionGridProjection> pageRows = hasNext
			? rows.subList(0, COLLECTION_GRID_PAGE_SIZE)
			: rows;
		ZoneNameResolver resolver = zoneNameQueryService.resolver();
		List<CollectionGridView> items = pageRows.stream()
			.map(projection -> toView(projection, resolver))
			.toList();
		String nextCursor = null;
		if (hasNext) {
			CollectionGridProjection last = pageRows.get(pageRows.size() - 1);
			nextCursor = CollectionGridCursor.encode(
				regionCode, last.getLastUploadedAt(), last.getVideoCount(), last.getGridId());
		}
		return new CollectionGridPage(items, hasNext, nextCursor);
	}

	private List<CollectionGridProjection> queryCollectionGridPage(
		long userId, String regionCode, CollectionGridCursor cursor) {
		PageRequest pageRequest = PageRequest.of(0, COLLECTION_GRID_LOOKAHEAD_SIZE);
		if (cursor == null) {
			return userGridRepository.getCollectionGridPage(userId, regionCode, pageRequest);
		}
		return userGridRepository.getCollectionGridPageAfter(
			userId, regionCode, cursor.lastUploadedAt(), cursor.videoCount(), cursor.gridId(), pageRequest);
	}

	private CollectionGridCursor decodeCursor(String regionCode, String cursor) {
		if (cursor == null) {
			return null;
		}
		try {
			CollectionGridCursor decoded = CollectionGridCursor.decode(cursor);
			if (!decoded.regionCode().equals(regionCode)) {
				throw new ApiException(ErrorCode.BAD_REQUEST);
			}
			return decoded;
		} catch (ApiException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new ApiException(ErrorCode.BAD_REQUEST, e);
		}
	}

	/**
	 * int와 Integer를 섞은 삼항 연산자의 null 언박싱 회귀를 피하려
	 * 분기로 기본 상한을 정한다.
	 */
	private static Integer resolveLimit(String regionCode, Integer limit) {
		if (limit != null) {
			return Math.max(limit, 1);
		}
		if (regionCode != null) {
			return COLLECTION_GRID_PAGE_SIZE;
		}
		return DEFAULT_GRID_LIMIT;
	}

	/**
	 * gridY/gridX 는 gridId 를 GridEncoder.decode 로 산출한다(grids 조인 회피, D5). 격자 인덱스는
	 * 한국·전지구를 통틀어 int 범위에 넉넉히 들어가므로 int 로 좁혀 응답 DTO(Integer)에 맞춘다.
	 * cover 썸네일 key 는 presigned GET URL 로 바꾼다 — key 가 null(cover 없음·READY 이전)이면 null(D4).
	 * regionName 은 쿼리가 grids·regions 조인으로 이미 채운 값을 그대로 통과시킨다(무귀속이면 null, MSG-167 §D4).
	 * 구역 이름은 같은 인덱스로 리졸버가 산술한다 — 구역 밖이면 NONE 이라 여기에 null 분기가 없다(MSG-341).
	 */
	private CollectionGridView toView(CollectionGridProjection projection, ZoneNameResolver resolver) {
		GridIndex index = GridEncoder.decode(projection.getGridId());
		ZoneCellName name = resolver.name(index.gridY(), index.gridX());
		return new CollectionGridView(
			projection.getGridId(),
			(int) index.gridY(),
			(int) index.gridX(),
			projection.getFirstCollectedAt(),
			projection.getLastUploadedAt(),
			projection.getVideoCount(),
			projection.getCoverVideoId(),
			thumbnailUrlPresigner.presign(projection.getCoverThumbnailKey()),
			projection.getCoverDurationSec(),
			projection.getRegionName(),
			name.zoneName(),
			name.zoneCell()
		);
	}

	@Override
	public List<RegionVideoView> getRegionVideos(long userId, String regionCode) {
		ZoneNameResolver resolver = zoneNameQueryService.resolver();
		return userGridRepository.getRegionVideos(userId, regionCode).stream()
			.map(projection -> toView(projection, resolver))
			.toList();
	}

	/**
	 * thumbnailKey 를 presigned GET URL 로 바꾼다 — key 가 null(READY 이전)이면 null(§D3). gridId 는
	 * 항목별 격자 라벨용으로 그대로 통과시킨다(Open Q2). 나머지 필드는 projection 값을 옮긴다.
	 * 여기만 gridY/gridX 를 뷰에 담지 않으므로 구역 산술용 인덱스를 이 자리에서 decode 한다 (MSG-341 D-5).
	 */
	private RegionVideoView toView(RegionVideoProjection projection, ZoneNameResolver resolver) {
		GridIndex index = GridEncoder.decode(projection.getGridId());
		ZoneCellName name = resolver.name(index.gridY(), index.gridX());
		return new RegionVideoView(
			projection.getVideoId(),
			projection.getGridId(),
			thumbnailUrlPresigner.presign(projection.getThumbnailKey()),
			projection.getProcessingStatus(),
			projection.getDurationSec(),
			projection.getCreatedAt(),
			name.zoneName(),
			name.zoneCell()
		);
	}

	@Override
	public List<FriendCollectionGridView> getCollectionGridsForFriend(long ownerUserId) {
		ZoneNameResolver resolver = zoneNameQueryService.resolver();
		return userGridRepository.getCollectionGridsForFriend(ownerUserId).stream()
			.map(projection -> toView(projection, resolver))
			.toList();
	}

	/**
	 * 본인 갤러리의 toView 와 같은 산식(gridY/gridX 는 GridEncoder.decode, 썸네일 key 는 presign, 구역 이름은
	 * 리졸버 산술)이되 영상 ID 를 담지 않는다 — 친구 응답은 표시 전용이라 비공개 영상의 존재가 id 로 새면
	 * 안 된다 (MSG-186 D6). thumbnailKey 가 null(재생 허용 영상 없음)이면 presign 이 null 을 그대로 흘려
	 * 격자 사실만 남는다.
	 */
	private FriendCollectionGridView toView(FriendCollectionGridProjection projection, ZoneNameResolver resolver) {
		GridIndex index = GridEncoder.decode(projection.getGridId());
		ZoneCellName name = resolver.name(index.gridY(), index.gridX());
		return new FriendCollectionGridView(
			projection.getGridId(),
			(int) index.gridY(),
			(int) index.gridX(),
			projection.getFirstCollectedAt(),
			projection.getLastUploadedAt(),
			projection.getVideoCount(),
			thumbnailUrlPresigner.presign(projection.getThumbnailKey()),
			projection.getRegionName(),
			name.zoneName(),
			name.zoneCell()
		);
	}

	@Override
	public List<GridOccupantView> getGridOccupants(String gridId) {
		return userGridRepository.getGridOccupants(gridId).stream()
			.map(projection -> new GridOccupantView(projection.getUserId(), projection.getRegionName()))
			.toList();
	}

	/**
	 * created_at 은 타임존 없는 벽시계 저장이라 저장 존을 쿼리에 파라미터로 넘겨 해석시킨다
	 * (MSG-362 §D4 — MSG-315 의 임계값 환산과 같은 전제를 읽기 방향으로 쓴 것). 그 존은 UTC 다:
	 * 쓰기 경로(Video 생성자)가 UTC 를 넣도록 고정된 MSG-376 후속 수정 이후의 사실이며, 그 전에는
	 * JVM 기본 존이라 여기도 systemDefault 를 넘겼다. 날짜 접기는 전부 쿼리 몫이라 여기는
	 * 프로젝션을 뷰로 옮기는 것이 전부다.
	 */
	@Override
	public List<UploadHistoryView> getUploadHistory(long userId) {
		return userGridRepository.getUploadHistory(userId, ZoneOffset.UTC.getId()).stream()
			.map(projection -> new UploadHistoryView(projection.getUploadDate(), projection.getUploadCount()))
			.toList();
	}
}
