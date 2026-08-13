package com.msg.fillmap.mission.controller;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
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

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.mission.dto.MissionResponseDto;
import com.msg.fillmap.mission.dto.MissionShape;
import com.msg.fillmap.mission.dto.MissionShape.Cell;
import com.msg.fillmap.mission.dto.MissionShape.CellsShape;
import com.msg.fillmap.mission.dto.MissionShape.PathShape;
import com.msg.fillmap.mission.dto.MissionShape.Spot;
import com.msg.fillmap.mission.service.MissionQueryService;
import com.msg.fillmap.user.entity.UserRole;

/**
 * 활성 미션 조회 HTTP 계약 검증 (MissionController, MockMvc · MSG-222 §API 명세 · Owner B). 200+리스트·빈 배열·
 * 미인증 401 과 @JsonRawValue 로 path 가 escape 없이 JSON 객체로 직렬화되는지 검증한다. 서비스는 목이라 shape
 * 합성 자체는 서비스 테스트 담당.
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
		MissionResponseDto dto = withoutMetadata(31L, "THEME", "성수 카페 투어", 5,
			new CellsShape(List.of(new Cell("19422_9582", 37.478, 127.027))));
		given(missionQueryService.getActiveMissions()).willReturn(List.of(dto));

		mockMvc.perform(get("/api/missions/active")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.length()").value(1))
			.andExpect(jsonPath("$.data[0].missionId").value(31))
			.andExpect(jsonPath("$.data[0].type").value("THEME"))
			.andExpect(jsonPath("$.data[0].title").value("성수 카페 투어"))
			.andExpect(jsonPath("$.data[0].targetCount").value(5))
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
		given(missionQueryService.getActiveMissions()).willReturn(List.of(dto));

		mockMvc.perform(get("/api/missions/active")
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
		given(missionQueryService.getActiveMissions()).willReturn(List.of());

		mockMvc.perform(get("/api/missions/active")
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
		given(missionQueryService.getActiveMissions()).willReturn(List.of(dto));

		mockMvc.perform(get("/api/missions/active")
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
		MissionResponseDto dto = withoutMetadata(31L, "THEME", "성수 카페 투어", 5,
			new CellsShape(List.of(new Cell("19422_9582", 37.478, 127.027))));
		given(missionQueryService.getActiveMissions()).willReturn(List.of(dto));

		mockMvc.perform(get("/api/missions/active")
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
	@DisplayName("인증없이 호출하면 401이다")
	void 인증없이_호출하면_401이다() throws Exception {
		mockMvc.perform(get("/api/missions/active"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.developCode").value(2403));
	}
}
