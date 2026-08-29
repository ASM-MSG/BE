package com.msg.fillmap.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.global.mail.MailSender;
import com.msg.fillmap.user.entity.AuthProvider;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 관리자 계정 직접 발급·재발송·목록 (MSG-499 API 6~8, 실 DB). 발급 계정의 형태와 재발송 대상 판정이
 * 실제 저장 상태에 걸려 있어 목으로는 잡히지 않는다 — 메일 발송만 목으로 갈아 끼운다.
 * {@code @Transactional} 롤백 격리로 공유 로컬 DB 에 계정을 남기지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("관리자 계정 직접 발급·재발송·목록 (MSG-499, 실 DB)")
class AdminOrgAccountControllerTest {

	private static final String ACCOUNTS_URL = "/api/admin/organizations";
	private static final String INITIAL_PASSWORD = "Initial1234";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private TokenProvider tokenProvider;

	@Autowired
	private EntityManager entityManager;

	@MockitoBean
	private MailSender mailSender;

	private String adminToken;

	@BeforeEach
	void setUp() {
		User admin = saveUser("admin-" + UUID.randomUUID() + "@fillmap.dev", UserRole.ADMIN, AuthProvider.LOCAL);
		adminToken = "Bearer " + tokenProvider.issueAccessToken(admin.getId(), admin.getRole());
	}

	private User saveUser(String email, UserRole role, AuthProvider provider) {
		User user = User.createLocalUser(email, passwordEncoder.encode(INITIAL_PASSWORD), "담당자");
		ReflectionTestUtils.setField(user, "role", role);
		ReflectionTestUtils.setField(user, "provider", provider);
		if (provider != AuthProvider.LOCAL) {
			ReflectionTestUtils.setField(user, "oid", UUID.randomUUID().toString());
		}
		return userRepository.saveAndFlush(user);
	}

	private User saveOrgAccount(String email, String orgName, boolean mustChange) {
		User user = User.createOrgUser(email, passwordEncoder.encode(INITIAL_PASSWORD), "김담당",
			"010-1234-5678", orgName);
		if (!mustChange) {
			user.changePassword(passwordEncoder.encode("Fillmap5678"));
		}
		return userRepository.saveAndFlush(user);
	}

	private String uniqueEmail() {
		return "org-" + UUID.randomUUID() + "@fillmap.dev";
	}

	private String createBody(String email) {
		return """
			{"orgName": "부산진구청", "contactName": "김담당", "email": "%s", "contactPhone": "010-1234-5678"}"""
			.formatted(email);
	}

	private String 발송된_초기_비밀번호() {
		ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
		then(mailSender).should().send(any(), any(), bodyCaptor.capture());
		String body = bodyCaptor.getValue();
		int begin = body.indexOf("초기 비밀번호: ") + "초기 비밀번호: ".length();
		return body.substring(begin, body.indexOf('\n', begin));
	}

	@Nested
	@DisplayName("직접 발급")
	class DirectIssue {

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("직접 발급은 요청 행 없이 계정을 생성하고 초기 비밀번호를 발송한다")
		void 직접_발급은_요청_행_없이_계정을_생성하고_초기_비밀번호를_발송한다() throws Exception {
			String email = uniqueEmail();

			String body = mockMvc.perform(post(ACCOUNTS_URL)
					.header(HttpHeaders.AUTHORIZATION, adminToken)
					.contentType(MediaType.APPLICATION_JSON)
					.content(createBody(email)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.emailSent").value(true))
				.andReturn().getResponse().getContentAsString();

			ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
			then(mailSender).should().send(toCaptor.capture(), any(), any());
			assertThat(toCaptor.getValue()).isEqualTo(email);
			String plainPassword = 발송된_초기_비밀번호();
			assertThat(body).doesNotContain(plainPassword);

			Long userId = ((Number) JsonPath.read(body, "$.data.userId")).longValue();
			entityManager.flush();
			entityManager.clear();
			User issued = userRepository.findById(userId).orElseThrow();
			assertThat(issued.getRole()).isEqualTo(UserRole.ORG);
			assertThat(issued.getProvider()).isEqualTo(AuthProvider.LOCAL);
			assertThat(issued.isPasswordMustChange()).isTrue();
			assertThat(issued.getOrgName()).isEqualTo("부산진구청");
			assertThat(passwordEncoder.matches(plainPassword, issued.getPasswordHash())).isTrue();
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("직접 발급도 이메일 중복이면 1409 다")
		void 직접_발급도_이메일_중복이면_1409다() throws Exception {
			String email = uniqueEmail();
			saveUser(email, UserRole.USER, AuthProvider.LOCAL);

			mockMvc.perform(post(ACCOUNTS_URL)
					.header(HttpHeaders.AUTHORIZATION, adminToken)
					.contentType(MediaType.APPLICATION_JSON)
					.content(createBody(email)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.developCode").value(1409));

			then(mailSender).should(never()).send(any(), any(), any());
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("연락처 없이도 발급되고 기관명·담당자·이메일이 비면 400 이다")
		void 연락처_없이도_발급되고_필수_필드가_비면_400이다() throws Exception {
			mockMvc.perform(post(ACCOUNTS_URL)
					.header(HttpHeaders.AUTHORIZATION, adminToken)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"orgName": "부산진구청", "contactName": "김담당", "email": "%s"}""".formatted(uniqueEmail())))
				.andExpect(status().isOk());

			mockMvc.perform(post(ACCOUNTS_URL)
					.header(HttpHeaders.AUTHORIZATION, adminToken)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"contactName": "김담당", "email": "%s"}""".formatted(uniqueEmail())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(400));
		}
	}

	@Nested
	@DisplayName("초기 비밀번호 재발송")
	class Resend {

		private String resendUrl(Long userId) {
			return ACCOUNTS_URL + "/" + userId + "/resend-password";
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("재발송하면 새 초기 비밀번호로 교체되어 발송되고 이전 비밀번호는 로그인에 실패한다")
		void 재발송하면_새_초기_비밀번호로_교체되어_발송되고_이전_비밀번호는_로그인에_실패한다() throws Exception {
			String email = uniqueEmail();
			User account = saveOrgAccount(email, "부산진구청", true);

			mockMvc.perform(post(resendUrl(account.getId())).header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.emailSent").value(true));

			String reissued = 발송된_초기_비밀번호();
			entityManager.flush();
			entityManager.clear();
			User reloaded = userRepository.findById(account.getId()).orElseThrow();
			assertThat(passwordEncoder.matches(reissued, reloaded.getPasswordHash())).isTrue();
			// 강제 변경 플래그는 유지된다 — 여전히 발급자가 아는 값이다.
			assertThat(reloaded.isPasswordMustChange()).isTrue();

			mockMvc.perform(post("/api/auth/login")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"email": "%s", "password": "%s"}""".formatted(email, INITIAL_PASSWORD)))
				.andExpect(status().isUnauthorized());
			mockMvc.perform(post("/api/auth/login")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"email": "%s", "password": "%s"}""".formatted(email, reissued)))
				.andExpect(status().isOk());
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("초기 로그인을 마친 계정의 재발송은 1423 이다")
		void 초기_로그인을_마친_계정의_재발송은_1423다() throws Exception {
			User account = saveOrgAccount(uniqueEmail(), "부산진구청", false);

			mockMvc.perform(post(resendUrl(account.getId())).header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.developCode").value(1423));

			then(mailSender).should(never()).send(any(), any(), any());
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("일반 사용자 계정의 재발송은 1423 이다")
		void 일반_사용자_계정의_재발송은_1423다() throws Exception {
			User user = saveUser(uniqueEmail(), UserRole.USER, AuthProvider.LOCAL);

			mockMvc.perform(post(resendUrl(user.getId())).header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.developCode").value(1423));
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("없는 사용자의 재발송은 1404 다")
		void 없는_사용자의_재발송은_1404다() throws Exception {
			mockMvc.perform(post(resendUrl(99999999L)).header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.developCode").value(1404));
		}
	}

	@Nested
	@DisplayName("계정 목록")
	class Accounts {

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("계정 목록에 기관명과 초기 로그인 전 여부가 최신순으로 실린다")
		void 계정_목록에_기관명과_초기_로그인_전_여부가_최신순으로_실린다() throws Exception {
			saveOrgAccount(uniqueEmail(), "먼저 발급", false);
			String newerEmail = uniqueEmail();
			saveOrgAccount(newerEmail, "나중 발급", true);

			mockMvc.perform(get(ACCOUNTS_URL).header(HttpHeaders.AUTHORIZATION, adminToken).param("size", "100"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.accounts[0].email").value(newerEmail))
				.andExpect(jsonPath("$.data.accounts[0].orgName").value("나중 발급"))
				.andExpect(jsonPath("$.data.accounts[0].contactName").value("김담당"))
				.andExpect(jsonPath("$.data.accounts[0].mustChange").value(true))
				.andExpect(jsonPath("$.data.accounts[0].provider").value("LOCAL"));
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("이메일 검색은 완전 일치이고 비 LOCAL 계정은 목록에서 제외된다")
		void 이메일이_같은_비LOCAL_계정은_계정_목록에서_제외된다() throws Exception {
			String orgEmail = uniqueEmail();
			saveOrgAccount(orgEmail, "부산진구청", true);
			// 같은 역할이라도 제공자가 LOCAL 이 아니면 이 발급 경로의 계정이 아니라 목록에서 빠진다.
			String kakaoEmail = uniqueEmail();
			User kakaoOrg = saveUser(kakaoEmail, UserRole.ORG, AuthProvider.KAKAO);
			assertThat(kakaoOrg.getProvider()).isEqualTo(AuthProvider.KAKAO);

			mockMvc.perform(get(ACCOUNTS_URL)
					.header(HttpHeaders.AUTHORIZATION, adminToken)
					.param("email", orgEmail))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalElements").value(1))
				.andExpect(jsonPath("$.data.accounts[0].email").value(orgEmail));

			mockMvc.perform(get(ACCOUNTS_URL)
					.header(HttpHeaders.AUTHORIZATION, adminToken)
					.param("email", kakaoEmail))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalElements").value(0));
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("계정 목록의 페이지 범위 밖은 1425 다")
		void 계정_목록의_페이지_범위_밖은_1425다() throws Exception {
			for (String[] params : new String[][] {{"page", "-1"}, {"size", "0"}, {"size", "101"}}) {
				mockMvc.perform(get(ACCOUNTS_URL)
						.header(HttpHeaders.AUTHORIZATION, adminToken)
						.param(params[0], params[1]))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.developCode").value(1425));
			}
		}
	}

	@Nested
	@DisplayName("발급 계정의 첫 로그인 (MSG-496·497 접점)")
	class IssuedAccountGate {

		private static final String CONSOLE_URL = "/api/org/profile";

		/** 발급 → 실제 로그인까지 한 번에 — 발급 계정이 곧바로 기존 로그인 경로를 타는지 확인한다. */
		private String 발급받고_로그인한다(String email) throws Exception {
			mockMvc.perform(post(ACCOUNTS_URL)
					.header(HttpHeaders.AUTHORIZATION, adminToken)
					.contentType(MediaType.APPLICATION_JSON)
					.content(createBody(email)))
				.andExpect(status().isOk());
			String initial = 발송된_초기_비밀번호();
			String login = mockMvc.perform(post("/api/auth/login")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"email": "%s", "password": "%s"}""".formatted(email, initial)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.role").value("ORG"))
				.andReturn().getResponse().getContentAsString();
			return "Bearer " + JsonPath.read(login, "$.data.accessToken");
		}

		// 검증: FR-AUTH-15
		@Test
		@DisplayName("발급된 계정은 로그인 후 콘솔 경로가 2441 로 막힌다")
		void 발급된_계정은_로그인_후_org_경로가_2441로_막힌다() throws Exception {
			String token = 발급받고_로그인한다(uniqueEmail());

			mockMvc.perform(get(CONSOLE_URL).header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.developCode").value(2441));
		}

		// 검증: FR-AUTH-15
		@Test
		@DisplayName("발급된 계정이 비밀번호를 변경하면 콘솔 경로가 통과한다")
		void 발급된_계정이_비밀번호를_변경하면_org_경로가_통과한다() throws Exception {
			String email = uniqueEmail();
			mockMvc.perform(post(ACCOUNTS_URL)
					.header(HttpHeaders.AUTHORIZATION, adminToken)
					.contentType(MediaType.APPLICATION_JSON)
					.content(createBody(email)))
				.andExpect(status().isOk());
			String initial = 발송된_초기_비밀번호();
			String login = mockMvc.perform(post("/api/auth/login")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"email": "%s", "password": "%s"}""".formatted(email, initial)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
			String token = "Bearer " + JsonPath.read(login, "$.data.accessToken");

			mockMvc.perform(post("/api/auth/password/change")
					.header(HttpHeaders.AUTHORIZATION, token)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"currentPassword": "%s", "newPassword": "Fillmap5678"}""".formatted(initial)))
				.andExpect(status().isOk());

			mockMvc.perform(get(CONSOLE_URL).header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.email").value(email));
		}
	}
}
