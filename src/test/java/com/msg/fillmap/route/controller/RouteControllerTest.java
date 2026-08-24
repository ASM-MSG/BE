package com.msg.fillmap.route.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.user.entity.UserRole;

/**
 * AI 경로 추천 엔드포인트 (MSG-457) — 기본 설정(route.ai.enabled=false) 그대로 기동한다. 프로퍼티를 켜지
 * 않는 것이 이 테스트의 요점이다: 컨텍스트 기동 성공 = RouteIntentClient 빈 없이도 상시 빈(컨트롤러·
 * 서비스)이 서는 ObjectProvider 구성의 증명이고(직주입이면 로드부터 깨진다), 호출은 404 가 아니라 14503
 * 명시적 비활성 응답이어야 한다 (§설정).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("RouteController — AI 경로 추천 (기본 설정: 플래그 꺼짐)")
class RouteControllerTest {

	private static final long USER_ID = 42L;
	private static final String URL = "/api/routes/recommend";
	private static final String 유효한_뷰포트 =
		"{\"minLat\": 35.05, \"minLng\": 128.95, \"maxLat\": 35.25, \"maxLng\": 129.20}";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	// 검증: NFR-OPS-06
	@Test
	@DisplayName("플래그가 꺼지면 기동이 성공하고 호출은 503 + 14503 명시적 비활성 응답이다")
	void 플래그가_꺼지면_기동이_성공하고_명시적_비활성_응답이다() throws Exception {
		mockMvc.perform(post(URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"text\": \"부산역 내려서 해운대 축제 보고 싶어\", \"viewport\": " + 유효한_뷰포트 + "}"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.developCode").value(14503));
	}

	// 검증: FR-ROUTE-01
	@Test
	@DisplayName("공백 딸린 500자 문장은 거부되지 않는다 — 길이 검증은 trim 후 값 기준이다 (스펙 \"trim 후 1~500자\")")
	void 공백_딸린_500자_문장은_거부되지_않는다() throws Exception {
		String text = "  " + "가".repeat(500) + "  ";	// trim 후 정확히 500자 — 원문 기준이면 504자로 400 이 난다

		mockMvc.perform(post(URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"text\": \"" + text + "\", \"viewport\": " + 유효한_뷰포트 + "}"))
			.andExpect(status().isServiceUnavailable())	// 검증을 통과해 서비스(플래그 게이트 14503)까지 도달했다
			.andExpect(jsonPath("$.developCode").value(14503));
	}

	// 검증: FR-ROUTE-01
	@Test
	@DisplayName("범위 밖 origin 은 공통 400 이다 — 필드 단위 @Valid 라 text 와 같은 경로 (§API)")
	void 범위_밖_origin은_공통_400이다() throws Exception {
		mockMvc.perform(post(URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"text\": \"해운대 가자\", \"viewport\": " + 유효한_뷰포트
					+ ", \"origin\": {\"lat\": 91.0, \"lng\": 129.04}}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(400));
	}

	// 검증: FR-ROUTE-01
	@Test
	@DisplayName("뷰포트 의미 위반은 플래그와 무관하게 14400 이다 — 검증이 비활성 게이트보다 앞선다")
	void 뒤집힌_뷰포트는_14400이다() throws Exception {
		mockMvc.perform(post(URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"text\": \"해운대 가자\", \"viewport\": "
					+ "{\"minLat\": 35.25, \"minLng\": 128.95, \"maxLat\": 35.05, \"maxLng\": 129.20}}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(14400));
	}
}
