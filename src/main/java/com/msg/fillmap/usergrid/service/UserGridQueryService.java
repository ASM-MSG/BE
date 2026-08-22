package com.msg.fillmap.usergrid.service;

import java.util.List;

import com.msg.fillmap.usergrid.dto.CollectionGridSort;

/**
 * 개인 도감 조회 계약 (B 제공 → A 소비, infrastructure.md 경계면). GridQueryService 와 대칭.
 * 엔티티는 grid.entity.UserGrid(Owner A, MSG-78 D6) — usergrid 는 그 위의 조회 계약 패키지.
 */
public interface UserGridQueryService {

	/**
	 * 로그인 사용자의 도감 요약: 점령 격자 수 · 영상 총합 · 방문 행정동 수 + 현재/최장 스트릭 · 뱃지 수
	 * (MSG-362 비파괴 확장 — 기존 접근자 불변). 업로드 경험 0 사용자도 여섯 지표 전부 0.
	 */
	CollectionSummaryView getCollectionSummary(long userId);

	/**
	 * 갤러리 격자 목록: 내 점령 격자를 정렬·행정동·개수 상한을 받아 반환한다 (MSG-153 + MSG-388 확장,
	 * B 내부 read — CollectionController 만 소비, Owner A 미소비라 실질 non-breaking).
	 * 점령 0건이면 빈 리스트. coverVideoId 는 cover 없으면 null(readiness 무관 — 스펙 §API),
	 * coverThumbnailUrl 만 READY 게이트 — cover 없거나 READY 이전·썸네일 미발급이면 null(§D4).
	 * coverDurationSec 은 게이트 밖이라 인코딩 완료 전에도 실린다(null 은 cover 자체가 없을 때뿐).
	 *
	 * @param regionCode 행정동 코드. null 이면 전국, 지정하면 그 행정동에 속한 격자만(격자 축 귀속 —
	 *                   영상 좌표가 옆 동이어도 격자 소속 동 기준). 미존재 코드면 빈 리스트(에러 아님)
	 * @param sort       정렬 축. 컨트롤러 defaultValue 로 항상 비null
	 * @param limit      카드 수 상한. null 이면 전국 조회는 30, 행정동 조회는 20. 1 미만은 1로 보정
	 */
	List<CollectionGridView> getCollectionGrids(long userId, String regionCode, CollectionGridSort sort, Integer limit);

	/**
	 * 행정동 전체 보기 개인 격자 페이지.
	 * cursor 는 직전 응답의 nextCursor 이며 null 이면 첫 페이지다.
	 * 한 페이지는 최대 20개고 최근 업로드 시각, 영상 수, 격자 ID 내림차순이다.
	 */
	CollectionGridPage getCollectionGridPage(long userId, String regionCode, String cursor);

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

	/**
	 * 격자를 점령한 사용자 전원 + 격자 행정동 이름 (MSG-181 핫구역 진입 통지용, B-내부 read —
	 * notification 만 소비, Owner A 미소비라 non-breaking). 행정동 없는 격자(해상 등)는 regionName null.
	 */
	List<GridOccupantView> getGridOccupants(String gridId);

	/**
	 * 날짜별 업로드 기록: 내 영상을 KST 날짜로 접어 업로드가 있었던 날만 날짜 오름차순으로 반환한다
	 * (MSG-362, B-내부 read — CollectionController 만 소비, Owner A 미소비라 non-breaking).
	 * 삭제·블라인드 영상의 업로드도 센다(FR-8, 스트릭 소급 차감 없음과 정합). 업로드 0건이면 빈 리스트.
	 */
	List<UploadHistoryView> getUploadHistory(long userId);
}
