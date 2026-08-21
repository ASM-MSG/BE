package com.msg.fillmap.grid.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.msg.fillmap.grid.dto.RegionUnit;
import com.msg.fillmap.grid.dto.ViewportBounds;

/**
 * 격자 색칠 조회 계약 (A 제공 → B 소비, infrastructure.md 경계면). videos 미접근 — user_grids 만 읽는다.
 * 반환은 내부 뷰(GridCellView/OccupiedGridView), HTTP 응답 DTO 변환은 컨트롤러 책임.
 */
public interface GridQueryService {

	/**
	 * 단일 격자 색칠 상태: 로그인 사용자의 점령 여부 + videoCount. 미점령이면 occupied=false, videoCount=0.
	 * gridId 포맷 불량이면 GridErrorCode.INVALID_GRID_ID.
	 * 표시명 재료로 zoneName/zoneCell(MSG-341)과 regionName(행정동, MSG-349)을 함께 담는다 —
	 * regionName 은 중심점 재판정이라 미점령 격자에도 나오고, 무귀속·서비스 범위 밖이면 null 이다(에러 아님).
	 */
	GridCellView getCell(long userId, String gridId);

	/**
	 * viewport 안에서 로그인 사용자가 점령한 격자 전체 목록(미점령 제외, 비페이지).
	 * Owner B 가 소비하는 계약 시그니처(MSG-73 §계약 변경)라 유지한다(non-breaking, MSG-90 Q3).
	 * 뒤집힌 bbox → INVALID_VIEWPORT, 면적 상한 초과 → VIEWPORT_TOO_LARGE.
	 */
	List<OccupiedGridView> getOccupiedInViewport(long userId, ViewportBounds bounds);

	/**
	 * viewport 점령 격자 한 페이지 — keyset cursor 페이지네이션 (MSG-90).
	 * cursor 는 직전 응답의 opaque 토큰(null 이면 첫 페이지), 디코드 실패 → INVALID_CURSOR.
	 * size 는 1..5000 범위 밖이면 INVALID_PAGE_SIZE. 마지막 페이지의 nextCursor 는 null 이다.
	 */
	OccupiedGridPage getOccupiedInViewport(long userId, ViewportBounds bounds, String cursor, int size);

	/**
	 * viewport 안 점령 격자를 행정 단위(동, 시군구, 시도)로 묶어 센 집계 목록 (MSG-356).
	 * 항목은 묶음 키(regionCode 접두), 표시 이름, 대표 좌표(그룹 점령 격자 중심 평균), 격자 수.
	 * region_code 미판정 격자는 키와 이름이 null 인 항목 하나로 포함된다(제외 불가, FR-7).
	 * bbox 상한은 단위별 차등(RegionUnit 보유), 초과 시 VIEWPORT_TOO_LARGE.
	 */
	List<RegionAggregateView> getOccupiedAggregatesInViewport(long userId, ViewportBounds bounds, RegionUnit unit);

	/**
	 * 지도 홈의 뷰포트 묶음 목록과 중심 행정동 요약을 함께 반환한다 (MSG-374).
	 * 중심 행정동의 수치는 뷰포트가 아니라 행정동 전체의 개인 점령을 센다.
	 * 중심이 해상 또는 서비스 범위 밖이면 currentRegion 은 null 이다.
	 */
	GridAggregationView getOccupiedAggregatesWithCurrentRegion(
		long userId, ViewportBounds bounds, RegionUnit unit);

	/**
	 * 격자 여러 개의 행정동 이름을 한 번에 얻는다 (MSG-439, 행사 위치 표시명 재료).
	 * grids row 존재와 무관하게 격자 중심점으로 판정한다 — grids 는 lazy insert 라 아무도 영상을 안 올린
	 * 격자에는 row 가 없고, 저장 라벨에만 기대면 실제 행정동 안 격자가 무귀속으로 보인다.
	 * 저장 라벨을 읽는 GridRepository.findRegionNames 와 이름을 나눈 이유가 그 차이다 — 그쪽은 grids
	 * INNER JOIN 이라 row 없는 격자가 결과에서 빠지므로 표시명 재료로는 쓸 수 없다.
	 * 반환은 gridId → 이름 사전이고 무귀속·서비스 범위 밖 격자는 키 자체가 없다(null 값을 담지 않는다).
	 * 입력 순서는 유지되며 중복 gridId 는 한 번만 판정한다. 빈 목록이면 빈 맵이다.
	 */
	Map<String, String> resolveRegionNames(Collection<String> gridIds);
}
