package com.msg.fillmap.friend.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.hamcrest.Matchers;
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
import com.msg.fillmap.friend.dto.FriendCodeResponseDto;
import com.msg.fillmap.friend.dto.FriendListItemResponseDto;
import com.msg.fillmap.friend.dto.FriendPreviewResponseDto;
import com.msg.fillmap.friend.dto.FriendRequestCreateResponseDto;
import com.msg.fillmap.friend.dto.ReceivedFriendRequestResponseDto;
import com.msg.fillmap.friend.entity.FriendshipStatus;
import com.msg.fillmap.friend.service.FriendService;
import com.msg.fillmap.user.entity.GridColor;
import com.msg.fillmap.user.entity.UserRole;

/**
 * 친구 API 7종 컨트롤러 (MSG-185 §D4). UserProfileControllerTest 패턴 미러 — TokenProvider
 * 실 Bearer + @MockitoBean 정확값 스텁으로 principal userId 전달(사용자 격리)까지 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("FriendController 친구 API")
class FriendControllerTest {

	private static final long USER_ID = 42L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private FriendService friendService;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	@Test
	@DisplayName("내 친구 코드를 조회한다 — 200 · friendCode (FR-1)")
	void 내_친구_코드를_조회한다() throws Exception {
		given(friendService.getMyFriendCode(USER_ID)).willReturn(new FriendCodeResponseDto("AB3DE7GH"));

		mockMvc.perform(get("/api/friends/code").header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.friendCode").value("AB3DE7GH"));
	}

	@Test
	@DisplayName("코드 미리보기는 닉네임을 반환한다 (FR-3)")
	void 코드_미리보기는_닉네임을_반환한다() throws Exception {
		given(friendService.preview("AB3DE7GH")).willReturn(new FriendPreviewResponseDto("채우미"));

		mockMvc.perform(get("/api/friends/preview")
				.param("code", "AB3DE7GH")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.nickname").value("채우미"));
	}

	@Test
	@DisplayName("친구 요청은 응답 status 로 신규/자동수락을 구분한다 (FR-4·8)")
	void 친구_요청은_응답_status로_신규와_자동수락을_구분한다() throws Exception {
		given(friendService.request(USER_ID, "AB3DE7GH"))
			.willReturn(new FriendRequestCreateResponseDto(FriendshipStatus.PENDING));

		mockMvc.perform(post("/api/friends/requests")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"friendCode\":\"AB3DE7GH\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.status").value("PENDING"));
	}

	@Test
	@DisplayName("빈 친구 코드 요청은 400 이다 (@NotBlank)")
	void 빈_친구_코드_요청은_400을_반환한다() throws Exception {
		mockMvc.perform(post("/api/friends/requests")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"friendCode\":\"\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("받은 요청 목록은 보낸 사람 정보를 담는다 (FR-9)")
	void 받은_요청_목록은_보낸_사람_정보를_담는다() throws Exception {
		given(friendService.getReceivedRequests(USER_ID)).willReturn(List.of(
			new ReceivedFriendRequestResponseDto(3L, "채우미", null, LocalDateTime.of(2026, 8, 3, 12, 0))));

		mockMvc.perform(get("/api/friends/requests/received").header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data", Matchers.hasSize(1)))
			.andExpect(jsonPath("$.data[0].requesterId").value(3))
			.andExpect(jsonPath("$.data[0].nickname").value("채우미"))
			.andExpect(jsonPath("$.data[0].profileImageUrl").value(Matchers.nullValue()))
			.andExpect(jsonPath("$.data[0].requestedAt").value("2026-08-03T12:00:00"));
	}

	@Test
	@DisplayName("친구 목록은 사용자 id·닉네임·프로필 이미지·도감 색상을 담는다 (MSG-186 FR-3)")
	void 친구_목록은_친구_정보를_담는다() throws Exception {
		given(friendService.getFriends(USER_ID, null)).willReturn(List.of(
			new FriendListItemResponseDto(7L, "채우미", "https://cdn.example.com/p.png", GridColor.PINK)));

		mockMvc.perform(get("/api/friends").header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data", Matchers.hasSize(1)))
			.andExpect(jsonPath("$.data[0].userId").value(7))
			.andExpect(jsonPath("$.data[0].nickname").value("채우미"))
			.andExpect(jsonPath("$.data[0].profileImageUrl").value("https://cdn.example.com/p.png"))
			.andExpect(jsonPath("$.data[0].gridColor").value("PINK"));
	}

	@Test
	@DisplayName("sort 파라미터가 서비스로 전달된다 (FR-2)")
	void sort_파라미터가_서비스로_전달된다() throws Exception {
		given(friendService.getFriends(USER_ID, "nickname")).willReturn(List.of());

		mockMvc.perform(get("/api/friends")
				.param("sort", "nickname")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data", Matchers.hasSize(0)));

		verify(friendService).getFriends(USER_ID, "nickname");
	}

	@Test
	@DisplayName("친구 목록은 토큰 없이 호출하면 401 이다")
	void 친구_목록은_토큰_없이_호출하면_401이다() throws Exception {
		mockMvc.perform(get("/api/friends"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("요청을 수락한다 — 경로의 requesterId 와 principal 이 서비스로 전달된다 (FR-10·13)")
	void 요청을_수락한다() throws Exception {
		mockMvc.perform(post("/api/friends/requests/3/accept").header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data").value(Matchers.nullValue()));

		verify(friendService).accept(USER_ID, 3L);
	}

	@Test
	@DisplayName("요청을 거절한다 (FR-11·13)")
	void 요청을_거절한다() throws Exception {
		mockMvc.perform(post("/api/friends/requests/3/reject").header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data").value(Matchers.nullValue()));

		verify(friendService).reject(USER_ID, 3L);
	}

	@Test
	@DisplayName("친구를 삭제한다 (FR-12·13)")
	void 친구를_삭제한다() throws Exception {
		mockMvc.perform(delete("/api/friends/7").header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data").value(Matchers.nullValue()));

		verify(friendService).deleteFriend(USER_ID, 7L);
	}

	@Test
	@DisplayName("토큰 없이 호출하면 401 이다 — anyRequest().authenticated() 커버")
	void 토큰_없이_호출하면_401이다() throws Exception {
		mockMvc.perform(get("/api/friends/code"))
			.andExpect(status().isUnauthorized());
	}
}
