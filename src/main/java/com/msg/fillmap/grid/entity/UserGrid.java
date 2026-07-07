package com.msg.fillmap.grid.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.CreationTimestamp;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 개인 도감 — 사용자가 점령한 격자 (user_grids). 첫 방문 = 점령 = 이 row 생성.
 */
@Entity
@Table(name = "user_grids", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "grid_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserGrid {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "grid_id", nullable = false, length = 20)
	private String gridId;

	@CreationTimestamp
	@Column(name = "first_collected_at", nullable = false, updatable = false)
	private LocalDateTime firstCollectedAt;

	@CreationTimestamp
	@Column(name = "last_uploaded_at", nullable = false)
	private LocalDateTime lastUploadedAt;

	@Column(name = "video_count", nullable = false)
	private Integer videoCount;

	@Column(name = "cover_video_id", nullable = true)
	private Long coverVideoId;

	@Builder
	private UserGrid(Long userId, String gridId, Long coverVideoId) {
		this.userId = userId;
		this.gridId = gridId;
		this.coverVideoId = coverVideoId;
		this.videoCount = 1;
	}
}
