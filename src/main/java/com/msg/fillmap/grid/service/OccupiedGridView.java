package com.msg.fillmap.grid.service;

/**
 * viewport 점령 격자 내부 뷰 (서비스 간 계약). gridY/gridX 는 정수 인덱스로 좁혀 담는다
 * (GridEncoder.GridIndex 는 long 이지만 격자 인덱스 도메인 범위상 int 로 안전 — MSG-73 reviewer 지적).
 */
public record OccupiedGridView(String gridId, int gridY, int gridX) {
}
