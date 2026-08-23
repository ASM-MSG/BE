package com.msg.fillmap.mission.controller;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.msg.fillmap.hotzone.service.HotZoneService;
import com.msg.fillmap.mission.dto.MissionDetailResponseDto;
import com.msg.fillmap.mission.dto.MissionDetailResponseDto.SpotStats;
import com.msg.fillmap.mission.dto.MissionResponseDto;
import com.msg.fillmap.mission.dto.MissionShape.PathShape;
import com.msg.fillmap.mission.dto.MissionShape.Spot;
import com.msg.fillmap.mission.service.MissionQueryService;
import com.msg.fillmap.video.dto.GridVideoPageResponseDto;
import com.msg.fillmap.video.service.VideoService;

/**
 * 핫구역·미션 조회 5종의 인증 계약 (MSG-454). 지도 홈 상단 칩은 비로그인으로 보이고 로그인은 쓰기부터라는
 * MSG-439 원칙을 핫구역·미션에도 적용한 것이다. 서비스는 목이라 이 테스트의 변수는 SecurityConfig 등록과
 * 컨트롤러의 principal 처리뿐이다 — 열려야 할 것이 열렸는지와, 같은 깊이의 형제 경로(progress)·비GET이
 * 함께 열리지 않았는지를 같이 본다(광역 matcher 회귀 방지).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("핫구역·미션 조회 공개 접근 (SecurityConfig)")
class MissionPublicAccessHttpTest {

	private static final long MISSION_ID = 412L;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private HotZoneService hotZoneService;

	@MockitoBean
	private MissionQueryService missionQueryService;

	@MockitoBean
	private VideoService videoService;

	/** 익명 상세 — progress 는 null, spotStats.visited 는 전부 false 인 서비스 응답 (MSG-454 §미션 상세의 익명 계약). */
	private static MissionDetailResponseDto anonymousDetail() {
		MissionResponseDto mission = new MissionResponseDto(MISSION_ID, "COURSE", "남파랑길 3코스", 3, null, null,
			new PathShape(null, List.of(new Spot("38677_114635", 35.1, 129.0, 1))),
			null, null, null, null, null, null, null, null);
		return new MissionDetailResponseDto(mission, null, 19L,
			List.of(new SpotStats("38677_114635", false, 9)));
	}

	// 검증: FR-HOTZONE-07, FR-MISSION-01, FR-MISSION-20, FR-MISSION-16, FR-MISSION-17
	@Test
	@DisplayName("무인증으로 핫구역과 미션 조회 5종이 200으로 성공한다")
	void 무인증으로_핫구역과_미션_조회_5종이_200으로_성공한다() throws Exception {
		given(hotZoneService.getHotZones(any())).willReturn(List.of());
		given(missionQueryService.getMissionsInViewport(any(), any())).willReturn(List.of());
		given(missionQueryService.getMissionAggregates(any(), any(), any())).willReturn(List.of());
		// isNull() — 익명이면 컨트롤러가 userId 로 null 을 넘겨야 이 스텁이 잡힌다.
		given(missionQueryService.getMissionDetail(anyLong(), isNull())).willReturn(anonymousDetail());
		given(videoService.getMissionVideos(anyLong(), any(), anyInt()))
			.willReturn(new GridVideoPageResponseDto(List.of(), false, null));

		// 파라미터가 빠지면 인가와 무관하게 400 이라 유효 파라미터를 싣는다 (§성공 기준 1).
		mockMvc.perform(get("/api/hotzones")
				.param("swLat", "37.50").param("swLng", "127.00")
				.param("neLat", "37.55").param("neLng", "127.05"))
			.andExpect(status().isOk());
		mockMvc.perform(get("/api/missions/active")
				.param("type", "POPUP")
				.param("swLat", "37.50").param("swLng", "127.00")
				.param("neLat", "37.55").param("neLng", "127.05"))
			.andExpect(status().isOk());
		mockMvc.perform(get("/api/missions/aggregation")
				.param("type", "POPUP").param("unit", "SIGUNGU")
				.param("swLat", "35.10").param("swLng", "128.90")
				.param("neLat", "35.30").param("neLng", "129.20"))
			.andExpect(status().isOk());
		mockMvc.perform(get("/api/missions/{missionId}", MISSION_ID)).andExpect(status().isOk());
		mockMvc.perform(get("/api/missions/{missionId}/videos", MISSION_ID)).andExpect(status().isOk());
	}

	// 검증: FR-MISSION-18
	@Test
	@DisplayName("무인증 미션 진행도 조회는 401로 거절된다 (사용자별 값은 열지 않는다)")
	void 무인증_미션_진행도_조회는_401로_거절된다() throws Exception {
		// 상세 matcher 를 제약 없는 /api/missions/* 로 넓히면 progress 까지 잡혀 이 테스트가 깨진다.
		mockMvc.perform(get("/api/missions/progress").param("missionIds", "412"))
			.andExpect(status().isUnauthorized());
	}

	// 검증: FR-MISSION-25
	@Test
	@DisplayName("비로그인으로도_역조회할_수_있다 — 격자가 대표 격자인 미션 조회 (MSG-459)")
	void 비로그인으로도_역조회할_수_있다() throws Exception {
		given(missionQueryService.getMissionsByGrid(any())).willReturn(List.of());

		mockMvc.perform(get("/api/grids/{gridId}/missions", "19443_9582"))
			.andExpect(status().isOk());
	}

	// 검증: FR-MISSION-17
	@Test
	@DisplayName("비로그인 미션 경유 업로드는 401이다 (같은 경로에서 GET 만 열려 있다)")
	void 비로그인_업로드는_401이다() throws Exception {
		// MSG-459 로 이 경로에 실제 업로드 POST 가 생겼다 — GET 한정 matcher 가 계약이라는 기존 주석이
		// 여기서 값을 한다. 문자열 패턴으로 열려 있었다면 업로드가 익명에 함께 풀렸을 자리다.
		mockMvc.perform(post("/api/missions/{missionId}/videos", MISSION_ID))
			.andExpect(status().isUnauthorized());
	}

	// 검증: FR-MISSION-16
	@Test
	@DisplayName("공개 GET에서도 무효 토큰은 2401로 거절된다 (토큰 검증 성질 보존)")
	void 공개_GET에서도_무효_토큰은_2401로_거절된다() throws Exception {
		mockMvc.perform(get("/api/missions/{missionId}", MISSION_ID)
				.header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.developCode").value(2401));
	}

	// 검증: FR-MISSION-16
	@Test
	@DisplayName("무인증 미션 상세는 progress 키가 있고 값이 null이다 (와이어 계약)")
	void 무인증_미션_상세는_progress_키가_있고_값이_null이다() throws Exception {
		given(missionQueryService.getMissionDetail(anyLong(), isNull())).willReturn(anonymousDetail());

		mockMvc.perform(get("/api/missions/{missionId}", MISSION_ID))
			.andExpect(status().isOk())
			// 키는 남고 값만 null 이다 — 전역 NON_NULL 직렬화가 끼어들어 키가 사라지면 여기서 깨진다.
			.andExpect(jsonPath("$.data", hasKey("progress")))
			.andExpect(jsonPath("$.data.progress").value(nullValue()))
			.andExpect(jsonPath("$.data.spotStats[*].visited", everyItem(is(false))))
			// 전역 값은 익명에도 그대로 나간다.
			.andExpect(jsonPath("$.data.mission.missionId").value(MISSION_ID))
			.andExpect(jsonPath("$.data.videoCount").value(19));
	}
}
