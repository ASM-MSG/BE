package com.msg.fillmap.region.repository;

/**
 * 역지오코딩 네이티브 조회 결과 프로젝션 (MSG-93). 검색 UX 에 필요한 최소 컬럼만 노출한다 —
 * boundary_geom 은 select 하지 않는다(무겁고 불필요, WHERE 절에서만 사용). 네이티브 쿼리는 별칭을
 * regionCode/regionName/parentCode 로 맞춘다.
 */
public interface RegionProjection {

	String getRegionCode();

	String getRegionName();

	String getParentCode();
}
