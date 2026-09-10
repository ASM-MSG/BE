package com.msg.fillmap.user.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.dto.BlockedUserResponseDto;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.service.UserBlockService;

/**
 * 사용자 차단 3경로 컨트롤러 (MSG-569). UserProfileControllerTest 패턴 — TokenProvider 실 Bearer +
 * @MockitoBean 정확값 스텁으로 principal userId 전달을 검증한다. 인가 계약(익명 401·ORG 403·USER 200)은
 * SecurityConfig 무수정 전제라 이 테스트가 그 전제를 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("UserBlockController 차단·해제·목록")
class UserBlockControllerTest {

	private static final long USER_ID = 42L;
	private static final long TARGET_ID = 7L;
	private static final String BLOCK_URL = "/api/users/" + TARGET_ID + "/block";
	private static final String LIST_URL = "/api/users/me/blocks";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private UserBlockService userBlockService;

	private String bearer(UserRole role) {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, role);
	}

	@Nested
	@DisplayName("principal 전달")
	class PrincipalPassing {

		// 검증: FR-MOD-15, AC-569-01, AC-569-15
		@Test
		@DisplayName("차단은 토큰의 userId 와 경로 userId 를 서비스에 그대로 넘긴다")
		void 차단은_토큰의_userId와_경로_userId를_서비스에_그대로_넘긴다() throws Exception {
			mockMvc.perform(post(BLOCK_URL).header(HttpHeaders.AUTHORIZATION, bearer(UserRole.USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200))
				.andExpect(jsonPath("$.data").value(nullValue()));

			then(userBlockService).should().block(USER_ID, TARGET_ID);
		}

		// 검증: FR-MOD-15, AC-569-01
		@Test
		@DisplayName("자기 자신 차단은 400 에 1430 이다")
		void 자기_자신_차단은_400에_1430이다() throws Exception {
			willThrow(new ApiException(UserErrorCode.SELF_BLOCK)).given(userBlockService).block(USER_ID, USER_ID);

			mockMvc.perform(post("/api/users/" + USER_ID + "/block")
					.header(HttpHeaders.AUTHORIZATION, bearer(UserRole.USER)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(1430));
		}

		// 검증: FR-MOD-15, AC-569-03, AC-569-15
		@Test
		@DisplayName("해제는 토큰의 userId 와 경로 userId 를 서비스에 그대로 넘긴다")
		void 해제는_토큰의_userId와_경로_userId를_서비스에_그대로_넘긴다() throws Exception {
			mockMvc.perform(delete(BLOCK_URL).header(HttpHeaders.AUTHORIZATION, bearer(UserRole.USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200));

			then(userBlockService).should().unblock(USER_ID, TARGET_ID);
		}

		// 검증: FR-MOD-15, AC-569-04, AC-569-15
		@Test
		@DisplayName("목록은 토큰의 userId 로 조회하고 항목 4필드를 UTC Z 로 직렬화한다")
		void 목록은_토큰의_userId로_조회하고_항목_4필드를_직렬화한다() throws Exception {
			given(userBlockService.getBlockedUsers(USER_ID)).willReturn(List.of(
				new BlockedUserResponseDto(TARGET_ID, "상대방", null, LocalDateTime.of(2026, 9, 8, 3, 10))));

			mockMvc.perform(get(LIST_URL).header(HttpHeaders.AUTHORIZATION, bearer(UserRole.USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data", hasSize(1)))
				.andExpect(jsonPath("$.data[0].userId").value(TARGET_ID))
				.andExpect(jsonPath("$.data[0].nickname").value("상대방"))
				.andExpect(jsonPath("$.data[0].profileImageUrl").value(nullValue()))
				.andExpect(jsonPath("$.data[0].blockedAt").value("2026-09-08T03:10:00Z"));
		}

		// 검증: FR-MOD-15
		// 매핑 없는 경로의 500은 GlobalExceptionHandler 에 NoResourceFoundException 핸들러가 없는 기존 전역 동작이라
		// 이 티켓 범위 밖. 여기서 검증하는 것은 me 가 {userId:[0-9]+} 에 잡히지 않아 서비스가 호출되지 않는다는 것뿐.
		@Test
		@DisplayName("경로 변수는 숫자만 받는다 — /api/users/me/block 은 차단 핸들러에 잡히지 않는다")
		void 경로_변수는_숫자만_받는다() throws Exception {
			mockMvc.perform(post("/api/users/me/block").header(HttpHeaders.AUTHORIZATION, bearer(UserRole.USER)))
				.andExpect(status().is5xxServerError());

			then(userBlockService).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("인가 계약 (SecurityConfig 무수정)")
	class AccessContract {

		// 검증: FR-MOD-15, AC-569-15
		@Test
		@DisplayName("토큰 없이 부르면 3경로 전부 401 이다")
		void 토큰_없이_부르면_3경로_전부_401이다() throws Exception {
			mockMvc.perform(post(BLOCK_URL)).andExpect(status().isUnauthorized());
			mockMvc.perform(delete(BLOCK_URL)).andExpect(status().isUnauthorized());
			mockMvc.perform(get(LIST_URL)).andExpect(status().isUnauthorized());

			then(userBlockService).shouldHaveNoInteractions();
		}

		// 검증: FR-MOD-15, AC-569-15
		@Test
		@DisplayName("ORG 계정은 3경로 전부 403 이다 (행사 운영자 권한은 콘솔에만)")
		void ORG_계정은_3경로_전부_403이다() throws Exception {
			mockMvc.perform(post(BLOCK_URL).header(HttpHeaders.AUTHORIZATION, bearer(UserRole.ORG)))
				.andExpect(status().isForbidden());
			mockMvc.perform(delete(BLOCK_URL).header(HttpHeaders.AUTHORIZATION, bearer(UserRole.ORG)))
				.andExpect(status().isForbidden());
			mockMvc.perform(get(LIST_URL).header(HttpHeaders.AUTHORIZATION, bearer(UserRole.ORG)))
				.andExpect(status().isForbidden());

			then(userBlockService).shouldHaveNoInteractions();
		}
	}
}
