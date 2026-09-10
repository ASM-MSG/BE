package com.msg.fillmap.region.service;

/**
 * 시군구 목록 항목 내부 뷰 (MSG-435). RegionNationalStatView(MSG-406) 선례대로 리포지토리 프로젝션을
 * 컨트롤러로 그대로 흘리지 않고 서비스가 이 뷰로 감싼다 — 응답 DTO 변환은 컨트롤러 책임.
 * gridCount 는 행정 경계 면적에서 산출된 전체 격자 수라 점령·방문과 무관한 사용자 무관 값이다.
 */
public record RegionDistrictView(
	String parentCode,
	String name,
	Long gridCount
) {
}
