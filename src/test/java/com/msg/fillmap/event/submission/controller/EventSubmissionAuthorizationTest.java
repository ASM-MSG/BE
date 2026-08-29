package com.msg.fillmap.event.submission.controller;

import static com.msg.fillmap.event.submission.EventSubmissionFixtures.GWANGALLI_RECT;
import static com.msg.fillmap.event.submission.EventSubmissionFixtures.festivalBody;
import static com.msg.fillmap.event.submission.EventSubmissionFixtures.location;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 신청 API 의 인가 회귀 (MSG-498). 이 티켓은 인가 코드를 한 줄도 만들지 않는다 — SecurityConfig 의
 * {@code /api/org/**} matcher(MSG-496)와 비밀번호 게이트 인터셉터(MSG-497)가 프리픽스로 새 경로를 자동으로
 * 덮는다는 것이 스펙의 전제이고, 그 전제가 실경로에서 참인지를 여기서 확인한다.
 * <p>
 * 게이트 검증에 실 DB 가 필요한 이유는 인터셉터가 매 요청 users 를 조회해 판정하기 때문이다(토큰 클레임이
 * 아니다 — 그래야 변경 즉시 풀린다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("행사 등재 신청 인가 (MSG-498, 실 DB)")
class EventSubmissionAuthorizationTest {

	private static final String URL = "/api/org/event-submissions";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private TokenProvider tokenProvider;

	private User organizer;

	@BeforeEach
	void setUp() {
		organizer = saveUser(UserRole.ORG);
	}

	private User saveUser(UserRole role) {
		User user = User.createLocalUser("m498-auth-" + UUID.randomUUID() + "@fillmap.dev",
			passwordEncoder.encode("Initial1234"), "담당자");
		ReflectionTestUtils.setField(user, "role", role);
		return userRepository.saveAndFlush(user);
	}

	private String bearer(User user) {
		return "Bearer " + tokenProvider.issueAccessToken(user.getId(), user.getRole());
	}

	private String body() {
		return festivalBody(organizer.getId(), location(GWANGALLI_RECT));
	}

	// 검증: FR-AUTH-14
	@Test
	@DisplayName("비로그인 신청 제출은 401 이다")
	void 비로그인으로_신청_API_접근은_401이다() throws Exception {
		mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body()))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.developCode").value(2403));

		mockMvc.perform(get(URL + "/my"))
			.andExpect(status().isUnauthorized());
	}

	// 검증: FR-AUTH-14
	@Test
	@DisplayName("USER 토큰 신청 제출은 403 이다 — 콘솔은 행사 운영자 전용이다")
	void USER_토큰으로_신청_API_접근은_403이다() throws Exception {
		mockMvc.perform(post(URL)
				.header(HttpHeaders.AUTHORIZATION, bearer(saveUser(UserRole.USER)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.developCode").value(403));
	}

	// 검증: FR-AUTH-14
	@Test
	@DisplayName("ADMIN 토큰 신청 제출도 403 이다 — 관리자에게도 열지 않는다")
	void ADMIN_토큰으로_신청_API_접근은_403이다() throws Exception {
		mockMvc.perform(post(URL)
				.header(HttpHeaders.AUTHORIZATION, bearer(saveUser(UserRole.ADMIN)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.developCode").value(403));
	}

	// 검증: FR-AUTH-15
	@Test
	@DisplayName("초기 비밀번호 상태의 ORG 토큰은 신청 제출도 차단된다 — 프리픽스 게이트가 새 경로를 덮는다")
	void mustChange_상태의_ORG_토큰으로_신청_제출은_차단된다() throws Exception {
		ReflectionTestUtils.setField(organizer, "passwordMustChange", true);
		userRepository.flush();

		mockMvc.perform(post(URL)
				.header(HttpHeaders.AUTHORIZATION, bearer(organizer))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.developCode").value(2441));

		mockMvc.perform(get(URL + "/my").header(HttpHeaders.AUTHORIZATION, bearer(organizer)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.developCode").value(2441));
	}
}
