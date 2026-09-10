package com.msg.fillmap.user.controller;

import static org.hamcrest.Matchers.hasKey;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.user.dto.ConsentStatusResponseDto;
import com.msg.fillmap.user.dto.ConsentSubmitRequestDto;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.service.UserService;

/**
 * 가입 약관 동의 API 3종 (MSG-433 §API). UserProfileControllerTest 패턴 미러 — TokenProvider 실 Bearer +
 * @MockitoBean 정확값 스텁(principal userId 전달 검증).
 *
 * <p>필수 4항목 검증(FR-3)의 자리가 여기다 — @NotNull + @AssertTrue 가 컨트롤러 진입 전에 거르므로
 * 서비스에 아예 닿지 않는다는 것까지 단언한다(verifyNoInteractions). "아무 항목도 저장되지 않는다"는
 * 서비스 분기가 아니라 이 구조가 보장한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("UserController 가입 약관 동의 조회 · 제출 · 마케팅 변경")
class UserConsentControllerTest {

	private static final long USER_ID = 42L;
	private static final String CONSENTS_URL = "/api/users/me/consents";
	private static final String MARKETING_URL = "/api/users/me/marketing-consent";
	private static final String ALL_AGREED = """
		{"ageOver14":true,"serviceTerms":true,"privacyPolicy":true,"locationTerms":true,"marketing":true}""";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private UserService userService;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	private static ConsentStatusResponseDto consentStatus(boolean requiredCompleted, boolean marketing) {
		return new ConsentStatusResponseDto(requiredCompleted, requiredCompleted, requiredCompleted,
			requiredCompleted, marketing, requiredCompleted);
	}

	@Nested
	@DisplayName("동의 상태 조회")
	class Status {

		// 검증: FR-USER-15
		@Test
		@DisplayName("동의 상태를 조회한다 — 200 · 필수 완료 여부 (FR-1)")
		void 동의_상태를_조회한다() throws Exception {
			// 정확값 스텁 — principal userId(토큰의 USER_ID)가 서비스에 그대로 전달돼야만 매치된다(사용자 격리).
			given(userService.getConsentStatus(USER_ID)).willReturn(consentStatus(false, false));

			mockMvc.perform(get(CONSENTS_URL).header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200))
				.andExpect(jsonPath("$.data.requiredCompleted").value(false));
		}

		// 검증: FR-USER-15
		@Test
		@DisplayName("동의 상태 조회 JSON 은 6개 필드 required 계약을 지킨다")
		void 동의_상태_조회_JSON은_6개_필드_required_계약을_지킨다() throws Exception {
			given(userService.getConsentStatus(USER_ID)).willReturn(consentStatus(true, false));

			mockMvc.perform(get(CONSENTS_URL).header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data", hasKey("ageOver14")))
				.andExpect(jsonPath("$.data", hasKey("serviceTerms")))
				.andExpect(jsonPath("$.data", hasKey("privacyPolicy")))
				.andExpect(jsonPath("$.data", hasKey("locationTerms")))
				.andExpect(jsonPath("$.data", hasKey("marketing")))
				.andExpect(jsonPath("$.data", hasKey("requiredCompleted")))
				.andExpect(jsonPath("$.data.marketing").value(false));
		}
	}

	@Nested
	@DisplayName("동의 제출")
	class Submit {

		// 검증: FR-USER-15
		@Test
		@DisplayName("5항목을 제출하면 제출 후 동의 상태를 반환한다 (FR-2)")
		void 다섯_항목을_제출하면_제출_후_동의_상태를_반환한다() throws Exception {
			given(userService.submitConsents(USER_ID, new ConsentSubmitRequestDto(true, true, true, true, true)))
				.willReturn(consentStatus(true, true));

			mockMvc.perform(put(CONSENTS_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer())
					.contentType(MediaType.APPLICATION_JSON)
					.content(ALL_AGREED))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200))
				.andExpect(jsonPath("$.data.requiredCompleted").value(true))
				.andExpect(jsonPath("$.data.marketing").value(true));
		}

		// 검증: FR-USER-15
		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = {
			"{\"ageOver14\":false,\"serviceTerms\":true,\"privacyPolicy\":true,\"locationTerms\":true,"
				+ "\"marketing\":true}",
			"{\"ageOver14\":true,\"serviceTerms\":false,\"privacyPolicy\":true,\"locationTerms\":true,"
				+ "\"marketing\":true}",
			"{\"ageOver14\":true,\"serviceTerms\":true,\"privacyPolicy\":false,\"locationTerms\":true,"
				+ "\"marketing\":true}",
			"{\"ageOver14\":true,\"serviceTerms\":true,\"privacyPolicy\":true,\"locationTerms\":false,"
				+ "\"marketing\":true}"})
		@DisplayName("필수 항목 하나라도 false 면 400 이고 아무 항목도 저장되지 않는다 (FR-3, @AssertTrue)")
		void 필수_항목_하나라도_false면_400이고_아무_항목도_저장되지_않는다(String body) throws Exception {
			mockMvc.perform(put(CONSENTS_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer())
					.contentType(MediaType.APPLICATION_JSON)
					.content(body))
				.andExpect(status().isBadRequest());

			// 검증이 컨트롤러 진입 전이라 서비스가 호출되지 않는다 — 부분 저장이 생길 자리 자체가 없다.
			verifyNoInteractions(userService);
		}

		// 검증: FR-USER-15
		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = {
			"{\"serviceTerms\":true,\"privacyPolicy\":true,\"locationTerms\":true,\"marketing\":true}",
			"{\"ageOver14\":true,\"privacyPolicy\":true,\"locationTerms\":true,\"marketing\":true}",
			"{\"ageOver14\":true,\"serviceTerms\":true,\"locationTerms\":true,\"marketing\":true}",
			"{\"ageOver14\":true,\"serviceTerms\":true,\"privacyPolicy\":true,\"marketing\":true}"})
		@DisplayName("필수 항목 누락 요청은 400 이다 (@NotNull — @AssertTrue 는 null 을 통과시킨다)")
		void 필수_항목_누락_요청은_400이다(String body) throws Exception {
			mockMvc.perform(put(CONSENTS_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer())
					.contentType(MediaType.APPLICATION_JSON)
					.content(body))
				.andExpect(status().isBadRequest());

			verifyNoInteractions(userService);
		}

		// 검증: FR-USER-15
		@Test
		@DisplayName("marketing 누락 요청은 400 이다 (선택 항목이지만 여부는 필수 — 누락이 false 로 둔갑하지 않는다)")
		void marketing_누락_요청은_400이다() throws Exception {
			mockMvc.perform(put(CONSENTS_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer())
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"ageOver14":true,"serviceTerms":true,"privacyPolicy":true,"locationTerms":true}"""))
				.andExpect(status().isBadRequest());

			verifyNoInteractions(userService);
		}

		// 검증: FR-USER-15
		@Test
		@DisplayName("마케팅 false 제출은 통과한다 (선택 항목은 false 도 유효)")
		void 마케팅_false_제출은_통과한다() throws Exception {
			given(userService.submitConsents(USER_ID, new ConsentSubmitRequestDto(true, true, true, true, false)))
				.willReturn(consentStatus(true, false));

			mockMvc.perform(put(CONSENTS_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer())
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"ageOver14":true,"serviceTerms":true,"privacyPolicy":true,"locationTerms":true,
						 "marketing":false}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.marketing").value(false))
				.andExpect(jsonPath("$.data.requiredCompleted").value(true));
		}
	}

	@Nested
	@DisplayName("마케팅 동의 변경")
	class Marketing {

		// 검증: FR-USER-15
		@Test
		@DisplayName("마케팅 동의를 켜면 변경 후 동의 상태를 반환한다 (FR-6)")
		void 마케팅_동의를_켜면_변경_후_동의_상태를_반환한다() throws Exception {
			given(userService.updateMarketingConsent(USER_ID, true)).willReturn(consentStatus(true, true));

			mockMvc.perform(put(MARKETING_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer())
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"consented\":true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.marketing").value(true));
		}

		// 검증: FR-USER-15
		@Test
		@DisplayName("마케팅 동의를 끄면 false 인 동의 상태를 반환한다 (FR-6, 양방향)")
		void 마케팅_동의를_끄면_false인_동의_상태를_반환한다() throws Exception {
			given(userService.updateMarketingConsent(USER_ID, false)).willReturn(consentStatus(true, false));

			mockMvc.perform(put(MARKETING_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer())
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"consented\":false}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.marketing").value(false));
		}

		// 검증: FR-USER-15
		@Test
		@DisplayName("consented 없는 마케팅 변경 요청은 400 이다 (@NotNull)")
		void consented_없는_마케팅_변경_요청은_400이다() throws Exception {
			mockMvc.perform(put(MARKETING_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer())
					.contentType(MediaType.APPLICATION_JSON)
					.content("{}"))
				.andExpect(status().isBadRequest());

			verifyNoInteractions(userService);
		}
	}

	@Nested
	@DisplayName("인증 경계")
	class Authentication {

		@Test
		@DisplayName("토큰 없는 동의 상태 조회는 401 UNAUTHENTICATED (2403) 다")
		void 토큰_없는_동의_상태_조회는_2403이다() throws Exception {
			mockMvc.perform(get(CONSENTS_URL))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.developCode").value(2403));
		}

		@Test
		@DisplayName("토큰 없는 동의 제출은 401 UNAUTHENTICATED (2403) 다")
		void 토큰_없는_동의_제출은_2403이다() throws Exception {
			mockMvc.perform(put(CONSENTS_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(ALL_AGREED))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.developCode").value(2403));
		}

		@Test
		@DisplayName("토큰 없는 마케팅 변경은 401 UNAUTHENTICATED (2403) 다")
		void 토큰_없는_마케팅_변경은_2403이다() throws Exception {
			mockMvc.perform(put(MARKETING_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"consented\":true}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.developCode").value(2403));
		}
	}
}
