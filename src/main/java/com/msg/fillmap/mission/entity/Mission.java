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
 * 더하고 MSG-225 가 빌더에 path 를 확장한다 — path 는 코스 시더 전용(chk_missions_path 가 COURSE 만 허용,
 * 타 시더는 미지정=NULL), region_code(AREA 전용)는 시드에서 NULL 고정이라 빌더에서 제외. path 는 코스
 * 표시용 GeoJSON LineString jsonb 원문을 String 으로 읽어(§D7) FE 로 그대로 passthrough 한다.
 * start_at/end_at NULL = 무기간(상시, 코스).
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

	/**
	 * 적재 출처 (V13, D7) — NULL = 수동/미상. String 유지(enum 금지): 공유 엔티티라 후속 소스(POPUP 등)가
	 * 상수 추가를 잊으면 기존 조회 경로 전체가 역직렬화로 터진다. 값 상수는 각 러너가 보유한다.
	 */
	@Column(name = "source", length = 30)
	private String source;

	/** insertable=false — DB DEFAULT(CURRENT_TIMESTAMP) 위임. save 직후엔 null, 재조회 시 채워진다(MSG-224). */
	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private LocalDateTime createdAt;

	@Builder
	private Mission(MissionType type, String title, LocalDateTime startAt, LocalDateTime endAt, Integer targetCount,
		String source, String path) {
		this.type = type;
		this.title = title;
		this.startAt = startAt;
		this.endAt = endAt;
		this.targetCount = targetCount;
		this.source = source;
		// path 는 코스(MSG-225) 전용 — chk_missions_path 가 COURSE 외 path 를 거부하므로 타 시더는 미지정(null).
		this.path = path;
	}
}
