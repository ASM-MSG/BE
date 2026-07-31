package com.msg.fillmap.mission.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 미션 판정 대상 격자 (mission_grids, MSG-166 V6). 코스는 포토스팟 5~8곳만 담고 seq 로 순번을 갖는다(순서 없는
 * 유형은 seq NULL). MSG-222 는 조회 전용 — shape 합성 시 gridId 를 GridEncoder 로 좌표 변환한다(§도메인 3).
 */
@Entity
@Table(name = "mission_grids")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MissionGrid {

	@EmbeddedId
	private MissionGridId id;

	/** 코스 포토스팟 순번(1..N), 순서 없는 유형은 NULL. */
	@Column(name = "seq")
	private Integer seq;

	/** 시드용 (MSG-224) — EVENT 는 순서 없는 유형이라 seq NULL 유지. */
	public MissionGrid(Long missionId, String gridId) {
		this.id = new MissionGridId(missionId, gridId);
	}

	public Long getMissionId() {
		return id.getMissionId();
	}

	public String getGridId() {
		return id.getGridId();
	}
}
