package com.msg.fillmap.badge.controller;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

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
import com.msg.fillmap.badge.dto.FeaturedBadgeResponseDto;
import com.msg.fillmap.badge.service.BadgeFeaturedService;
import com.msg.fillmap.user.entity.UserRole;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("BadgeController 대표 뱃지 교체")
class BadgeControllerTest {

	private static final long USER_ID = 42L;
	private static final String URL = "/api/badges/featured";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private BadgeFeaturedService badgeFeaturedService;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	@Test
	@DisplayName("교체 요청은 200과 적용된 대표 뱃지(rank 순)를 반환한다")
	void 교체_요청은_200과_적용된_대표_뱃지를_반환한다() throws Exception {
		given(badgeFeaturedService.replaceFeatured(anyLong(), anyList())).willReturn(List.of(
			new FeaturedBadgeResponseDto(3L, "EXPLORER_50", "탐험가 II", null, 1),
			new FeaturedBadgeResponseDto(7L, "RECORDER_50", "기록러 II", null, 2)));

		mockMvc.perform(put(URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"badgeIds\":[3, 7]}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.body[0].badgeId").value(3))
			.andExpect(jsonPath("$.body[0].rank").value(1))
			.andExpect(jsonPath("$.body[1].code").value("RECORDER_50"))
			.andExpect(jsonPath("$.body[1].rank").value(2));
	}

	@Test
	@DisplayName("3개 이상 지정하면 400 이다 — @Size global(신규 코드 아님, §D7)")
	void 삼개_이상_지정하면_400이다() throws Exception {
		mockMvc.perform(put(URL)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"badgeIds\":[1, 2, 3]}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("미인증 요청은 401 이다")
	void 미인증_요청은_401이다() throws Exception {
		mockMvc.perform(put(URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"badgeIds\":[1]}"))
			.andExpect(status().isUnauthorized());
	}
}
