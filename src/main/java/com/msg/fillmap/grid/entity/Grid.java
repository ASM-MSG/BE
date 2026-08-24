package com.msg.fillmap.grid.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 전역 격자 등록 row (grids). 조회 전용 최소 매핑 — 색칠 응답(MSG-73)에 필요한
 * 논리 식별자·정수 인덱스만 매핑한다. center_geom/bbox_geom·타임스탬프는 매핑하지 않는다
 * (ddl-auto=validate 는 미매핑 컬럼을 문제 삼지 않는다. 지오메트리는 접근 B 네이티브 쿼리로 처리).
 */
@Entity
@Table(name = "grids")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Grid {

	@Id
	@Column(name = "grid_id", length = 20)
	private String gridId;

	@Column(name = "grid_y", nullable = false)
	private Integer gridY;

	@Column(name = "grid_x", nullable = false)
	private Integer gridX;

	/**
	 * 격자 중심점이 속한 행정동 라벨 (MSG-167 저장 컬럼, MSG-466 에서 읽기 전용 매핑). 쓰기는 종전대로
	 * native upsert 경로가 하므로 insert·update 에서 뺀다 — 이 매핑은 행정 단위 집계의 그룹핑 키를
	 * JPQL 로 읽기 위한 것뿐이다. 무귀속(해상 등)은 NULL 이다.
	 */
	@Column(name = "region_code", length = 10, insertable = false, updatable = false)
	private String regionCode;
}
