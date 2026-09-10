package com.msg.fillmap.video.repository;

/**
 * 전체 지역 리스트 항목 프로젝션 (단일 네이티브 쿼리 결과, MSG-238 §도메인 3).
 * 게이트 통과 격자가 1개 이상인 행정동만 잡힌다. gridCount 는 카드 집합 크기와 헤더 gridCount와
 * 동일 정의라 화면 간 숫자가 어긋나지 않는다. 검색 무입력 드롭다운("전체 지역")이 소비한다.
 */
public interface RegionGridCountProjection {

	String getRegionCode();

	String getRegionName();

	Integer getGridCount();
}
