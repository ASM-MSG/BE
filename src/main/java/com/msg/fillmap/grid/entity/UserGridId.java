package com.msg.fillmap.grid.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * user_grids 복합 PK (user_id, grid_id) — {@link UserGrid}의 {@code @IdClass} (v6).
 * 필드명·타입은 {@link UserGrid}의 {@code @Id} 필드와 일치해야 한다.
 */
public class UserGridId implements Serializable {

	private Long userId;
	private String gridId;

	protected UserGridId() {
	}

	public UserGridId(Long userId, String gridId) {
		this.userId = userId;
		this.gridId = gridId;
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
