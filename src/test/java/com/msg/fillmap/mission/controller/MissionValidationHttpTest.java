package com.msg.fillmap.mission.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.user.entity.UserRole;

/**
 * 미션 조회 bbox·진행도 상한 검증의 HTTP 계약 (실 서비스 빈 체인 · MSG-398 D6·D7 · Owner B).
 * MissionControllerTest 는 서비스를 목으로 두어 컨트롤러 몫(누락 12400·type 12402)만 보고, 서비스 안의
 * 좌표 유효성 → 뒤집힘 → span 상한 순서와 진행도 상한은 여기서 실제 체인으로 본다. 검증은 스냅샷 재계산
 * 앞에서 끝나므로 합성 데이터가 필요 없다(공유 로컬 DB 의 시드와 무관하게 결정적).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("미션 조회 뷰포트·진행도 HTTP 검증 (실 서비스)")
class MissionValidationHttpTest {

	private static final long USER_ID = 7398L;
	private static final long OTHER_USER_ID = 7399L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	private final ObjectMapper objectMapper = new ObjectMapper();

	private String bearer(long userId) {
		return "Bearer " + tokenProvider.issueAccessToken(userId, UserRole.USER);
	}

	private MockHttpServletRequestBuilder active(String swLat, String swLng, String neLat, String neLng) {
		return get("/api/missions/active")
			.param("type", "POPUP")
			.param("swLat", swLat).param("swLng", swLng)
			.param("neLat", neLat).param("neLng", neLng)
			.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID));
	}

	@Test
	@DisplayName("뒤집힌 bbox는 12400이다")
	void 뒤집힌_bbox는_12400이다() throws Exception {
		mockMvc.perform(active("37.55", "127.00", "37.50", "127.05"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(12400));
	}

	@Test
	@DisplayName("좌표가 WGS84 범위 밖이면 12400이다 — 위도 100 같은 유한 오류값")
	void 좌표가_WGS84_범위_밖이면_12400이다() throws Exception {
		mockMvc.perform(active("100.0", "127.00", "100.5", "127.05"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(12400));
	}

	@Test
	@DisplayName("NaN 좌표는 12400이다 — 검증이 fail-open으로 통과하지 않는다")
	void NaN_좌표는_12400이다() throws Exception {
		// NaN 은 어떤 비교에도 false 라 좌표 유효성 검사가 맨 앞에 없으면 뒤집힘·상한 검사를 그냥 통과한다(D6).
		mockMvc.perform(active("NaN", "127.00", "37.55", "127.05"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(12400));
	}

	@Test
	@DisplayName("한 변이 0점5도를 넘으면 12401이다")
	void 한_변이_0점5도를_넘으면_12401이다() throws Exception {
		mockMvc.perform(active("37.00", "127.00", "37.51", "127.05"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(12401));
	}

	@Test
	@DisplayName("정확히 0점5도는 허용된다")
	void 정확히_0점5도는_허용된다() throws Exception {
		mockMvc.perform(active("37.00", "127.00", "37.50", "127.50"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200));
	}

	@Test
	@DisplayName("상한을 넘는 요청은 잘라서 응답하지 않는다 — 중심 기준 축소 후 200 금지 (D6 기각안 2)")
	void 상한을_넘는_요청은_잘라서_응답하지_않는다() throws Exception {
		mockMvc.perform(active("33.00", "124.00", "38.50", "132.00"))
			.andExpect(status().isBadRequest())
			// 잘라서 응답했다면 data 가 배열로 실린다 — 거절 응답은 data 가 null 이다.
			.andExpect(jsonPath("$.data").value(Matchers.nullValue()));
	}

	@Test
	@DisplayName("상한을 넘는 요청은 빈 목록 200이 아니다 — '진짜 없음'과 와이어에서 갈린다 (D6 기각안 1)")
	void 상한을_넘는_요청은_빈_목록_200이_아니다() throws Exception {
		mockMvc.perform(active("33.00", "124.00", "38.50", "132.00"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(12401));
	}

	// 검증: FR-MISSION-02
	@Test
	@DisplayName("목록 응답에 사용자별 값이 없다 — 다른 두 사용자의 응답이 글자 단위로 같다")
	void 목록_응답에_사용자별_값이_없다() throws Exception {
		MockHttpServletRequestBuilder request = get("/api/missions/active")
			.param("type", "POPUP")
			.param("swLat", "37.45").param("swLng", "126.95")
			.param("neLat", "37.60").param("neLng", "127.10");

		String first = mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		String second = mockMvc.perform(get("/api/missions/active")
				.param("type", "POPUP")
				.param("swLat", "37.45").param("swLng", "126.95")
				.param("neLat", "37.60").param("neLng", "127.10")
				.header(HttpHeaders.AUTHORIZATION, bearer(OTHER_USER_ID)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		assertThat(second).isEqualTo(first);
	}

	@Test
	@DisplayName("생성된 OpenAPI 문서에서 bbox와 type이 필수다 — @Parameter(required = true) 누락 회귀")
	void 생성된_OpenAPI_문서에서_bbox와_type이_필수다() throws Exception {
		String body = mockMvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		JsonNode docs = objectMapper.readTree(body);

		JsonNode activeParameters = docs.path("paths").path("/api/missions/active").path("get").path("parameters");
		assertThat(activeParameters.isArray()).as("/active 파라미터 선언이 없다").isTrue();
		for (String name : new String[] {"type", "swLat", "swLng", "neLat", "neLng"}) {
			JsonNode parameter = findParameter(activeParameters, name);
			assertThat(parameter.path("required").asBoolean(false))
				.as("문서가 %s 를 선택이라 말하면 클라이언트가 12400/12402 를 받는다 (§API 명세)", name)
				.isTrue();
		}

		// /progress 의 missionIds 는 반대로 문서에서도 선택이다 — 없으면 빈 배열이 정의된 동작(D7).
		JsonNode progressParameters =
			docs.path("paths").path("/api/missions/progress").path("get").path("parameters");
		assertThat(findParameter(progressParameters, "missionIds").path("required").asBoolean(false)).isFalse();
	}

	private JsonNode findParameter(JsonNode parameters, String name) {
		for (JsonNode parameter : parameters) {
			if (name.equals(parameter.path("name").asString(""))) {
				return parameter;
			}
		}
		throw new AssertionError("OpenAPI 문서에 파라미터가 없다: " + name);
	}

	@Test
	@DisplayName("missionIds가 없으면 빈 배열 200이다")
	void missionIds가_없으면_빈_배열_200이다() throws Exception {
		mockMvc.perform(get("/api/missions/progress")
				.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.length()").value(0));
	}

	@Test
	@DisplayName("300개를 넘으면 12403이다 — 조용히 잘라내면 잘린 카드가 진행 0으로 그려진다 (D7)")
	void 삼백개를_넘으면_12403이다() throws Exception {
		mockMvc.perform(get("/api/missions/progress")
				.param("missionIds", joinedIds(301))
				.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(12403));
	}

	@Test
	@DisplayName("정확히 300개는 허용된다")
	void 정확히_300개는_허용된다() throws Exception {
		mockMvc.perform(get("/api/missions/progress")
				.param("missionIds", joinedIds(300))
				.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200));
	}

	/** 존재하지 않는 큰 id 대역 count 개 — 공유 로컬 DB 의 실데이터와 겹치지 않는다. */
	private static String joinedIds(int count) {
		return LongStream.range(900_000_000L, 900_000_000L + count)
			.mapToObj(Long::toString)
			.collect(Collectors.joining(","));
	}
}
