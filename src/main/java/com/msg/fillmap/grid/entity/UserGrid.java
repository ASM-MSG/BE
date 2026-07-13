package com.msg.fillmap.grid.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 개인 도감 — 사용자가 점령한 격자 (user_grids). 첫 방문 = 점령 = 이 row 생성.
 * PK는 (user_id, grid_id) 복합키 — 같은 쌍의 row는 하나만 존재한다 (v6, MSG-66).
 */
@Entity
@Table(name = "user_grids")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserGrid {

	@EmbeddedId
	private UserGridId id;

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
		this.id = new UserGridId(userId, gridId);
		this.coverVideoId = coverVideoId;
		this.videoCount = 1;
	}

	public Long getUserId() {
		return id.getUserId();
	}

	public String getGridId() {
		return id.getGridId();
	}
}
