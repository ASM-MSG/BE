package com.msg.fillmap.user.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.user.dto.UserProfileResponseDto;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.service.UserService;

/**
 * 프로필 조회 · 닉네임 수정 컨트롤러 (MSG-203 FR-1~4). BadgeControllerTest 패턴 미러 —
 * TokenProvider 실 Bearer + @MockitoBean 정확값 스텁(principal userId 전달 검증).
 * 계정 삭제(DELETE /me)와 컨트롤러를 공유하지만 축이 달라 테스트 클래스는 분리한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("UserController 프로필 조회 · 닉네임 수정")
class UserProfileControllerTest {

	private static final long USER_ID = 42L;
	private static final String ME_URL = "/api/users/me";
	private static final String NICKNAME_URL = "/api/users/me/nickname";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private UserService userService;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	@Test
	@DisplayName("내 프로필을 조회한다 — 200 · email · nickname (FR-1)")
	void 내_프로필을_조회한다() throws Exception {
		// 정확값 스텁 — principal userId(토큰의 USER_ID)가 서비스에 그대로 전달돼야만 매치된다(사용자 격리).
		given(userService.getMyProfile(USER_ID))
			.willReturn(new UserProfileResponseDto("user@fillmap.dev", "채우미"));

		mockMvc.perform(get(ME_URL).header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.body.email").value("user@fillmap.dev"))
			.andExpect(jsonPath("$.body.nickname").value("채우미"));
	}

	@Test
	@DisplayName("닉네임을 수정하면 변경 후 프로필을 반환한다 (FR-2·D2)")
	void 닉네임을_수정하면_변경_후_프로필을_반환한다() throws Exception {
		given(userService.updateNickname(USER_ID, "새닉네임"))
			.willReturn(new UserProfileResponseDto("user@fillmap.dev", "새닉네임"));

		mockMvc.perform(put(NICKNAME_URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"nickname\":\"새닉네임\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.body.nickname").value("새닉네임"));
	}

	@Test
	@DisplayName("빈 닉네임은 400 이다 (FR-3, @NotBlank)")
	void 빈_닉네임은_400을_반환한다() throws Exception {
		mockMvc.perform(put(NICKNAME_URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"nickname\":\"\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("한 글자 닉네임은 400 이다 (FR-3, @Size 하한 밖)")
	void 한_글자_닉네임은_400을_반환한다() throws Exception {
		mockMvc.perform(put(NICKNAME_URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"nickname\":\"가\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("스물한 글자 닉네임은 400 이다 (FR-3, @Size 상한 밖)")
	void 스물한_글자_닉네임은_400을_반환한다() throws Exception {
		mockMvc.perform(put(NICKNAME_URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"nickname\":\"" + "가".repeat(21) + "\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("두 글자·스무 글자 닉네임은 통과한다 (FR-2, @Size 경계 안)")
	void 두_글자와_스무_글자_닉네임은_통과한다() throws Exception {
		given(userService.updateNickname(eq(USER_ID), anyString()))
			.willReturn(new UserProfileResponseDto("user@fillmap.dev", "무관"));

		mockMvc.perform(put(NICKNAME_URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"nickname\":\"가나\"}"))
			.andExpect(status().isOk());

		mockMvc.perform(put(NICKNAME_URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"nickname\":\"" + "가".repeat(20) + "\"}"))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("토큰 없는 프로필 조회는 401 이다 (FR-4)")
	void 토큰_없는_프로필_조회는_401을_반환한다() throws Exception {
		mockMvc.perform(get(ME_URL))
			.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("토큰 없는 닉네임 수정은 401 이다 (FR-4)")
	void 토큰_없는_닉네임_수정은_401을_반환한다() throws Exception {
		mockMvc.perform(put(NICKNAME_URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"nickname\":\"새닉네임\"}"))
			.andExpect(status().isUnauthorized());
	}
}
