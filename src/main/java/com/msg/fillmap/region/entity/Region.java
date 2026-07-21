package com.msg.fillmap.region.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 행정동 마스터 row (regions). 수집률·행정동 검색(MSG-155/156)이 읽을 정적 데이터.
 * boundary_geom(GEOGRAPHY, NOT NULL)은 grid.entity.Grid 관례대로 엔티티에 매핑하지 않는다 —
 * write 는 native UPSERT(RegionRepository)로 하고, 공간 read 가 필요해지면 그때 JTS 로 매핑한다.
 * ddl-auto=validate 는 미매핑 컬럼을 문제 삼지 않는다.
 */
@Entity
@Table(name = "regions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Region {

	@Id
	@Column(name = "region_code", length = 10)
	private String regionCode;

	@Column(name = "region_name", length = 100, nullable = false)
	private String regionName;

	@Column(name = "parent_code", length = 10)
	private String parentCode;

	@Column(name = "total_grid_count", nullable = false)
	private int totalGridCount;
}
