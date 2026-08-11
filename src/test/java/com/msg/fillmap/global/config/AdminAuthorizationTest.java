package com.msg.fillmap.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.user.entity.UserRole;

/**
 * /api/admin/** ADMIN 전용 인가 (MSG-195 FR-9). 이 프로젝트 최초의 role 검사 지점이라, 검증 대상은
 * 특정 컨트롤러가 아니라 SecurityConfig 의 matcher 자체다 — 실제 관리자 API 가 아니라 프리픽스 아래
 * 탐침 컨트롤러를 붙여(JwtFilterIntegrationTest 선례) 엔드포인트 구현과 독립적으로 확인한다.
 *
 * <p>세 축을 본다: ADMIN 통과, USER 거부(403 공통 응답 형식), 무토큰 거부(401 2403). 403 은 새로
 * 등록한 CustomAccessDeniedHandler 가 만든다 — 미등록이면 스프링 시큐리티 기본 핸들러가 /error 로
 * 포워드해 Boot 기본 에러 JSON(timestamp·status·path)이 나가고 developCode 가 없다 (§D7).
 */
// 검증: FR-MOD-13
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminAuthorizationTest.AdminProbeController.class)
@DisplayName("관리자 API 인가 (/api/admin/**)")
class AdminAuthorizationTest {

	private static final String PROBE_URL = "/api/admin/authorization-probe";
	private static final long ADMIN_ID = 9001L;
	private static final long USER_ID = 9002L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@RestController
	static class AdminProbeController {

		@GetMapping(PROBE_URL)
		public Map<String, Object> probe(@AuthenticationPrincipal AuthPrincipal principal) {
			return Map.of("userId", principal.userId(), "role", principal.role().name());
		}
	}

	private String bearer(long userId, UserRole role) {
		return "Bearer " + tokenProvider.issueAccessToken(userId, role);
	}

	@Test
	@DisplayName("ADMIN 토큰은 관리자 API 를 통과한다 (FR-9)")
	void ADMIN_토큰은_관리자_API를_통과한다() throws Exception {
		mockMvc.perform(get(PROBE_URL)
				.header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_ID, UserRole.ADMIN)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.userId").value(ADMIN_ID))
			.andExpect(jsonPath("$.role").value("ADMIN"));
	}

	@Test
	@DisplayName("USER 토큰의 관리자 API 호출은 403 공통 응답 형식이다 (FR-9)")
	void USER_토큰의_관리자_API_호출은_403_공통_응답_형식이다() throws Exception {
		mockMvc.perform(get(PROBE_URL)
				.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID, UserRole.USER)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.developCode").value(403))
			.andExpect(jsonPath("$.message").value("접근 권한이 없습니다"))
			// Boot 기본 에러 JSON 이 새어 나오면 여기서 걸린다 — 공통 응답엔 timestamp·path 가 없다.
			.andExpect(jsonPath("$.timestamp").doesNotExist())
			.andExpect(jsonPath("$.path").doesNotExist());
	}

	@Test
	@DisplayName("토큰 없는 관리자 API 호출은 401 이다 — 기존 EntryPoint 그대로 (FR-9)")
	void 토큰_없는_관리자_API_호출은_401이다() throws Exception {
		mockMvc.perform(get(PROBE_URL))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.developCode").value(2403));
	}
}
