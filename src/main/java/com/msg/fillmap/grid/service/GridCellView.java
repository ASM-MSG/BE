package com.msg.fillmap.grid.service;

/**
 * 단일 격자 색칠 상태 내부 뷰 (서비스 간 계약). HTTP 응답 DTO 로의 변환은 컨트롤러가 한다.
 * zoneName/zoneCell 은 격자 표시명의 구역 부분(MSG-341)으로, 구역 밖 격자면 둘 다 null 이다(항상 쌍).
 * regionName 은 격자 중심점 재판정으로 얻은 행정동 전체 이름(MSG-349)으로, 미점령 격자에도 담긴다 —
 * zoneName 이 null 일 때의 표시 이름이다. 무귀속(해상)이거나 서비스 범위 밖 격자면 null.
 */
public record GridCellView(String gridId, boolean occupied, int videoCount, String zoneName, String zoneCell,
	String regionName) {
}
