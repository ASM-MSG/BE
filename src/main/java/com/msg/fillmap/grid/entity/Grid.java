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
 * 논리 식별자, 정수 인덱스, 행정동 라벨만 매핑한다. center_geom/bbox_geom·타임스탬프는 매핑하지 않는다
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

	@Column(name = "region_code", length = 10)
	private String regionCode;
}
