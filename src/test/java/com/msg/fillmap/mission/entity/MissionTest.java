package com.msg.fillmap.mission.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 시드 빌더 확장 검증 (MSG-225 모듈 2, 순수 단위). path 필드 추가(코스 전용)와 기존 축제 경로(path 미지정)의
 * 회귀 무해성을 본다 — chk_missions_path 가 COURSE 만 path 를 허용하므로 EVENT 는 null 유지가 필수다.
 */
@DisplayName("Mission 시드 빌더 — path 확장·축제 회귀")
class MissionTest {

	private static final String LINE = """
		{"type": "LineString", "coordinates": [[129.03597, 35.09656], [129.03642, 35.09721]]}""";

	@Test
	void 빌더로_path를_가진_COURSE_미션을_만들_수_있다() {
		Mission mission = Mission.builder()
			.type(MissionType.COURSE)
			.title("남파랑길 3코스")
			.targetCount(3)
			.source("DURUNUBI")
			.path(LINE)
			.build();

		assertThat(mission.getType()).isEqualTo(MissionType.COURSE);
		assertThat(mission.getPath()).isEqualTo(LINE);
		// 무기간(FR-9) — startAt/endAt 미지정 = NULL.
		assertThat(mission.getStartAt()).isNull();
		assertThat(mission.getEndAt()).isNull();
	}

	@Test
	void EVENT_시드_경로는_path_없이_그대로_동작한다() {
		Mission mission = Mission.builder()
			.type(MissionType.EVENT)
			.title("한강 여름 축제")
			.targetCount(1)
			.source("FESTIVAL")
			.build();

		// 축제 회귀 — 빌더 확장 후에도 path 는 null(chk_missions_path: COURSE 외 path 금지).
		assertThat(mission.getPath()).isNull();
		assertThat(mission.getType()).isEqualTo(MissionType.EVENT);
	}
}
