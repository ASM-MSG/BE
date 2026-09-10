package com.msg.fillmap.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.auth.jwt.JwtProperties;
import com.msg.fillmap.auth.jwt.RefreshTokenStore;
import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.auth.service.RefreshTokenService;
import com.msg.fillmap.global.mail.MailSender;
import com.msg.fillmap.user.entity.AuthProvider;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.user.service.OrgAccountIssueService;

/**
 * 비밀번호 상태·변경·재설정 4종 (MSG-497 FR-21·22, 실 DB·실 Redis). 검증 대상이 인가 경계·저장소
 * 상태·메일 발송까지 걸쳐 있어 목으로는 잡히지 않는다 — 메일 발송만 목으로 갈아 끼워 수신자와 본문을
 * 단언한다. {@code @Transactional} 롤백 격리로 공유 로컬 DB 에 계정을 남기지 않고, 이메일과 토큰은
 * 매번 UUID 라 Redis 키도 다른 테스트와 겹치지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("비밀번호 API (MSG-497, 실 DB)")
class PasswordControllerTest {

	private static final String STATUS_URL = "/api/auth/password/status";
	private static final String CHANGE_URL = "/api/auth/password/change";
	private static final String INITIAL_URL = "/api/auth/password/initial";
	private static final String ORG_PROFILE_URL = "/api/org/profile";
	private static final String RESET_REQUEST_URL = "/api/auth/password/reset-request";
	private static final String RESET_URL = "/api/auth/password/reset";
	private static final String INITIAL_PASSWORD = "Initial1234";
	private static final String NEW_PASSWORD = "Fillmap5678";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	/** 재설정 저장 실패를 주입하는 지점 — encode 만 예외로 바꿔 커밋 실패 경로를 만든다. */
	@MockitoSpyBean
	private PasswordEncoder passwordEncoder;

	@Autowired
	private TokenProvider tokenProvider;

	@Autowired
	private JwtProperties jwtProperties;

	/** 커밋 후 세션 정리 실패를 주입하는 지점 — 그 실패가 응답으로 새면 안 된다. */
	@MockitoSpyBean
	private RefreshTokenService refreshTokenService;

	@Autowired
	private RefreshTokenStore refreshTokenStore;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@MockitoBean
	private MailSender mailSender;

	/** 초기 비밀번호 재발송 — 관리자 API 를 거치지 않고 그 경로의 세션 무효화만 본다. */
	@Autowired
	private OrgAccountIssueService orgAccountIssueService;

	private User organizer;
	private String organizerEmail;

	@BeforeEach
	void setUp() {
		organizerEmail = "organizer-" + UUID.randomUUID() + "@fillmap.dev";
		organizer = saveUser(organizerEmail, INITIAL_PASSWORD, UserRole.ORG);
	}

	private User saveUser(String email, String rawPassword, UserRole role) {
		User user = User.createLocalUser(email, passwordEncoder.encode(rawPassword), "담당자");
		ReflectionTestUtils.setField(user, "role", role);
		return userRepository.saveAndFlush(user);
	}

	private User saveSocialUser(String email) {
		User user = User.createOAuthUser(AuthProvider.KAKAO, "oid-" + UUID.randomUUID(), email, "소셜사용자");
		return userRepository.saveAndFlush(user);
	}

	private String bearer(User user) {
		return "Bearer " + tokenProvider.issueAccessToken(user.getId(), user.getRole());
	}

	private static String sha256Hex(String value) {
		try {
			return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private String changeBody(String current, String next) {
		return """
			{"currentPassword": "%s", "newPassword": "%s"}""".formatted(current, next);
	}

	private String initialBody(String password) {
		return """
			{"newPassword": "%s"}""".formatted(password);
	}

	/** 관리자 발급 직후 상태 — 강제 변경 플래그가 켜진 초기 비밀번호 계정이다. */
	private void 초기_비밀번호_상태로_만든다() {
		ReflectionTestUtils.setField(organizer, "passwordMustChange", true);
		userRepository.flush();
	}

	private String emailBody(String email) {
		return """
			{"email": "%s"}""".formatted(email);
	}

	private String resetBody(String token, String password) {
		return """
			{"token": "%s", "newPassword": "%s"}""".formatted(token, password);
	}

	/** 발송된 메일 본문에서 링크의 토큰 원문을 꺼낸다 — 실제 사용자가 메일함에서 하는 일과 같다. */
	private String capturedResetToken() {
		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		then(mailSender).should().send(eq(organizerEmail), anyString(), body.capture());
		String link = body.getValue();
		return link.substring(link.indexOf("?token=") + "?token=".length()).split("\\s")[0];
	}

	@Nested
	@DisplayName("상태 조회")
	class Status {

		// 검증: FR-AUTH-15
		@Test
		@DisplayName("초기 비밀번호 상태면 참을 반환한다")
		void 초기_비밀번호_상태면_상태_조회가_참이다() throws Exception {
			ReflectionTestUtils.setField(organizer, "passwordMustChange", true);
			userRepository.flush();

			mockMvc.perform(get(STATUS_URL).header(HttpHeaders.AUTHORIZATION, bearer(organizer)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.mustChange").value(true));
		}

		// 검증: FR-AUTH-15
		@Test
		@DisplayName("비밀번호가 없는 소셜 계정은 거짓이다 — 플래그를 세울 경로가 없다")
		void 비밀번호가_없는_소셜_계정은_상태_조회가_거짓이다() throws Exception {
			User social = saveSocialUser("social-" + UUID.randomUUID() + "@fillmap.dev");

			mockMvc.perform(get(STATUS_URL).header(HttpHeaders.AUTHORIZATION, bearer(social)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.mustChange").value(false));
		}
	}

	@Nested
	@DisplayName("비밀번호 변경")
	class Change {

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("현재 비밀번호가 틀리면 2442 로 거부된다 — 401 이 아니다")
		void 현재_비밀번호가_틀리면_변경이_거부된다() throws Exception {
			mockMvc.perform(post(CHANGE_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer(organizer))
					.contentType(MediaType.APPLICATION_JSON)
					.content(changeBody("WrongPass123", NEW_PASSWORD)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(2442));
		}

		// 검증: FR-AUTH-15
		@Test
		@DisplayName("새 비밀번호가 현재와 같으면 2444 로 거부된다 — 발급자가 아는 값이 유지된다")
		void 새_비밀번호가_현재와_같으면_거부된다() throws Exception {
			mockMvc.perform(post(CHANGE_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer(organizer))
					.contentType(MediaType.APPLICATION_JSON)
					.content(changeBody(INITIAL_PASSWORD, INITIAL_PASSWORD)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(2444));
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("정책 위반 비밀번호와 누락은 공통 400 이다")
		void 정책_위반_비밀번호는_공통_400이다() throws Exception {
			for (String invalid : new String[] {"Ab1234", "onlyletters", "12345678"}) {
				mockMvc.perform(post(CHANGE_URL)
						.header(HttpHeaders.AUTHORIZATION, bearer(organizer))
						.contentType(MediaType.APPLICATION_JSON)
						.content(changeBody(INITIAL_PASSWORD, invalid)))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.developCode").value(400));
			}
			mockMvc.perform(post(CHANGE_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer(organizer))
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"currentPassword": "%s"}""".formatted(INITIAL_PASSWORD)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(400));
		}

		// 검증: FR-AUTH-15
		@Test
		@DisplayName("변경에 성공하면 강제 변경 플래그가 풀리고 새 비밀번호로 로그인된다")
		void 변경_성공_시_강제_변경_플래그가_해제되고_새_비밀번호로_로그인된다() throws Exception {
			ReflectionTestUtils.setField(organizer, "passwordMustChange", true);
			userRepository.flush();

			mockMvc.perform(post(CHANGE_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer(organizer))
					.contentType(MediaType.APPLICATION_JSON)
					.content(changeBody(INITIAL_PASSWORD, NEW_PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200));

			assertThat(userRepository.findById(organizer.getId()).orElseThrow().isPasswordMustChange()).isFalse();
			mockMvc.perform(post("/api/auth/login")
					.header("X-Client-Type", "app")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"email": "%s", "password": "%s"}""".formatted(organizerEmail, NEW_PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.accessToken").isNotEmpty());
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("변경에 성공하면 잔여 재설정 링크가 폐기된다")
		void 변경_성공_시_잔여_재설정_토큰이_폐기된다() throws Exception {
			mockMvc.perform(post(RESET_REQUEST_URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content(emailBody(organizerEmail)));
			String token = capturedResetToken();

			mockMvc.perform(post(CHANGE_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer(organizer))
					.contentType(MediaType.APPLICATION_JSON)
					.content(changeBody(INITIAL_PASSWORD, NEW_PASSWORD)))
				.andExpect(status().isOk());

			mockMvc.perform(post(RESET_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(resetBody(token, "Another9999")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(2443));
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("소셜 계정의 변경 요청은 2445 로 거부된다 — 불일치로 수렴시키지 않는다")
		void 소셜_계정의_변경_요청은_비밀번호_미설정으로_거부된다() throws Exception {
			User social = saveSocialUser("social-" + UUID.randomUUID() + "@fillmap.dev");

			mockMvc.perform(post(CHANGE_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer(social))
					.contentType(MediaType.APPLICATION_JSON)
					.content(changeBody(INITIAL_PASSWORD, NEW_PASSWORD)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(2445));
		}
	}

	@Nested
	@DisplayName("재설정 요청 — 계정 존재 은닉")
	class ResetRequest {

		private void 같은_성공_응답이다(String email) throws Exception {
			mockMvc.perform(post(RESET_REQUEST_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(emailBody(email)))
				.andExpect(status().isOk())
				.andExpect(content().json("""
					{"developCode": 200, "message": "성공", "data": null}"""));
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("존재하는 계정과 없는 계정의 응답이 완전히 같다")
		void 존재하는_계정과_없는_계정의_재설정_요청_응답이_같다() throws Exception {
			같은_성공_응답이다(organizerEmail);
			같은_성공_응답이다("absent-" + UUID.randomUUID() + "@fillmap.dev");
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("소셜 계정 이메일도 같은 성공 응답이고 발송은 없다")
		void 소셜_계정_이메일의_재설정_요청도_같은_성공_응답이다() throws Exception {
			String email = "social-" + UUID.randomUUID() + "@fillmap.dev";
			saveSocialUser(email);

			같은_성공_응답이다(email);

			then(mailSender).should(never()).send(eq(email), anyString(), anyString());
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("USER 역할 LOCAL 계정도 같은 성공 응답이고 발송은 없다 — 대상은 ORG·ADMIN 뿐")
		void USER_역할_LOCAL_계정의_재설정_요청도_같은_성공_응답이다() throws Exception {
			String email = "user-" + UUID.randomUUID() + "@fillmap.dev";
			saveUser(email, INITIAL_PASSWORD, UserRole.USER);

			같은_성공_응답이다(email);

			then(mailSender).should(never()).send(eq(email), anyString(), anyString());
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("관리자 LOCAL 계정에도 재설정 링크가 발송된다 — 같은 이메일 로그인 경로라서다")
		void 관리자_LOCAL_계정에는_재설정_링크가_발송된다() throws Exception {
			String email = "admin-" + UUID.randomUUID() + "@fillmap.dev";
			saveUser(email, INITIAL_PASSWORD, UserRole.ADMIN);

			같은_성공_응답이다(email);

			then(mailSender).should().send(eq(email), anyString(), anyString());
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("발송이 실패해도 응답은 같은 성공이다 — 실패가 새면 존재 오라클이 된다")
		void 발송이_실패해도_응답은_같은_성공이다() throws Exception {
			willThrow(new IllegalStateException("SES 오류")).given(mailSender)
				.send(anyString(), anyString(), anyString());

			같은_성공_응답이다(organizerEmail);
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("응답에 토큰이 실리지 않는다 — 토큰 원문은 메일에만 있다")
		void 재설정_요청_응답에_토큰이_실리지_않는다() throws Exception {
			String response = mockMvc.perform(post(RESET_REQUEST_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(emailBody(organizerEmail)))
				.andReturn().getResponse().getContentAsString();

			assertThat(response).doesNotContain(capturedResetToken());
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("메일 본문의 링크에 토큰 원문이 들어간다 — base URL 프로퍼티 조립")
		void 메일_본문의_링크에_토큰_원문이_들어간다() throws Exception {
			mockMvc.perform(post(RESET_REQUEST_URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content(emailBody(organizerEmail)));

			ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
			then(mailSender).should().send(eq(organizerEmail), anyString(), body.capture());
			assertThat(body.getValue()).contains("http://localhost:5173/reset-password?token=");
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("이메일 누락과 빈 문자열은 공통 400 이다")
		void 이메일_누락과_빈_문자열은_공통_400이다() throws Exception {
			for (String body : new String[] {"{}", emailBody(""), emailBody("   ")}) {
				mockMvc.perform(post(RESET_REQUEST_URL)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.developCode").value(400));
			}
			then(mailSender).should(never()).send(anyString(), anyString(), anyString());
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("재요청하면 이전 링크가 즉시 무효가 된다 — 사용자당 활성 1개")
		void 재요청하면_이전_링크가_즉시_무효가_된다() throws Exception {
			mockMvc.perform(post(RESET_REQUEST_URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content(emailBody(organizerEmail)));
			String first = capturedResetToken();
			// 쿨다운은 이메일 기준이라 재요청을 보려면 그 키를 지운다 (60초 대기 대체, 다른 이메일 키는 건드리지 않는다)
			redisTemplate.delete("pwreset-cooldown:" + sha256Hex(organizerEmail));
			mockMvc.perform(post(RESET_REQUEST_URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content(emailBody(organizerEmail)));

			mockMvc.perform(post(RESET_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(resetBody(first, NEW_PASSWORD)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(2443));
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("쿨다운 안의 재요청은 발송 없이 같은 응답이다")
		void 쿨다운_내_재요청은_발송_없이_같은_응답이다() throws Exception {
			같은_성공_응답이다(organizerEmail);
			같은_성공_응답이다(organizerEmail);

			then(mailSender).should().send(eq(organizerEmail), anyString(), anyString());
		}
	}

	@Nested
	@DisplayName("재설정 확정")
	class ResetConfirm {

		private String 링크를_받는다() throws Exception {
			mockMvc.perform(post(RESET_REQUEST_URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content(emailBody(organizerEmail)));
			return capturedResetToken();
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("유효 토큰으로 재설정하면 새 비밀번호로 로그인되고 강제 변경 플래그도 풀린다")
		void 유효_토큰으로_재설정하면_새_비밀번호로_로그인된다() throws Exception {
			ReflectionTestUtils.setField(organizer, "passwordMustChange", true);
			userRepository.flush();
			String token = 링크를_받는다();

			mockMvc.perform(post(RESET_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(resetBody(token, NEW_PASSWORD)))
				.andExpect(status().isOk());

			assertThat(userRepository.findById(organizer.getId()).orElseThrow().isPasswordMustChange()).isFalse();
			mockMvc.perform(post("/api/auth/login")
					.header("X-Client-Type", "app")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"email": "%s", "password": "%s"}""".formatted(organizerEmail, NEW_PASSWORD)))
				.andExpect(status().isOk());
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("같은 토큰의 두 번째 사용과 위조 토큰의 실패 응답이 같다 — 단일 2443")
		void 같은_토큰의_두번째_사용과_위조_토큰의_실패가_같다() throws Exception {
			String token = 링크를_받는다();
			mockMvc.perform(post(RESET_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(resetBody(token, NEW_PASSWORD)))
				.andExpect(status().isOk());

			for (String invalid : new String[] {token, "forged-" + UUID.randomUUID()}) {
				mockMvc.perform(post(RESET_URL)
						.contentType(MediaType.APPLICATION_JSON)
						.content(resetBody(invalid, NEW_PASSWORD)))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.developCode").value(2443))
					.andExpect(jsonPath("$.message").value("유효하지 않거나 만료된 재설정 링크입니다"));
			}
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("재설정에 성공하면 전 디바이스 리프레시 세션이 삭제된다")
		void 재설정_성공_시_전_디바이스_리프레시_세션이_삭제된다() throws Exception {
			refreshTokenService.issue(organizer.getId(), "device-1");
			refreshTokenService.issue(organizer.getId(), "device-2");
			String token = 링크를_받는다();

			mockMvc.perform(post(RESET_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(resetBody(token, NEW_PASSWORD)))
				.andExpect(status().isOk());

			assertThat(refreshTokenStore.findJti(organizer.getId(), "device-1")).isNull();
			assertThat(refreshTokenStore.findJti(organizer.getId(), "device-2")).isNull();
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("재설정에 성공하면 기존 액세스 토큰이 즉시 거부된다")
		void 재설정_성공_시_기존_액세스_토큰이_즉시_거부된다() throws Exception {
			String existingToken = bearer(organizer);
			String token = 링크를_받는다();

			mockMvc.perform(post(RESET_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(resetBody(token, NEW_PASSWORD)))
				.andExpect(status().isOk());

			mockMvc.perform(get(STATUS_URL).header(HttpHeaders.AUTHORIZATION, existingToken))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.developCode").value(2401));
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("무효화 마커의 TTL 이 리프레시 수명과 같다 — 부분 실패한 세션 삭제의 백스톱")
		void 무효화_마커의_TTL이_리프레시_수명과_같다() throws Exception {
			String token = 링크를_받는다();

			mockMvc.perform(post(RESET_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(resetBody(token, NEW_PASSWORD)))
				.andExpect(status().isOk());

			long refreshTtlSeconds = jwtProperties.refreshTokenTtl().toSeconds();
			Long ttlSeconds = redisTemplate.getExpire("blacklist:user:" + organizer.getId());
			assertThat(ttlSeconds).isBetween(refreshTtlSeconds - 60, refreshTtlSeconds);
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("커밋 후 세션 삭제가 실패해도 재설정은 성공으로 끝난다 — 재시도 불가한 거짓 실패 차단")
		void 세션_삭제가_실패해도_재설정은_성공하고_비밀번호는_바뀐다() throws Exception {
			String token = 링크를_받는다();
			willThrow(new IllegalStateException("Redis 장애")).given(refreshTokenService).deleteAll(anyLong());

			mockMvc.perform(post(RESET_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(resetBody(token, NEW_PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200));

			String storedHash = userRepository.findById(organizer.getId()).orElseThrow().getPasswordHash();
			assertThat(passwordEncoder.matches(NEW_PASSWORD, storedHash)).isTrue();
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("저장이 실패하면 선점한 토큰이 복원된다 — 링크가 소실되지 않는다")
		void 토큰_소비_후_저장_실패면_토큰이_복원된다() throws Exception {
			String token = 링크를_받는다();
			willThrow(new IllegalStateException("저장 실패")).given(passwordEncoder).encode(NEW_PASSWORD);

			mockMvc.perform(post(RESET_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(resetBody(token, NEW_PASSWORD)))
				.andExpect(status().isInternalServerError());

			assertThat(redisTemplate.hasKey("pwreset:user:" + organizer.getId())).isTrue();
		}
	}

	@Nested
	@DisplayName("초기 비밀번호 설정 (MSG-537)")
	class InitialPassword {

		// 검증: FR-AUTH-17
		@Test
		@DisplayName("강제 변경 상태의 행사 운영자가 새 비밀번호만으로 설정에 성공한다")
		void 강제_변경_상태의_행사_운영자가_새_비밀번호만으로_설정에_성공한다() throws Exception {
			초기_비밀번호_상태로_만든다();

			mockMvc.perform(post(INITIAL_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer(organizer))
					.contentType(MediaType.APPLICATION_JSON)
					.content(initialBody(NEW_PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200));

			mockMvc.perform(post("/api/auth/login")
					.header("X-Client-Type", "app")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"email": "%s", "password": "%s"}""".formatted(organizerEmail, NEW_PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.accessToken").isNotEmpty());
			mockMvc.perform(post("/api/auth/login")
					.header("X-Client-Type", "app")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"email": "%s", "password": "%s"}""".formatted(organizerEmail, INITIAL_PASSWORD)))
				.andExpect(status().isUnauthorized());
		}

		// 검증: FR-AUTH-17
		@Test
		@DisplayName("설정에 성공하면 강제 변경 상태가 풀려 콘솔 접근이 열린다")
		void 설정에_성공하면_강제_변경_상태가_풀려_콘솔_접근이_열린다() throws Exception {
			초기_비밀번호_상태로_만든다();

			mockMvc.perform(post(INITIAL_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer(organizer))
					.contentType(MediaType.APPLICATION_JSON)
					.content(initialBody(NEW_PASSWORD)))
				.andExpect(status().isOk());

			assertThat(userRepository.findById(organizer.getId()).orElseThrow().isPasswordMustChange()).isFalse();
			mockMvc.perform(get(ORG_PROFILE_URL).header(HttpHeaders.AUTHORIZATION, bearer(organizer)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200));
		}

		// 검증: FR-AUTH-17
		@Test
		@DisplayName("설정에 성공해도 현재 로그인 세션은 유지된다 — 자발적 설정이라 복구 시나리오가 아니다")
		void 설정에_성공해도_현재_로그인_세션은_유지된다() throws Exception {
			초기_비밀번호_상태로_만든다();
			String existingToken = bearer(organizer);

			mockMvc.perform(post(INITIAL_URL)
					.header(HttpHeaders.AUTHORIZATION, existingToken)
					.contentType(MediaType.APPLICATION_JSON)
					.content(initialBody(NEW_PASSWORD)))
				.andExpect(status().isOk());

			mockMvc.perform(get(STATUS_URL).header(HttpHeaders.AUTHORIZATION, existingToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.mustChange").value(false));
		}

		// 검증: FR-AUTH-17
		@Test
		@DisplayName("설정에 성공하면 남아 있던 재설정 링크가 폐기된다")
		void 설정에_성공하면_남아_있던_재설정_링크가_폐기된다() throws Exception {
			초기_비밀번호_상태로_만든다();
			mockMvc.perform(post(RESET_REQUEST_URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content(emailBody(organizerEmail)));
			String token = capturedResetToken();

			mockMvc.perform(post(INITIAL_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer(organizer))
					.contentType(MediaType.APPLICATION_JSON)
					.content(initialBody(NEW_PASSWORD)))
				.andExpect(status().isOk());

			mockMvc.perform(post(RESET_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(resetBody(token, "Another9999")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(2443));
		}

		// 검증: FR-AUTH-17
		@Test
		@DisplayName("설정을 마친 계정의 재요청은 2446 으로 거절된다 — 화면이 재변경으로 안내한다")
		void 설정을_마친_계정의_재요청은_전용_코드로_거절된다() throws Exception {
			초기_비밀번호_상태로_만든다();
			mockMvc.perform(post(INITIAL_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer(organizer))
					.contentType(MediaType.APPLICATION_JSON)
					.content(initialBody(NEW_PASSWORD)))
				.andExpect(status().isOk());

			mockMvc.perform(post(INITIAL_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer(organizer))
					.contentType(MediaType.APPLICATION_JSON)
					.content(initialBody("Another9999")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.developCode").value(2446));
		}

		// 검증: FR-AUTH-17
		@Test
		@DisplayName("새 비밀번호가 초기 비밀번호와 같으면 2444 로 거절된다")
		void 새_비밀번호가_초기_비밀번호와_같으면_거절된다() throws Exception {
			초기_비밀번호_상태로_만든다();

			mockMvc.perform(post(INITIAL_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer(organizer))
					.contentType(MediaType.APPLICATION_JSON)
					.content(initialBody(INITIAL_PASSWORD)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(2444));
		}

		// 검증: FR-AUTH-17
		@Test
		@DisplayName("비밀번호가 없는 소셜 계정의 요청은 2445 다 — 플래그 검사보다 먼저라 2446 으로 새지 않는다")
		void 비밀번호가_없는_소셜_계정의_요청은_거절된다() throws Exception {
			User social = saveSocialUser("social-" + UUID.randomUUID() + "@fillmap.dev");

			mockMvc.perform(post(INITIAL_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer(social))
					.contentType(MediaType.APPLICATION_JSON)
					.content(initialBody(NEW_PASSWORD)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(2445));
		}

		// 검증: FR-AUTH-17
		@Test
		@DisplayName("정책에 어긋나는 새 비밀번호는 검증에서 거절된다")
		void 정책에_어긋나는_새_비밀번호는_검증에서_거절된다() throws Exception {
			초기_비밀번호_상태로_만든다();

			for (String invalid : new String[] {"Ab12345", "onlyletters", "12345678"}) {
				mockMvc.perform(post(INITIAL_URL)
						.header(HttpHeaders.AUTHORIZATION, bearer(organizer))
						.contentType(MediaType.APPLICATION_JSON)
						.content(initialBody(invalid)))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.developCode").value(400));
			}
			mockMvc.perform(post(INITIAL_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer(organizer))
					.contentType(MediaType.APPLICATION_JSON)
					.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(400));
		}

		// 검증: FR-AUTH-17
		@Test
		@DisplayName("비로그인 요청은 401 이다 — status·change 와 같은 authenticated 줄")
		void 비로그인_요청은_거부된다() throws Exception {
			mockMvc.perform(post(INITIAL_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(initialBody(NEW_PASSWORD)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.developCode").value(2403));
		}

		// 검증: FR-AUTH-17
		@Test
		@DisplayName("재발송 후 옛 토큰으로는 초기 설정이 거절된다 — 자격 회전이 무력화되지 않는다")
		void 재발송_후_옛_토큰으로는_초기_설정이_거절된다() throws Exception {
			초기_비밀번호_상태로_만든다();
			String oldToken = bearer(organizer);

			orgAccountIssueService.resendInitialPassword(organizer.getId());

			mockMvc.perform(post(INITIAL_URL)
					.header(HttpHeaders.AUTHORIZATION, oldToken)
					.contentType(MediaType.APPLICATION_JSON)
					.content(initialBody(NEW_PASSWORD)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.developCode").value(2401));
			assertThat(userRepository.findById(organizer.getId()).orElseThrow().isPasswordMustChange()).isTrue();
		}

		// 검증: FR-AUTH-15
		@Test
		@DisplayName("게이트 차단 메시지가 설정 문구로 나온다 — 시안 1-3a 안내와 정렬")
		void 게이트_차단_메시지가_설정_문구로_나온다() throws Exception {
			초기_비밀번호_상태로_만든다();

			mockMvc.perform(get(ORG_PROFILE_URL).header(HttpHeaders.AUTHORIZATION, bearer(organizer)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.developCode").value(2441))
				.andExpect(jsonPath("$.message").value("초기 비밀번호를 설정해야 이용할 수 있습니다"));
		}
	}

	@Nested
	@DisplayName("인가 경계 — SecurityConfig 등록")
	class Authorization {

		// 검증: FR-AUTH-14
		@Test
		@DisplayName("비로그인으로 재설정 요청·확정 경로가 열려 있다")
		void 비로그인으로_재설정_요청과_확정_경로가_열려_있다() throws Exception {
			mockMvc.perform(post(RESET_REQUEST_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(emailBody(organizerEmail)))
				.andExpect(status().isOk());
			mockMvc.perform(post(RESET_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(resetBody("forged-" + UUID.randomUUID(), NEW_PASSWORD)))
				.andExpect(status().isBadRequest());
		}

		// 검증: FR-AUTH-14
		@Test
		@DisplayName("만료·위조 토큰이 헤더에 실려 와도 재설정 요청이 통과한다 — PUBLIC_AUTH_PATHS 동기화")
		void 만료_토큰이_실려_와도_재설정_요청이_통과한다() throws Exception {
			mockMvc.perform(post(RESET_REQUEST_URL)
					.header(HttpHeaders.AUTHORIZATION, "Bearer broken.token.value")
					.contentType(MediaType.APPLICATION_JSON)
					.content(emailBody(organizerEmail)))
				.andExpect(status().isOk());
		}

		// 검증: FR-AUTH-14
		@Test
		@DisplayName("비로그인의 상태 조회·변경은 401 이다")
		void 비로그인으로_상태_조회와_변경_경로는_401이다() throws Exception {
			mockMvc.perform(get(STATUS_URL))
				.andExpect(status().isUnauthorized());
			mockMvc.perform(post(CHANGE_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.content(changeBody(INITIAL_PASSWORD, NEW_PASSWORD)))
				.andExpect(status().isUnauthorized());
		}

		// 검증: FR-AUTH-14
		@Test
		@DisplayName("USER 와 ADMIN 도 상태 조회·변경을 쓸 수 있다 — 역할 무관 authenticated")
		void USER와_ADMIN도_비밀번호_상태와_변경을_쓸_수_있다() throws Exception {
			User user = saveUser("user-" + UUID.randomUUID() + "@fillmap.dev", INITIAL_PASSWORD, UserRole.USER);
			User admin = saveUser("admin-" + UUID.randomUUID() + "@fillmap.dev", INITIAL_PASSWORD, UserRole.ADMIN);

			for (User account : new User[] {user, admin}) {
				mockMvc.perform(get(STATUS_URL).header(HttpHeaders.AUTHORIZATION, bearer(account)))
					.andExpect(status().isOk());
				mockMvc.perform(post(CHANGE_URL)
						.header(HttpHeaders.AUTHORIZATION, bearer(account))
						.contentType(MediaType.APPLICATION_JSON)
						.content(changeBody(INITIAL_PASSWORD, NEW_PASSWORD)))
					.andExpect(status().isOk());
			}
		}
	}
}
