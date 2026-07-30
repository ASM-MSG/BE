package com.msg.fillmap.mission.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 미션 정의 (missions, MSG-166 V6). MSG-222 는 조회 전용이라 최소 매핑만 둔다 — INSERT/UPDATE 없음(시드는
 * MSG-224/225/235). path 는 코스 표시용 GeoJSON LineString jsonb 원문을 String 으로 읽어(§D7) FE 로 그대로
 * passthrough 한다. start_at/end_at NULL = 무기간(상시, 코스).
 */
@Entity
@Table(name = "missions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Mission {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, length = 20)
	private MissionType type;

	@Column(name = "title", nullable = false, length = 200)
	private String title;

	@Column(name = "region_code", length = 10)
	private String regionCode;

	@Column(name = "start_at")
	private LocalDateTime startAt;

	@Column(name = "end_at")
	private LocalDateTime endAt;

	@Column(name = "target_count", nullable = false)
	private Integer targetCount;

	/** 코스 표시용 GeoJSON LineString jsonb 원문 (코스 외 NULL, chk_missions_path). raw 문자열로 읽어 재직렬화 없이 발행(§D7). */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "path", columnDefinition = "jsonb")
	private String path;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;
}
