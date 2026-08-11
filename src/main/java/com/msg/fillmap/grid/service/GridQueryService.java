package com.msg.fillmap.grid.service;

import java.util.List;

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
}
