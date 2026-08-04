package com.msg.fillmap.usergrid.service;

import java.util.List;

/**
 * 개인 도감 조회 계약 (B 제공 → A 소비, infrastructure.md 경계면). GridQueryService 와 대칭.
 * 엔티티는 grid.entity.UserGrid(Owner A, MSG-78 D6) — usergrid 는 그 위의 조회 계약 패키지.
 */
public interface UserGridQueryService {

	/** 로그인 사용자의 도감 요약: 점령 격자 수 · 영상 총합 · 방문 행정동 수. 점령 0건이면 (0, 0, 0). */
	CollectionSummaryView getCollectionSummary(long userId);

	/**
	 * 갤러리 격자 목록: 내 점령 격자를 최근 수집순(first_collected_at DESC) 최대 30개 (MSG-153, B 내부 read).
	 * 점령 0건이면 빈 리스트. coverVideoId 는 cover 없으면 null(readiness 무관 — 스펙 §API),
	 * coverThumbnailUrl 만 READY 게이트 — cover 없거나 READY 이전·썸네일 미발급이면 null(§D4).
	 */
	List<CollectionGridView> getCollectionGrids(long userId);

	/**
	 * 동 단위 내 영상: 그 행정동(regionCode) 격자들에 올린 내 ACTIVE 영상을 created_at 내림차순으로 반환한다
	 * (MSG-167 §D3, B-내부 read — CollectionController 만 소비, Owner A 미소비라 non-breaking).
	 * 귀속은 격자 축(grids.region_code) — 영상 좌표가 옆 동이어도 격자 소속 동으로 포함된다. 내 도감이라
	 * PRIVATE·인코딩 중 영상도 포함하며(status='ACTIVE' 만), 내 영상 없음/미존재 regionCode 면 빈 리스트.
	 * READY 이전은 thumbnailUrl null(presign 이 null key 흡수).
	 */
	List<RegionVideoView> getRegionVideos(long userId, String regionCode);

	/**
	 * 친구에게 보여줄 격자 목록 — 격자 사실은 전부, 썸네일은 재생 허용 공개범위 영상 것만 (MSG-186 D6,
	 * B-내부 read — friend 도메인만 소비, Owner A 미소비라 non-breaking). 정렬·개수는 본인 갤러리와 동일
	 * (first_collected_at DESC 최대 30개), 수집 0건이면 빈 리스트. 관계 판정(ACCEPTED 친구인지)은 호출처가
	 * 끝내고 여기엔 소유자 userId 만 들어온다 — usergrid 는 friend 를 참조하지 않는다. MSG-285 가 FRIENDS
	 * 공개범위를 추가해도 시그니처는 그대로고 내부 필터만 넓어진다.
	 */
	List<FriendCollectionGridView> getCollectionGridsForFriend(long ownerUserId);
}
