package com.msg.fillmap.grid.entity;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * user_grids 복합 PK (user_id, grid_id) — {@link UserGrid}의 {@code @EmbeddedId} (v6).
 */
@Embeddable
public class UserGridId implements Serializable {

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "grid_id", nullable = false, length = 20)
	private String gridId;

	protected UserGridId() {
	}

	public UserGridId(Long userId, String gridId) {
		this.userId = userId;
		this.gridId = gridId;
	}

	public Long getUserId() {
		return userId;
	}

	public String getGridId() {
		return gridId;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof UserGridId that)) {
			return false;
		}
		return Objects.equals(userId, that.userId) && Objects.equals(gridId, that.gridId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(userId, gridId);
	}
}
