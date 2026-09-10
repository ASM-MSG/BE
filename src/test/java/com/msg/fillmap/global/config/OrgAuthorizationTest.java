package com.msg.fillmap.global.config;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.auth.service.AuthService;
import com.msg.fillmap.user.dto.UserProfileResponseDto;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.service.UserService;
import com.msg.fillmap.zone.dto.ZoneResponseDto;
import com.msg.fillmap.zone.service.ZoneQueryService;

/**
 * 행사 운영자(ORG) 인가 (MSG-496, SRS FR-AUTH-14). 검증 대상은 컨트롤러가 아니라 SecurityConfig 의 인가 체인이라
 * 실제 API 가 아니라 프리픽스 아래 탐침 컨트롤러를 붙여 본다 (AdminAuthorizationTest 선례) — 콘솔 API
 * 실체는 MSG-498 부터 생기므로 지금은 인가 판정까지만 확인할 수 있다.
 *
 * <p>두 축이다. ORG 전용 경로(/api/org/**)가 ORG 에게만 열리는지, 그리고 catch-all 을
 * authenticated() 에서 hasAnyRole("USER", "ADMIN") 로 바꾼 것이 의도대로 ORG 만 잘라내는지 —
 * 후자는 ORG 가 써야 하는 공용 경로 2종(GET /api/users/me · POST /api/auth/logout)이 명시 허용으로
 * 살아 있는지, USER·ADMIN 과 비로그인 공개 조회에는 파급이 없는지를 함께 본다.
 *
 * <p>서비스는 전부 목이라 이 테스트의 변수는 matcher 등록뿐이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({OrgAuthorizationTest.OrgProbeController.class, OrgAuthorizationTest.CatchAllProbeController.class,
	AdminAuthorizationTest.AdminProbeController.class})
@DisplayName("행사 운영자 API 인가 (/api/org/**)")
class OrgAuthorizationTest {

	private static final String ORG_PROBE_URL = "/api/org/authorization-probe";
	private static final String ADMIN_PROBE_URL = "/api/admin/authorization-probe";
	/** 어느 열거 matcher 에도 안 잡혀 catch-all 로 떨어지는 경로 — 일반 사용자 API 를 대표한다. */
	private static final String CATCH_ALL_PROBE_URL = "/api/authorization-probe";
	private static final long ORG_ID = 9101L;
	private static final long USER_ID = 9102L;
	private static final long ADMIN_ID = 9103L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private UserService userService;

	@MockitoBean
	private AuthService authService;

	@MockitoBean
	private ZoneQueryService zoneQueryService;

	@RestController
	static class OrgProbeController {

		@GetMapping(ORG_PROBE_URL)
		public Map<String, Object> probe(@AuthenticationPrincipal AuthPrincipal principal) {
			return Map.of("userId", principal.userId(), "role", principal.role().name());
		}
	}

	@RestController
	static class CatchAllProbeController {

		@GetMapping(CATCH_ALL_PROBE_URL)
		public Map<String, Object> probe(@AuthenticationPrincipal AuthPrincipal principal) {
			return Map.of("role", principal.role().name());
		}
	}

	private String bearer(long userId, UserRole role) {
		return "Bearer " + tokenProvider.issueAccessToken(userId, role);
	}

	@Nested
	@DisplayName("ORG 전용 경로")
	class OrgOnlyPath {

		// 검증: FR-AUTH-14
		@Test
		@DisplayName("비로그인 호출은 401 이다 — 기존 EntryPoint 그대로")
		void 비로그인으로_org_경로에_접근하면_401이다() throws Exception {
			mockMvc.perform(get(ORG_PROBE_URL))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.developCode").value(2403));
		}

		// 검증: FR-AUTH-14
		@Test
		@DisplayName("USER 토큰 호출은 403 공통 응답 형식이다")
		void USER_토큰으로_org_경로에_접근하면_403이다() throws Exception {
			mockMvc.perform(get(ORG_PROBE_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID, UserRole.USER)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.developCode").value(403))
				.andExpect(jsonPath("$.message").value("접근 권한이 없습니다"));
		}

		// 검증: FR-AUTH-14
		@Test
		@DisplayName("ORG 토큰 호출은 역할 인가를 통과한다")
		void ORG_토큰으로_org_경로에_접근하면_역할_인가를_통과한다() throws Exception {
			mockMvc.perform(get(ORG_PROBE_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer(ORG_ID, UserRole.ORG)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value(ORG_ID))
				.andExpect(jsonPath("$.role").value("ORG"));
		}

		// 검증: FR-AUTH-14
		@Test
		@DisplayName("ORG 토큰의 관리자 경로 호출은 403 이다 — 관리자 API 는 ORG 에 열리지 않는다")
		void ORG_토큰으로_admin_경로에_접근하면_403이다() throws Exception {
			mockMvc.perform(get(ADMIN_PROBE_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer(ORG_ID, UserRole.ORG)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.developCode").value(403));
		}
	}

	@Nested
	@DisplayName("ORG 경계 — catch-all 차단과 공용 경로 명시 허용")
	class OrgBoundary {

		// 검증: FR-AUTH-14
		@Test
		@DisplayName("ORG 토큰의 일반 사용자 API 호출은 403 이다 — 콘솔 밖으로 권한이 안 미친다")
		void ORG_토큰으로_일반_사용자_API에_접근하면_403이다() throws Exception {
			mockMvc.perform(get(CATCH_ALL_PROBE_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer(ORG_ID, UserRole.ORG)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.developCode").value(403));
		}

		// 검증: FR-AUTH-14
		@Test
		@DisplayName("ORG 토큰의 내 프로필 조회는 통과한다 — GET /api/users/me 명시 허용")
		void ORG_토큰으로_내_프로필_조회는_통과한다() throws Exception {
			given(userService.getMyProfile(ORG_ID)).willReturn(new UserProfileResponseDto(
				"org@fillmap.dev", "행사운영자", null, LocalDateTime.parse("2026-08-28T00:00:00"), false, "ORG"));

			mockMvc.perform(get("/api/users/me")
					.header(HttpHeaders.AUTHORIZATION, bearer(ORG_ID, UserRole.ORG)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.role").value("ORG"));
		}

		// 검증: FR-AUTH-14
		@Test
		@DisplayName("ORG 토큰의 로그아웃은 통과한다 — POST /api/auth/logout 명시 허용")
		void ORG_토큰으로_로그아웃은_통과한다() throws Exception {
			mockMvc.perform(post("/api/auth/logout")
					.header(HttpHeaders.AUTHORIZATION, bearer(ORG_ID, UserRole.ORG)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200));
		}

		// 검증: FR-AUTH-14
		@Test
		@DisplayName("ORG 토큰의 프로필 형제 쓰기 경로는 403 이다 — /me 허용은 GET 한정")
		void ORG_토큰으로_프로필_형제_쓰기_경로는_403이다() throws Exception {
			mockMvc.perform(put("/api/users/me/nickname")
					.header(HttpHeaders.AUTHORIZATION, bearer(ORG_ID, UserRole.ORG)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.developCode").value(403));
		}
	}

	@Nested
	@DisplayName("기존 동작 회귀 — catch-all 교체 파급 없음")
	class Regression {

		// 검증: FR-AUTH-14
		@Test
		@DisplayName("USER 토큰의 일반 사용자 API 접근은 그대로 통과한다")
		void USER_토큰의_일반_사용자_API_접근은_그대로_통과한다() throws Exception {
			mockMvc.perform(get(CATCH_ALL_PROBE_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID, UserRole.USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("USER"));
		}

		// 검증: FR-AUTH-14
		@Test
		@DisplayName("ADMIN 토큰의 관리자 경로 접근은 그대로 통과한다")
		void ADMIN_토큰의_admin_경로_접근은_그대로_통과한다() throws Exception {
			mockMvc.perform(get(ADMIN_PROBE_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_ID, UserRole.ADMIN)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("ADMIN"));
		}

		// 검증: FR-AUTH-14
		@Test
		@DisplayName("비로그인 공개 조회 경로는 그대로 열려 있다 — permitAll 무변경")
		void 비로그인_공개_조회_경로는_그대로_열려_있다() throws Exception {
			given(zoneQueryService.getZones()).willReturn(List.of(
				new ZoneResponseDto("seomyeon", "서면", "2623051000", 16850, 16866, 11414, 11424, 0)));

			mockMvc.perform(get("/api/zones"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200));
		}

		// 검증: FR-AUTH-14
		@Test
		@DisplayName("비로그인의 비공개 경로 접근은 여전히 401 이다 — hasAnyRole 전환 후에도 403 아님")
		void 비로그인으로_비공개_경로_접근은_여전히_401이다() throws Exception {
			mockMvc.perform(get(CATCH_ALL_PROBE_URL))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.developCode").value(2403));
		}
	}
}
