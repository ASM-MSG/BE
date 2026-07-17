package com.msg.fillmap.grid.service;

import java.util.List;

import com.msg.fillmap.grid.dto.ViewportBounds;

/**
 * 격자 색칠 조회 계약 (A 제공 → B 소비, infrastructure.md 경계면). videos 미접근 — user_grids 만 읽는다.
 * 반환은 내부 뷰(GridCellView/OccupiedGridView), HTTP 응답 DTO 변환은 컨트롤러 책임.
 */
public interface GridQueryService {

	/**
	 * 단일 격자 색칠 상태: 로그인 사용자의 점령 여부 + videoCount. 미점령이면 occupied=false, videoCount=0.
	 * gridId 포맷 불량이면 GridErrorCode.INVALID_GRID_ID.
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
}
