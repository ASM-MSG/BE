package com.msg.fillmap.grid.service;

/**
 * 뷰포트 집계 항목 내부 뷰 (MSG-356, OccupiedGridView 선례). HTTP 응답 DTO 변환은 컨트롤러 책임.
 * regionCode 는 행정동 코드를 단위 길이로 자른 묶음 키, name 은 그 단위의 표시 이름이다.
 * lat/lng 는 그룹에 속한 점령 격자 중심의 평균(마커 대표 좌표), count 는 그 단위 안 점령 격자 수다.
 * 행정동 미판정 격자 묶음은 regionCode 와 name 이 모두 null 인 항목 하나로 온다 (제외 아님, FR-7).
 */
public record RegionAggregateView(String regionCode, String name, double lat, double lng, int count) {
}
