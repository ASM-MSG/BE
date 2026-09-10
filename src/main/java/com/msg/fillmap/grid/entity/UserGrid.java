package com.msg.fillmap.grid.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

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

	@Column(name = "first_collected_at", nullable = false, updatable = false)
	private LocalDateTime firstCollectedAt;

	@Column(name = "last_uploaded_at", nullable = false)
	private LocalDateTime lastUploadedAt;

	@Column(name = "video_count", nullable = false)
	private Integer videoCount;

	@Column(name = "cover_video_id", nullable = true)
	private Long coverVideoId;

	/**
	 * 복합키의 grid_id 컬럼을 그대로 쓰는 읽기 전용 연관 (MSG-585). 키는 UserGridId 가 소유하고 이 연관은
	 * JPQL 조인(ug.grid)용이다 — user_grids 쓰기는 native upsert 라 insert·update 에서 뺀다.
	 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "grid_id", insertable = false, updatable = false)
	private Grid grid;

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
