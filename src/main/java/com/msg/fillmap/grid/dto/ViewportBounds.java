package com.msg.fillmap.grid.dto;

/**
 * viewport 조회 입력 값객체 — 남서(sw)·북동(ne) 좌표. 서비스 계약(GridQueryService) 파라미터.
 * 유효성(뒤집힘·면적 상한)은 서비스가 판정한다.
 */
public record ViewportBounds(double swLat, double swLng, double neLat, double neLng) {
}
