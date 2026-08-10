package com.msg.fillmap.grid.service;

/**
 * viewport 점령 격자 내부 뷰 (서비스 간 계약). gridY/gridX 는 정수 인덱스로 좁혀 담는다
 * (GridEncoder.GridIndex 는 long 이지만 격자 인덱스 도메인 범위상 int 로 안전 — MSG-73 reviewer 지적).
 * zoneName/zoneCell 은 격자 표시명의 구역 부분(MSG-341)으로, 구역 밖 격자면 둘 다 null 이다(항상 쌍).
 * regionName 은 격자 중심점이 속한 행정동 전체 이름(MSG-349)으로, 구역 안 격자에도 항상 담는다 —
 * zoneName 이 null 일 때의 표시 이름이자 위치줄("부산 부산진구 …")의 시·구 출처다. 무귀속이면 null.
 * 친구 격자 뷰포트도 이 뷰를 그대로 통과시키므로 두 경로가 같은 이름을 낸다.
 */
public record OccupiedGridView(String gridId, int gridY, int gridX, String zoneName, String zoneCell,
	String regionName) {
}
