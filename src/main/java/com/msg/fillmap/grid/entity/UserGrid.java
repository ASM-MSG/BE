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
 * v6: 서러게이트 id 제거, (user_id, grid_id) 복합 PK를 {@link UserGridId} {@code @EmbeddedId}로 매핑.
 * write는 native UPSERT({@code UserGridRepository.upsertOccupation})로 하므로 @Builder는 이 경로에서 쓰이지 않는다.
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
}
