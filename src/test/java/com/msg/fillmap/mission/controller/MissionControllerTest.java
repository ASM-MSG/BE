package com.msg.fillmap.mission.controller;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.mission.dto.MissionDetailResponseDto;
import com.msg.fillmap.mission.dto.MissionDetailResponseDto.SpotStats;
import com.msg.fillmap.mission.dto.MissionProgressResponseDto;
import com.msg.fillmap.mission.dto.MissionResponseDto;
import com.msg.fillmap.mission.dto.MissionShape;
import com.msg.fillmap.mission.dto.MissionShape.Cell;
import com.msg.fillmap.mission.dto.MissionShape.CellsShape;
import com.msg.fillmap.mission.dto.MissionShape.PathShape;
import com.msg.fillmap.mission.dto.MissionShape.Spot;
import com.msg.fillmap.mission.entity.MissionType;
import com.msg.fillmap.mission.exception.MissionErrorCode;
import com.msg.fillmap.mission.service.MissionQueryService;
import com.msg.fillmap.user.entity.UserRole;

/**
 * 미션 조회 HTTP 계약 검증 (MissionController, MockMvc · MSG-222 §API 명세 → MSG-398 · Owner B).
 * 200+리스트·빈 배열·미인증 401·@JsonRawValue path 직렬화에 더해 MSG-398 의 컨트롤러 몫 검증
 * (bbox 누락 12400 · type 누락/비허용 12402 · 대소문자 무관 · /progress 파싱)을 본다. 서비스는 목이라
 * shape 합성·뷰포트 필터·좌표 검증은 서비스/통합 테스트 담당(MissionViewportFilterTest ·
 * MissionValidationHttpTest).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("MissionController")
class MissionControllerTest {

	private static final long USER_ID = 7222L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private MissionQueryService missionQueryService;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	/** 유효한 bbox 4종을 실은 /active 요청 — MSG-398 부터 type·bbox 가 필수라 계약 테스트도 전부 싣는다. */
	private static MockHttpServletRequestBuilder activeRequest(String type) {
		MockHttpServletRequestBuilder request = get("/api/missions/active")
			.param("swLat", "37.50").param("swLng", "127.00")
			.param("neLat", "37.55").param("neLng", "127.05");
		if (type != null) {
			request.param("type", type);
		}
		return request;
	}

	/** 메타데이터(MSG-383) 없는 미션 — 신규 8개 필드가 전부 null 인 이 티켓 이전 상태의 미션이다. */
	private static MissionResponseDto withoutMetadata(long missionId, String type, String title, int targetCount,
		MissionShape shape) {
		return new MissionResponseDto(missionId, type, title, targetCount, null, null, shape,
			null, null, null, null, null, null, null, null);
	}

	// 검증: FR-MISSION-01
	@Test
	@DisplayName("active 조회는 200과 미션 리스트를 반환한다")
	void active_조회는_200과_미션_리스트를_반환한다() throws Exception {
		MissionResponseDto dto = withoutMetadata(31L, "POPUP", "성수 팝업", 1,
			new CellsShape(List.of(new Cell("19422_9582", 37.478, 127.027))));
		given(missionQueryService.getMissionsInViewport(any(), eq(MissionType.POPUP))).willReturn(List.of(dto));

		mockMvc.perform(activeRequest("POPUP")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.length()").value(1))
			.andExpect(jsonPath("$.data[0].missionId").value(31))
			.andExpect(jsonPath("$.data[0].type").value("POPUP"))
			.andExpect(jsonPath("$.data[0].title").value("성수 팝업"))
			.andExpect(jsonPath("$.data[0].targetCount").value(1))
			.andExpect(jsonPath("$.data[0].shape.cells.length()").value(1))
			.andExpect(jsonPath("$.data[0].shape.cells[0].gridId").value("19422_9582"));
	}

	// 검증: FR-MISSION-01
	@Test
	@DisplayName("PATH의 line은 GeoJSON 객체로 직렬화된다")
	void PATH의_line은_GeoJSON_객체로_직렬화된다() throws Exception {
		String line = "{\"type\":\"LineString\",\"coordinates\":[[129.04,35.10],[129.05,35.11]]}";
		MissionResponseDto dto = withoutMetadata(12L, "COURSE", "남파랑길 3코스", 3,
			new PathShape(line, List.of(new Spot("16794_11404", 35.1005, 129.0415, 1))));
		given(missionQueryService.getMissionsInViewport(any(), eq(MissionType.COURSE))).willReturn(List.of(dto));

		mockMvc.perform(activeRequest("COURSE")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			// @JsonRawValue — line 이 문자열이 아니라 JSON 객체라 하위 필드로 접근된다(escape 없음).
			.andExpect(jsonPath("$.data[0].shape.line.type").value("LineString"))
			.andExpect(jsonPath("$.data[0].shape.line.coordinates.length()").value(2))
			.andExpect(jsonPath("$.data[0].shape.spots[0].gridId").value("16794_11404"))
			.andExpect(jsonPath("$.data[0].shape.spots[0].seq").value(1));
	}

	// 검증: FR-MISSION-02
	@Test
	@DisplayName("active가 없으면 200과 빈 배열이다")
	void active가_없으면_200과_빈_배열이다() throws Exception {
		given(missionQueryService.getMissionsInViewport(any(), any())).willReturn(List.of());

		mockMvc.perform(activeRequest("EVENT")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.length()").value(0));
	}

	// 검증: FR-MISSION-16
	@Test
	@DisplayName("활성 미션 응답에 메타데이터 필드가 실린다 — additive 변경 (MSG-383 §API 명세)")
	void 활성_미션_응답에_메타데이터_필드가_실린다() throws Exception {
		MissionResponseDto dto = new MissionResponseDto(
			12L, "COURSE", "남파랑길 3코스", 3, null, null,
			new PathShape(null, List.of(new Spot("16794_11404", 35.1005, 129.0415, 1))),
			"바다를 따라 걷는다\n전망대가 있다", "부산 영도구", "https://festival.example.kr",
			"매일 11:00 ~ 20:00", "https://cdn.fillmap.kr/mission/12.webp", 14000, 330, 2);
		given(missionQueryService.getMissionsInViewport(any(), eq(MissionType.COURSE))).willReturn(List.of(dto));

		mockMvc.perform(activeRequest("COURSE")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].description").value("바다를 따라 걷는다\n전망대가 있다"))
			.andExpect(jsonPath("$.data[0].placeName").value("부산 영도구"))
			.andExpect(jsonPath("$.data[0].sourceUrl").value("https://festival.example.kr"))
			.andExpect(jsonPath("$.data[0].operationTime").value("매일 11:00 ~ 20:00"))
			.andExpect(jsonPath("$.data[0].imageUrl").value("https://cdn.fillmap.kr/mission/12.webp"))
			.andExpect(jsonPath("$.data[0].distanceMeters").value(14000))
			.andExpect(jsonPath("$.data[0].durationMinutes").value(330))
			.andExpect(jsonPath("$.data[0].difficulty").value(2))
			// 기존 필드는 이름도 타입도 그대로다.
			.andExpect(jsonPath("$.data[0].missionId").value(12))
			.andExpect(jsonPath("$.data[0].targetCount").value(3));
	}

	// 검증: FR-MISSION-16
	@Test
	@DisplayName("메타데이터가 없는 미션은 필드가 null로 내려간다 — 필드 자체는 응답에 존재")
	void 메타데이터가_없는_미션은_필드가_null로_내려간다() throws Exception {
		// 값이 없다고 필드를 빼면 FE 가 "없는 필드"와 "빈 값"을 따로 분기해야 한다 (§API 명세).
		MissionResponseDto dto = withoutMetadata(31L, "POPUP", "성수 팝업", 1,
			new CellsShape(List.of(new Cell("19422_9582", 37.478, 127.027))));
		given(missionQueryService.getMissionsInViewport(any(), eq(MissionType.POPUP))).willReturn(List.of(dto));

		mockMvc.perform(activeRequest("POPUP")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0]", hasKey("description")))
			.andExpect(jsonPath("$.data[0]", hasKey("placeName")))
			.andExpect(jsonPath("$.data[0]", hasKey("sourceUrl")))
			.andExpect(jsonPath("$.data[0]", hasKey("operationTime")))
			.andExpect(jsonPath("$.data[0]", hasKey("imageUrl")))
			.andExpect(jsonPath("$.data[0]", hasKey("distanceMeters")))
			.andExpect(jsonPath("$.data[0]", hasKey("durationMinutes")))
			.andExpect(jsonPath("$.data[0]", hasKey("difficulty")))
			.andExpect(jsonPath("$.data[0].description").value(nullValue()))
			.andExpect(jsonPath("$.data[0].imageUrl").value(nullValue()))
			.andExpect(jsonPath("$.data[0].distanceMeters").value(nullValue()));
	}

	@Test
	@DisplayName("bbox 파라미터가 없으면 12400이다 — 스프링 기본 예외가 아니라 도메인 코드")
	void bbox_파라미터가_없으면_12400이다() throws Exception {
		mockMvc.perform(get("/api/missions/active")
				.param("type", "POPUP")
				.param("swLat", "37.50").param("swLng", "127.00").param("neLat", "37.55") // neLng 누락
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(12400));

		then(missionQueryService).should(never()).getMissionsInViewport(any(), any());
	}

	@Test
	@DisplayName("type이 없으면 12402이다 — 파라미터 없는 전국 조회 경로는 남지 않는다 (MSG-398 D5)")
	void type이_없으면_12402이다() throws Exception {
		mockMvc.perform(activeRequest(null)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(12402));

		then(missionQueryService).should(never()).getMissionsInViewport(any(), any());
	}

	@Test
	@DisplayName("칩이 없는 종류는 12402이다 — AREA·THEME·CONTINUOUS 는 노출하지 않는다")
	void 칩이_없는_종류는_12402이다() throws Exception {
		for (String hidden : List.of("AREA", "THEME", "CONTINUOUS", "FESTIVAL")) {
			mockMvc.perform(activeRequest(hidden)
					.header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(12402));
		}
		then(missionQueryService).should(never()).getMissionsInViewport(any(), any());
	}

	@Test
	@DisplayName("type은 대소문자를 가리지 않는다")
	void type은_대소문자를_가리지_않는다() throws Exception {
		given(missionQueryService.getMissionsInViewport(any(), eq(MissionType.POPUP))).willReturn(List.of());

		mockMvc.perform(activeRequest("popup")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200));

		then(missionQueryService).should().getMissionsInViewport(any(), eq(MissionType.POPUP));
	}

	@Test
	@DisplayName("progress 조회는 missionIds를 파싱해 내 진행도 리스트를 반환한다")
	void progress_조회는_missionIds를_파싱해_내_진행도_리스트를_반환한다() throws Exception {
		given(missionQueryService.getMyProgress(USER_ID, List.of(412L, 413L))).willReturn(List.of(
			new MissionProgressResponseDto(412L, 1, 1, true),
			new MissionProgressResponseDto(413L, 3, 0, false)));

		mockMvc.perform(get("/api/missions/progress")
				.param("missionIds", "412,413")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.length()").value(2))
			.andExpect(jsonPath("$.data[0].missionId").value(412))
			.andExpect(jsonPath("$.data[0].targetCount").value(1))
			.andExpect(jsonPath("$.data[0].filledCount").value(1))
			.andExpect(jsonPath("$.data[0].completed").value(true))
			.andExpect(jsonPath("$.data[1].missionId").value(413))
			.andExpect(jsonPath("$.data[1].filledCount").value(0))
			.andExpect(jsonPath("$.data[1].completed").value(false));
	}

	@Test
	@DisplayName("progress의 missionIds 원소가 숫자가 아니면 공통 400이다")
	void progress의_missionIds_원소가_숫자가_아니면_공통_400이다() throws Exception {
		mockMvc.perform(get("/api/missions/progress")
				.param("missionIds", "412,abc")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(400));
	}

	@Test
	@DisplayName("미인증 요청은 401이다 — 두 엔드포인트 모두 (MSG-398 D10)")
	void 미인증_요청은_401이다() throws Exception {
		mockMvc.perform(activeRequest("POPUP"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.developCode").value(2403));

		mockMvc.perform(get("/api/missions/progress").param("missionIds", "412"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.developCode").value(2403));
	}

	// --- 미션 상세 (MSG-399)

	// 검증: FR-MISSION-16
	@Test
	@DisplayName("미인증 상세 조회는 401이다 — 토큰 필수")
	void 미인증_상세_조회는_401이다() throws Exception {
		mockMvc.perform(get("/api/missions/412"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.developCode").value(2403));
	}

	// 검증: FR-MISSION-16
	@Test
	@DisplayName("미션 상세는 공통 응답으로 감싸 반환한다 — mission·progress·videoCount·spotStats 네 필드")
	void 미션_상세는_공통_응답으로_감싸_반환한다() throws Exception {
		MissionDetailResponseDto detail = new MissionDetailResponseDto(
			withoutMetadata(412L, "COURSE", "남파랑길 3코스", 3,
				new PathShape(null, List.of(new Spot("38677_114635", 35.1, 129.0, 1)))),
			new MissionProgressResponseDto(412L, 3, 2, false),
			19L,
			List.of(new SpotStats("38677_114635", true, 9)));
		given(missionQueryService.getMissionDetail(412L, USER_ID)).willReturn(detail);

		mockMvc.perform(get("/api/missions/412")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.mission.missionId").value(412))
			.andExpect(jsonPath("$.data.mission.type").value("COURSE"))
			.andExpect(jsonPath("$.data.mission.targetCount").value(3))
			.andExpect(jsonPath("$.data.progress.missionId").value(412))
			.andExpect(jsonPath("$.data.progress.filledCount").value(2))
			.andExpect(jsonPath("$.data.progress.completed").value(false))
			.andExpect(jsonPath("$.data.videoCount").value(19))
			.andExpect(jsonPath("$.data.spotStats.length()").value(1))
			.andExpect(jsonPath("$.data.spotStats[0].gridId").value("38677_114635"))
			.andExpect(jsonPath("$.data.spotStats[0].visited").value(true))
			.andExpect(jsonPath("$.data.spotStats[0].videoCount").value(9));
	}

	// 검증: FR-MISSION-16
	@Test
	@DisplayName("존재하지 않는 미션은 12404로 응답한다 — 단일 리소스라 빈 결과가 아니라 404")
	void 존재하지_않는_미션은_12404로_응답한다() throws Exception {
		given(missionQueryService.getMissionDetail(999L, USER_ID))
			.willThrow(new ApiException(MissionErrorCode.MISSION_NOT_FOUND));

		mockMvc.perform(get("/api/missions/999")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.developCode").value(12404));
	}

	// 검증: FR-MISSION-17
	@Test
	@DisplayName("코스가 아닌 미션의 spotStats는 빈 배열이다 — null이 아니다")
	void 코스가_아닌_미션의_spotStats는_빈_배열이다() throws Exception {
		MissionDetailResponseDto detail = new MissionDetailResponseDto(
			withoutMetadata(31L, "POPUP", "성수 팝업", 1,
				new CellsShape(List.of(new Cell("19422_9582", 37.478, 127.027)))),
			new MissionProgressResponseDto(31L, 1, 0, false),
			2L,
			List.of());
		given(missionQueryService.getMissionDetail(31L, USER_ID)).willReturn(detail);

		mockMvc.perform(get("/api/missions/31")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data", hasKey("spotStats")))
			.andExpect(jsonPath("$.data.spotStats").isArray())
			.andExpect(jsonPath("$.data.spotStats.length()").value(0))
			.andExpect(jsonPath("$.data.videoCount").value(2));
	}
}
