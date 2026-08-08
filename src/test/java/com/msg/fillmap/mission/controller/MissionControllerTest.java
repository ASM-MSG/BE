package com.msg.fillmap.mission.controller;

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

	@Test
	@DisplayName("active 조회는 200과 미션 리스트를 반환한다")
	void active_조회는_200과_미션_리스트를_반환한다() throws Exception {
		MissionResponseDto dto = new MissionResponseDto(
			31L, "THEME", "성수 카페 투어", 5, null, null,
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

	@Test
	@DisplayName("PATH의 line은 GeoJSON 객체로 직렬화된다")
	void PATH의_line은_GeoJSON_객체로_직렬화된다() throws Exception {
		String line = "{\"type\":\"LineString\",\"coordinates\":[[129.04,35.10],[129.05,35.11]]}";
		MissionResponseDto dto = new MissionResponseDto(
			12L, "COURSE", "남파랑길 3코스", 3, null, null,
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

	@Test
	@DisplayName("인증없이 호출하면 401이다")
	void 인증없이_호출하면_401이다() throws Exception {
		mockMvc.perform(get("/api/missions/active"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.developCode").value(2403));
	}
}
