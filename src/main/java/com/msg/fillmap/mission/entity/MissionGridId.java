package com.msg.fillmap.mission.entity;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * mission_grids 복합 기본키 (mission_id, grid_id). UserGridId 관용 미러(§데이터 모델).
 * grid_id 는 논리 참조("{grid_y}_{grid_x}")라 grids 연관 없이 String 으로 둔다.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MissionGridId implements Serializable {

	@Column(name = "mission_id", nullable = false)
	private Long missionId;

	@Column(name = "grid_id", nullable = false, length = 20)
	private String gridId;

	public MissionGridId(Long missionId, String gridId) {
		this.missionId = missionId;
		this.gridId = gridId;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof MissionGridId that)) {
			return false;
		}
		return Objects.equals(missionId, that.missionId) && Objects.equals(gridId, that.gridId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(missionId, gridId);
	}
}
