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
	 * viewport 안에서 로그인 사용자가 점령한 격자 목록(미점령 제외) — 기본 전략(ViewportStrategy.DEFAULT).
	 * Owner B 가 소비하는 계약 시그니처(MSG-73 §계약 변경)다.
	 * 뒤집힌 bbox → INVALID_VIEWPORT, 면적 상한 초과 → VIEWPORT_TOO_LARGE.
	 */
	List<OccupiedGridView> getOccupiedInViewport(long userId, ViewportBounds bounds);

	/**
	 * 위 조회의 전략 선택 오버로드 — 접근 A(RANGE_SCAN)/B(GIST)를 명시 선택한다(부하테스트/벤치마크용).
	 */
	List<OccupiedGridView> getOccupiedInViewport(long userId, ViewportBounds bounds, ViewportStrategy strategy);
}
