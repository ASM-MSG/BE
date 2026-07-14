package com.msg.fillmap.grid.repository;

/**
 * viewport 조회 네이티브 결과 프로젝션 (접근 A·B 공용). 색칠에 필요한 최소 필드만 노출한다.
 * 네이티브 쿼리는 컬럼 별칭을 gridId/gridY/gridX 로 맞춘다.
 */
public interface OccupiedGridProjection {

	String getGridId();

	Integer getGridY();

	Integer getGridX();
}
