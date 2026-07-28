package com.msg.fillmap.zone.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 격자 표시명 구역(zone) row (zones, MSG-234). grid_y/grid_x 정수 사각형이라 geometry 컬럼이 없어
 * 전 컬럼을 JPA 로 매핑한다(Grid/Region 이 geometry 때문에 부분 매핑한 것과 대조 — 여기선 회피할 컬럼이 없음).
 * zone_key 는 재시딩·환경 무관 안정 식별자(자연키·타이브레이크), name 은 비유일 사람용 표시명.
 */
@Entity
@Table(name = "zones")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Zone {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "zone_key", length = 30, nullable = false, unique = true)
	private String zoneKey;

	@Column(name = "name", length = 50, nullable = false)
	private String name;

	@Column(name = "region_code", length = 10)
	private String regionCode;

	@Column(name = "min_grid_y", nullable = false)
	private Integer minGridY;

	@Column(name = "max_grid_y", nullable = false)
	private Integer maxGridY;

	@Column(name = "min_grid_x", nullable = false)
	private Integer minGridX;

	@Column(name = "max_grid_x", nullable = false)
	private Integer maxGridX;

	@Column(name = "priority", nullable = false)
	private Integer priority;

	@Builder
	private Zone(String zoneKey, String name, String regionCode, Integer minGridY, Integer maxGridY,
		Integer minGridX, Integer maxGridX, Integer priority) {
		this.zoneKey = zoneKey;
		this.name = name;
		this.regionCode = regionCode;
		this.minGridY = minGridY;
		this.maxGridY = maxGridY;
		this.minGridX = minGridX;
		this.maxGridX = maxGridX;
		this.priority = priority == null ? 0 : priority;
	}
}
