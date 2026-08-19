package com.msg.fillmap.region.repository;

/**
 * 시군구 목록 네이티브 조회 결과 프로젝션 (MSG-435). RegionNationalStatProjection(MSG-406) 선례대로
 * 인터페이스 프로젝션이라 엔티티를 만들지 않는다. gridCount 는 SUM 결과라 bigint — Long 으로 받는다.
 * 사용자 무관 값이고, 격자 수가 0 인 시군구는 쿼리(HAVING)에서 이미 빠진다.
 */
public interface RegionDistrictProjection {

	/** 시군구 식별자 = 행정동 코드 앞 5자리(regions.parent_code). GET /api/regions/stats 의 parentCode 로 그대로 쓴다. */
	String getParentCode();

	/** 시군구 이름. 전체 경로 이름("부산광역시 부산진구 부전2동")의 둘째 토큰. */
	String getName();

	/** 그 시군구 산하 행정동들의 regions.total_grid_count 합. 항상 0 초과. */
	Long getGridCount();
}
