package com.msg.fillmap.grid.entity;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * user_grids 복합 기본키 (user_id, grid_id) — 격자의 정체성은 유저×격자 쌍 그 자체다.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserGridId implements Serializable {

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "grid_id", nullable = false, length = 20)
	private String gridId;

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
