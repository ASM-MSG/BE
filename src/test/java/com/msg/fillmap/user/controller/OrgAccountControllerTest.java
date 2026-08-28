package com.msg.fillmap.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.user.entity.OrgEmailChangeRequest;
import com.msg.fillmap.user.entity.OrgEmailChangeStatus;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.repository.OrgEmailChangeRequestRepository;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 행사 운영자 계정 설정과 첫 로그인 비밀번호 게이트 (MSG-497 FR-21·23, 실 DB). 게이트는 인가 체인
 * 뒤의 인터셉터라 목으로는 재현되지 않고, 아이디 변경 요청은 부분 유니크 인덱스 위의 UPSERT 라 실제
 * DB 가 있어야 의미가 있다. {@code @Transactional} 롤백 격리로 공유 로컬 DB 에 계정을 남기지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("행사 운영자 계정 설정 (MSG-497, 실 DB)")
class OrgAccountControllerTest {

	private static final String PROFILE_URL = "/api/org/profile";
	private static final String EMAIL_CHANGE_URL = "/api/org/email-change-request";
	private static final String INITIAL_PASSWORD = "Initial1234";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private OrgEmailChangeRequestRepository requestRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private TokenProvider tokenProvider;

	private User organizer;
	private String organizerEmail;

	@BeforeEach
	void setUp() {
		organizerEmail = "organizer-" + UUID.randomUUID() + "@fillmap.dev";
		organizer = saveUser(organizerEmail, UserRole.ORG);
	}

	private User saveUser(String email, UserRole role) {
		User user = User.createLocalUser(email, passwordEncoder.encode(INITIAL_PASSWORD), "담당자");
		ReflectionTestUtils.setField(user, "role", role);
		return userRepository.saveAndFlush(user);
	}

	private String bearer(User user) {
		return "Bearer " + tokenProvider.issueAccessToken(user.getId(), user.getRole());
	}

	private void 초기_비밀번호_상태로_만든다() {
		ReflectionTestUtils.setField(organizer, "passwordMustChange", true);
		userRepository.flush();
	}

	private String profileBody(String name, String phone) {
		return """
			{"contactName": "%s", "contactPhone": "%s"}""".formatted(name, phone);
	}

	private String emailChangeBody(String email) {
		return """
			{"requestedEmail": "%s"}""".formatted(email);
	}

	@Nested
	@DisplayName("담당자 정보 조회·수정")
	class Profile {

		// 검증: FR-USER-16
		@Test
		@DisplayName("담당자 이름과 연락처를 수정하면 조회에 반영된다")
		void ORG가_담당자_이름과_연락처를_수정하면_조회에_반영된다() throws Exception {
			mockMvc.perform(patch(PROFILE_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer(organizer))
					.contentType(MediaType.APPLICATION_JSON)
					.content(profileBody("김담당", "010-1234-5678")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.contactName").value("김담당"))
				.andExpect(jsonPath("$.data.contactPhone").value("010-1234-5678"));

			mockMvc.perform(get(PROFILE_URL).header(HttpHeaders.AUTHORIZATION, bearer(organizer)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.contactName").value("김담당"))
				.andExpect(jsonPath("$.data.contactPhone").value("010-1234-5678"));
		}

		// 검증: FR-USER-16
		@Test
		@DisplayName("연락처 형식 위반은 400 이다 — 하이픈만 있는 값도 거부된다")
		void 연락처_형식_위반은_400이다() throws Exception {
			for (String invalid : new String[] {"----------", "010", "010-1234-5678-9012-3456-7890"}) {
				mockMvc.perform(patch(PROFILE_URL)
						.header(HttpHeaders.AUTHORIZATION, bearer(organizer))
						.contentType(MediaType.APPLICATION_JSON)
						.content(profileBody("김담당", invalid)))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.developCode").value(400));
			}
		}

		// 검증: FR-USER-16
		@Test
		@DisplayName("담당자 이름 누락은 400 이다 — NOT NULL 컬럼까지 내려가지 않는다")
		void 담당자_이름_누락은_400이다() throws Exception {
			mockMvc.perform(patch(PROFILE_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer(organizer))
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"contactPhone": "010-1234-5678"}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(400));
		}

		// 검증: FR-USER-16
		@Test
		@DisplayName("조회에 아이디(이메일)가 읽기 전용으로 실린다")
		void 프로필_조회에_이메일이_읽기_전용으로_실린다() throws Exception {
			mockMvc.perform(get(PROFILE_URL).header(HttpHeaders.AUTHORIZATION, bearer(organizer)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.email").value(organizerEmail))
				.andExpect(jsonPath("$.data.contactPhone").doesNotExist());
		}

		// 검증: FR-AUTH-14
		@Test
		@DisplayName("USER 토큰의 접근은 403 이다 — 기존 matcher 회귀")
		void USER_토큰의_org_프로필_접근은_403이다() throws Exception {
			User user = saveUser("user-" + UUID.randomUUID() + "@fillmap.dev", UserRole.USER);

			mockMvc.perform(get(PROFILE_URL).header(HttpHeaders.AUTHORIZATION, bearer(user)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.developCode").value(403));
		}
	}

	@Nested
	@DisplayName("아이디 변경 요청")
	class EmailChange {

		// 검증: FR-USER-16
		@Test
		@DisplayName("요청이 대기 상태로 저장된다")
		void 아이디_변경_요청이_대기_상태로_저장된다() throws Exception {
			String requested = "new-" + UUID.randomUUID() + "@fillmap.dev";

			mockMvc.perform(post(EMAIL_CHANGE_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer(organizer))
					.contentType(MediaType.APPLICATION_JSON)
					.content(emailChangeBody(requested)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200));

			OrgEmailChangeRequest saved = requestRepository.findAllByUserId(organizer.getId()).getFirst();
			assertThat(saved.getRequestedEmail()).isEqualTo(requested);
			assertThat(saved.getStatus()).isEqualTo(OrgEmailChangeStatus.PENDING);
		}

		// 검증: FR-USER-16
		@Test
		@DisplayName("현재 아이디와 같은 이메일 요청은 1420 으로 거부된다")
		void 현재_아이디와_같은_이메일_요청은_거부된다() throws Exception {
			mockMvc.perform(post(EMAIL_CHANGE_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer(organizer))
					.contentType(MediaType.APPLICATION_JSON)
					.content(emailChangeBody(organizerEmail)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(1420));

			assertThat(requestRepository.findAllByUserId(organizer.getId())).isEmpty();
		}

		// 검증: FR-USER-16
		@Test
		@DisplayName("이메일 누락과 빈 문자열은 공통 400 이다")
		void 이메일_누락과_빈_문자열은_공통_400이다() throws Exception {
			for (String body : new String[] {"{}", emailChangeBody(""), emailChangeBody("   ")}) {
				mockMvc.perform(post(EMAIL_CHANGE_URL)
						.header(HttpHeaders.AUTHORIZATION, bearer(organizer))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.developCode").value(400));
			}
		}

		// 검증: FR-USER-16
		@Test
		@DisplayName("대기 요청이 있으면 재요청이 그 행을 갱신한다 — 행 수 1 유지")
		void 대기_요청이_있으면_재요청이_그_행을_갱신한다() throws Exception {
			String second = "second-" + UUID.randomUUID() + "@fillmap.dev";
			mockMvc.perform(post(EMAIL_CHANGE_URL)
				.header(HttpHeaders.AUTHORIZATION, bearer(organizer))
				.contentType(MediaType.APPLICATION_JSON)
				.content(emailChangeBody("first-" + UUID.randomUUID() + "@fillmap.dev")));

			mockMvc.perform(post(EMAIL_CHANGE_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer(organizer))
					.contentType(MediaType.APPLICATION_JSON)
					.content(emailChangeBody(second)))
				.andExpect(status().isOk());

			assertThat(requestRepository.findAllByUserId(organizer.getId()))
				.hasSize(1)
				.first()
				.extracting(OrgEmailChangeRequest::getRequestedEmail)
				.isEqualTo(second);
		}
	}

	@Nested
	@DisplayName("첫 로그인 비밀번호 게이트")
	class PasswordChangeGate {

		// 검증: FR-AUTH-15
		@Test
		@DisplayName("초기 비밀번호 상태의 ORG 는 콘솔 경로가 2441 로 막힌다")
		void 초기_비밀번호_상태의_ORG는_콘솔_경로가_2441로_막힌다() throws Exception {
			초기_비밀번호_상태로_만든다();

			mockMvc.perform(get(PROFILE_URL).header(HttpHeaders.AUTHORIZATION, bearer(organizer)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.developCode").value(2441));
			mockMvc.perform(post(EMAIL_CHANGE_URL)
					.header(HttpHeaders.AUTHORIZATION, bearer(organizer))
					.contentType(MediaType.APPLICATION_JSON)
					.content(emailChangeBody("new-" + UUID.randomUUID() + "@fillmap.dev")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.developCode").value(2441));
		}

		// 검증: FR-AUTH-15
		@Test
		@DisplayName("초기 비밀번호 상태에서도 상태 조회와 변경은 열려 있다 — 탈출구")
		void 초기_비밀번호_상태에서도_비밀번호_변경과_상태_조회는_열려_있다() throws Exception {
			초기_비밀번호_상태로_만든다();

			mockMvc.perform(get("/api/auth/password/status")
					.header(HttpHeaders.AUTHORIZATION, bearer(organizer)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.mustChange").value(true));
			mockMvc.perform(post("/api/auth/password/change")
					.header(HttpHeaders.AUTHORIZATION, bearer(organizer))
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"currentPassword": "%s", "newPassword": "Fillmap5678"}""".formatted(INITIAL_PASSWORD)))
				.andExpect(status().isOk());
		}

		// 검증: FR-AUTH-15
		@Test
		@DisplayName("초기 비밀번호 상태에서도 내 프로필 조회와 로그아웃은 열려 있다")
		void 초기_비밀번호_상태에서도_내_프로필_조회와_로그아웃은_열려_있다() throws Exception {
			초기_비밀번호_상태로_만든다();

			mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(organizer)))
				.andExpect(status().isOk());
			mockMvc.perform(post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, bearer(organizer)))
				.andExpect(status().isOk());
		}

		// 검증: FR-AUTH-15
		@Test
		@DisplayName("비밀번호를 변경하면 즉시 콘솔 경로가 통과한다 — 해제 즉시성")
		void 비밀번호를_변경하면_즉시_콘솔_경로가_통과한다() throws Exception {
			초기_비밀번호_상태로_만든다();
			String token = bearer(organizer);

			mockMvc.perform(post("/api/auth/password/change")
					.header(HttpHeaders.AUTHORIZATION, token)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"currentPassword": "%s", "newPassword": "Fillmap5678"}""".formatted(INITIAL_PASSWORD)))
				.andExpect(status().isOk());

			// 같은(재발급하지 않은) 토큰으로 바로 통과한다 — 클레임 스냅숏 방식이었다면 여기서 계속 막힌다.
			mockMvc.perform(get(PROFILE_URL).header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isOk());
		}

		// 검증: FR-AUTH-15
		@Test
		@DisplayName("플래그가 없는 ORG 는 게이트에 걸리지 않는다")
		void 플래그가_없는_ORG는_게이트에_걸리지_않는다() throws Exception {
			mockMvc.perform(get(PROFILE_URL).header(HttpHeaders.AUTHORIZATION, bearer(organizer)))
				.andExpect(status().isOk());
		}
	}
}
