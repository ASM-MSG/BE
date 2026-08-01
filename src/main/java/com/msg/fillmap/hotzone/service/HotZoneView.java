package com.msg.fillmap.hotzone.service;

/**
 * 핫구역 내부 뷰 (서비스 간 계약). gridY/gridX 는 정수 인덱스로 좁혀 담는다
 * (OccupiedGridView 패턴 — GridIndex 는 long 이지만 격자 인덱스 도메인 범위상 int 로 안전, MSG-73 승계).
 * user_id 는 싣지 않는다 (PRD 보안 비기능).
 */
public record HotZoneView(String gridId, int gridY, int gridX, long score) {
}
