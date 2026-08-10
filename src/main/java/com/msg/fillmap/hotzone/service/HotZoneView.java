package com.msg.fillmap.hotzone.service;

/**
 * 핫구역 내부 뷰 (서비스 간 계약). gridY/gridX 는 정수 인덱스로 좁혀 담는다
 * (OccupiedGridView 패턴 — GridIndex 는 long 이지만 격자 인덱스 도메인 범위상 int 로 안전, MSG-73 승계).
 * user_id 는 싣지 않는다 (PRD 보안 비기능).
 * zoneName/zoneCell 은 격자 표시명의 구역 부분(MSG-341)으로, 구역 밖 격자면 둘 다 null 이다(항상 쌍).
 * regionName 은 격자의 저장 라벨에서 일괄 조회한 행정동 전체 이름(MSG-349)으로, 무귀속 격자면 null 이다 —
 * zoneName 이 null 일 때 마커 라벨은 이 값이다.
 */
public record HotZoneView(String gridId, int gridY, int gridX, long score, String zoneName, String zoneCell,
	String regionName) {
}
