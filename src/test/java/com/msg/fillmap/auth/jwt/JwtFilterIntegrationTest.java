package com.msg.fillmap.auth.jwt;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.msg.fillmap.user.entity.UserRole;

@SpringBootTest
@AutoConfigureMockMvc
@Import(JwtFilterIntegrationTest.TestProtectedController.class)
@DisplayName("JWT 필터 체인 통합")
class JwtFilterIntegrationTest {

	private static final String PROTECTED_URL = "/test/protected";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@Autowired
	private JwtProperties jwtProperties;

	@Autowired
	private InvalidatedTokenStore invalidatedTokenStore;

	@RestController
	static class TestProtectedController {

		@GetMapping("/test/protected")
		public Map<String, Object> whoami(@AuthenticationPrincipal AuthPrincipal principal) {
			return Map.of(
				"userId", principal.userId(),
				"role", principal.role().name()
			);
		}
	}

	@Nested
	@DisplayName("보호된 엔드포인트 접근")
	class Protected {

		@Test
		@DisplayName("토큰이 없으면 401 UNAUTHENTICATED (2403) 로 응답한다 - EntryPoint 경로")
		void noToken() throws Exception {
			mockMvc.perform(get(PROTECTED_URL))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.developCode").value(2403))
				.andExpect(jsonPath("$.message").value("인증이 필요합니다"));
		}

		@Test
		@DisplayName("유효한 Bearer 토큰이면 200 과 principal 정보가 반환된다")
		void validToken() throws Exception {
			String token = tokenProvider.issueAccessToken(42L, UserRole.USER);

			mockMvc.perform(get(PROTECTED_URL)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value(42))
				.andExpect(jsonPath("$.role").value("USER"));
		}

		@Test
		@DisplayName("만료된 토큰이면 401 EXPIRED_TOKEN (2402) 로 응답한다 - 필터→resolver→GlobalExceptionHandler 경로")
		void expiredToken() throws Exception {
			JwtProperties expiredProps = new JwtProperties(
				jwtProperties.secret(), Duration.ofSeconds(-1),
				jwtProperties.refreshSecret(), jwtProperties.refreshTokenTtl()
			);
			JwtTokenProvider expiredIssuer = new JwtTokenProvider(expiredProps, new InMemoryInvalidatedTokenStore());
			String token = expiredIssuer.issueAccessToken(1L, UserRole.USER);

			mockMvc.perform(get(PROTECTED_URL)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.developCode").value(2402))
				.andExpect(jsonPath("$.message").value("만료된 토큰입니다"));
		}

		@Test
		@DisplayName("변조된 토큰(다른 시크릿으로 서명) 이면 401 INVALID_TOKEN (2401) 로 응답한다")
		void forgedToken() throws Exception {
			JwtProperties otherProps = new JwtProperties(
				"another-completely-different-secret-32-bytes-plus-long",
				Duration.ofHours(1),
				jwtProperties.refreshSecret(), jwtProperties.refreshTokenTtl()
			);
			JwtTokenProvider forger = new JwtTokenProvider(otherProps, new InMemoryInvalidatedTokenStore());
			String token = forger.issueAccessToken(1L, UserRole.USER);

			mockMvc.perform(get(PROTECTED_URL)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.developCode").value(2401))
				.andExpect(jsonPath("$.message").value("유효하지 않은 토큰입니다"));
		}

		@Test
		@DisplayName("reissue 는 permitAll — 토큰 없이 접근해도 2403(UNAUTHENTICATED)이 아니라 컨트롤러에 도달한다 (MSG-135)")
		void reissue_엔드포인트는_인증없이_접근가능하다() throws Exception {
			// 리프레시 토큰이 없으므로 2431 — 시큐리티가 막았다면 2403 이 나온다
			mockMvc.perform(post("/api/auth/reissue"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.developCode").value(2431));
		}

		@Test
		@DisplayName("로그아웃된 토큰이면 401 INVALID_TOKEN (2401) 로 응답한다")
		void invalidatedToken() throws Exception {
			String token = tokenProvider.issueAccessToken(42L, UserRole.USER);
			invalidatedTokenStore.invalidate(token, Instant.now().plus(Duration.ofHours(1)));

			mockMvc.perform(get(PROTECTED_URL)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.developCode").value(2401))
				.andExpect(jsonPath("$.message").value("유효하지 않은 토큰입니다"));
		}
	}
}
