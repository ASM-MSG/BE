package com.msg.fillmap.grid.service;

/**
 * viewport 조회 전략 — 부하테스트로 채택 결정(보류)이라 두 경로를 모두 유지하고 요청 시 선택 가능하게 둔다.
 * 외부 계약(쿼리 파라미터 strategy=A|B, k6 부하테스트)과 이름을 맞춘다.
 * A = 정수 grid_y/grid_x BETWEEN 범위 스캔(btree), B = ST_Intersects 공간 쿼리(GIST).
 */
public enum ViewportStrategy {

	A,
	B;

	public static final ViewportStrategy DEFAULT = A;
}
