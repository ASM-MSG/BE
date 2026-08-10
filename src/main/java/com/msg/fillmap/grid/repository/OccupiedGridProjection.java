package com.msg.fillmap.grid.repository;

/**
 * viewport 조회 네이티브 결과 프로젝션 (접근 A·B 공용). 색칠에 필요한 최소 필드만 노출한다.
 * 네이티브 쿼리는 컬럼 별칭을 gridId/gridY/gridX/regionName 으로 맞춘다.
 */
public interface OccupiedGridProjection {

	String getGridId();

	Integer getGridY();

	Integer getGridX();

	/**
	 * 격자 중심점이 속한 행정동 전체 이름 (grids.region_code 저장 라벨을 조인해 읽는다, MSG-349).
	 * 무귀속 격자면 null. 실경로가 아닌 접근 B(findOccupiedByIntersects)는 이 별칭을 select 하지 않는다 —
	 * 유일한 소비처인 벤치마크가 이 게터를 읽지 않으므로 조인을 더하지 않는다.
	 */
	String getRegionName();
}
