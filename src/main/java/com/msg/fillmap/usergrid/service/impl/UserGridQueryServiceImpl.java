package com.msg.fillmap.usergrid.service.impl;

import java.time.ZoneOffset;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.usergrid.dto.CollectionGridSort;
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
import com.msg.fillmap.usergrid.service.UploadHistoryView;
import com.msg.fillmap.usergrid.service.UserGridQueryService;
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
	 * limit 결정(MSG-388): 지정하면 그 값(1 미만은 1 로 보정 — 전역 탐색 선례), 생략하면 regionCode 유무로
	 * 갈린다. 전국 조회는 기존 갤러리 계약 그대로 30이고(FR-7), 행정동 조회는 null 을 넘겨 그 동네 전부를
	 * 받는다(PostgreSQL 이 LIMIT NULL 을 무제한으로 읽는다 — 한 동네의 내 격자 수는 개인 활동량에 비례하는
	 * 작은 수라 조용한 절단보다 전부가 맞다). sort 는 리포지토리 경계에서 String 으로 좁힌다 — enum 을
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

	/**
	 * null 이 곧 "상한 없음"이라 삼항 연산자를 쓰지 않는다 — int 와 Integer 를 섞으면 자바가 양쪽을 int 로
	 * 맞추려 null 을 언박싱해 NPE 가 난다(MSG-388 테스트 적발).
	 */
	private static Integer resolveLimit(String regionCode, Integer limit) {
		if (limit != null) {
			return Math.max(limit, 1);
		}
		if (regionCode != null) {
			return null;
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
