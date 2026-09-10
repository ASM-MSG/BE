package com.msg.fillmap.badge.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 뱃지 마스터 row (badges, MSG-239). 시딩은 V9 마이그레이션이 하므로 쓰기 경로가 없다 — 읽기 전용 매핑.
 * condition_value(JSONB)는 매핑하지 않는다 — Grid.geom·Region.boundary_geom 관례대로 판정은 전부
 * native SQL 에서 (condition_value->>'value')::numeric 캐스트로 소비한다(§D5). ddl-auto=validate 는
 * 미매핑 컬럼을 문제 삼지 않는다.
 */
@Entity
@Table(name = "badges")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Badge {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "code", length = 50, nullable = false, unique = true)
	private String code;

	@Column(name = "name", length = 100, nullable = false)
	private String name;

	@Column(name = "description")
	private String description;

	@Column(name = "icon_url", nullable = false)
	private String iconUrl;

	@Enumerated(EnumType.STRING)
	@Column(name = "condition_type", length = 20, nullable = false)
	private BadgeConditionType conditionType;
}
