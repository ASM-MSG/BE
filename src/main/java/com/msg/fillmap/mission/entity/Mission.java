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
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 미션 정의 (missions, MSG-166 V6). MSG-222 는 조회 전용 최소 매핑, MSG-224 가 시드용 쓰기 경로(빌더)를
 * 더한다 — region_code(AREA 전용)·path(COURSE 전용, chk_missions_path)는 시드에서 NULL 고정이라 빌더에서
 * 제외. path 는 코스 표시용 GeoJSON LineString jsonb 원문을 String 으로 읽어(§D7) FE 로 그대로
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

	/** insertable=false — DB DEFAULT(CURRENT_TIMESTAMP) 위임. save 직후엔 null, 재조회 시 채워진다(MSG-224). */
	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private LocalDateTime createdAt;

	@Builder
	private Mission(MissionType type, String title, LocalDateTime startAt, LocalDateTime endAt, Integer targetCount) {
		this.type = type;
		this.title = title;
		this.startAt = startAt;
		this.endAt = endAt;
		this.targetCount = targetCount;
	}
}
