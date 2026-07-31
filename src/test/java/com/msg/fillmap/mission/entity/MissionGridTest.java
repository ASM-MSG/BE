package com.msg.fillmap.mission.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 시드 생성자 검증 (MSG-225 모듈 2, 순수 단위) — seq 포함 생성자(코스 포토스팟 순번)와
 * 기존 2인자 생성자(EVENT, seq NULL)의 공존을 본다.
 */
@DisplayName("MissionGrid 시드 생성자 — seq 순번")
class MissionGridTest {

	@Test
	void seq_생성자가_스팟_순번을_기록한다() {
		MissionGrid spot = new MissionGrid(7L, "39001_112198", 3);

		assertThat(spot.getMissionId()).isEqualTo(7L);
		assertThat(spot.getGridId()).isEqualTo("39001_112198");
		assertThat(spot.getSeq()).isEqualTo(3);
	}
}
