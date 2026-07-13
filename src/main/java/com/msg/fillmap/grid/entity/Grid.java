package com.msg.fillmap.grid.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 전역 격자 등록(grids) — row 존재 = "누군가 영상을 올린 격자" (lazy insert).
 * write는 native UPSERT({@code GridRepository.registerIfAbsent})로만 수행하므로 JPA 생성자를 두지 않는다.
 * grid_y / grid_x(뷰포트 스캔용) · center_geom / bbox_geom은 본 티켓에 읽기 요구가 없어 매핑하지 않는다
 * (v6 §grids, MSG-73에서 추가). region_code는 v6에서 grids에 없다 — 행정동 레이블은 videos로 이동.
 */
@Entity
@Table(name = "grids")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Grid {

	@Id
	@Column(name = "grid_id", length = 20)
	private String gridId;

	@Column(name = "first_seen_at", nullable = false)
	private LocalDateTime firstSeenAt;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;
}
