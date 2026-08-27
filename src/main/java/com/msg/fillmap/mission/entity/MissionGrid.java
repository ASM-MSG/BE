package com.msg.fillmap.mission.entity;

import java.util.Objects;

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

	/**
	 * 코스 포토스팟 표시 이름 (MSG-492 V43). 최종 표시 문자열 하나다 — 명소 이름·구역 표시명·행정동 이름 중
	 * 무엇이 채웠는지는 구분하지 않는다(출처 미저장, §D-3). 시더가 적재 시점에 계산해 넣고 조회는 통과만 한다(§D-2).
	 */
	@Column(name = "name", length = 100)
	private String name;

	/** 시드용 (MSG-224) — EVENT 는 순서 없는 유형이라 seq NULL 유지. */
	public MissionGrid(Long missionId, String gridId) {
		this(missionId, gridId, null);
	}

	/** 코스 시드용 (MSG-225) — seq 는 포토스팟 순번(1..N), MSG-222 조회가 seq순으로 마커를 낸다. */
	public MissionGrid(Long missionId, String gridId, Integer seq) {
		this(missionId, gridId, seq, null);
	}

	/** 코스 시드용 (MSG-492) — name 은 시더가 결정한 최종 표시 문자열. */
	public MissionGrid(Long missionId, String gridId, Integer seq, String name) {
		this.id = new MissionGridId(missionId, gridId);
		this.seq = seq;
		this.name = name;
	}

	public Long getMissionId() {
		return id.getMissionId();
	}

	public String getGridId() {
		return id.getGridId();
	}

	/**
	 * 표시 이름을 갱신한다 (MSG-492 §도메인 3, Mission.applyMetadata 와 같은 형태).
	 * 값이 실제로 달라질 때만 대입하고 바뀌었는지를 돌려준다 — 시더 재실행이 곧 백필이라 멱등해야 한다(FR-10).
	 *
	 * @return 값이 바뀌었으면 true
	 */
	public boolean applyName(String newName) {
		if (Objects.equals(this.name, newName)) {
			return false;
		}
		this.name = newName;
		return true;
	}
}
