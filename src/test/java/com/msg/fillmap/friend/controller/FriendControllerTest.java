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
import com.msg.fillmap.friend.dto.FriendCollectionGridResponseDto;
import com.msg.fillmap.friend.dto.FriendListItemResponseDto;
import com.msg.fillmap.friend.dto.FriendPreviewResponseDto;
import com.msg.fillmap.friend.dto.FriendProfileResponseDto;
import com.msg.fillmap.friend.dto.FriendRequestCreateResponseDto;
import com.msg.fillmap.friend.dto.ReceivedFriendRequestResponseDto;
import com.msg.fillmap.friend.entity.FriendshipStatus;
import com.msg.fillmap.friend.service.FriendService;
import com.msg.fillmap.grid.dto.RegionUnit;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.grid.service.OccupiedGridPage;
import com.msg.fillmap.grid.service.OccupiedGridView;
import com.msg.fillmap.grid.service.RegionAggregateView;
import com.msg.fillmap.user.entity.GridColor;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.usergrid.dto.CollectionSummaryResponseDto;
import com.msg.fillmap.video.dto.FriendGridVideoResponseDto;

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
	@DisplayName("친구 프로필은 프로필·도감 요약·최근 수집 격자를 한 응답에 담는다 (MSG-186 FR-5·6)")
	void 친구_프로필은_요약과_최근_수집_격자를_담는다() throws Exception {
		given(friendService.getFriendProfile(USER_ID, 7L)).willReturn(new FriendProfileResponseDto(
			"채우미", null, GridColor.PINK,
			new CollectionSummaryResponseDto(15, 42L, 6, 12, 21, 7),
			List.of(new FriendCollectionGridResponseDto("19422_9582", 19422, 9582,
				LocalDateTime.of(2026, 7, 20, 18, 3, 11), LocalDateTime.of(2026, 7, 21, 9, 12, 0),
				3, "https://signed/thumb.jpg", "서울특별시 강남구 역삼1동", "서면", "I-9"))));

		mockMvc.perform(get("/api/friends/7/profile").header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.nickname").value("채우미"))
			.andExpect(jsonPath("$.data.profileImageUrl").value(Matchers.nullValue()))
			.andExpect(jsonPath("$.data.gridColor").value("PINK"))
			.andExpect(jsonPath("$.data.summary.totalGridCount").value(15))
			.andExpect(jsonPath("$.data.summary.totalVideoCount").value(42))
			.andExpect(jsonPath("$.data.summary.visitedRegionCount").value(6))
			.andExpect(jsonPath("$.data.recentGrids", Matchers.hasSize(1)))
			.andExpect(jsonPath("$.data.recentGrids[0].gridId").value("19422_9582"))
			.andExpect(jsonPath("$.data.recentGrids[0].gridY").value(19422))
			.andExpect(jsonPath("$.data.recentGrids[0].gridX").value(9582))
			.andExpect(jsonPath("$.data.recentGrids[0].firstCollectedAt").value("2026-07-20T18:03:11"))
			.andExpect(jsonPath("$.data.recentGrids[0].lastUploadedAt").value("2026-07-21T09:12:00"))
			.andExpect(jsonPath("$.data.recentGrids[0].videoCount").value(3))
			.andExpect(jsonPath("$.data.recentGrids[0].thumbnailUrl").value("https://signed/thumb.jpg"))
			.andExpect(jsonPath("$.data.recentGrids[0].regionName").value("서울특별시 강남구 역삼1동"))
			.andExpect(jsonPath("$.data.recentGrids[0].zoneName").value("서면"))
			.andExpect(jsonPath("$.data.recentGrids[0].zoneCell").value("I-9"))
			// 영상 ID 미노출 — 비공개 영상의 존재가 id 로 새지 않아야 한다 (§D6 의도적 델타).
			.andExpect(jsonPath("$.data.recentGrids[0].coverVideoId").doesNotExist());
	}

	@Test
	@DisplayName("친구 격자 뷰포트 응답은 내 뷰포트 조회와 같은 형상이고 색상 필드가 없다 (MSG-187 FR-3)")
	void 응답_형상이_내_뷰포트_조회와_동일하고_색상_필드가_없다() throws Exception {
		// 형상 보장은 타입 공유(OccupiedGridPageResponseDto — GridController 와 동일 DTO)가 하고,
		// 이 테스트는 직렬화 결과에 색상 같은 여분 필드가 붙지 않는지만 고정한다.
		given(friendService.getFriendGrids(USER_ID, 7L, new ViewportBounds(37.50, 127.00, 37.55, 127.05), null, 1000))
			.willReturn(new OccupiedGridPage(
				List.of(new OccupiedGridView("19422_9582", 19422, 9582, "서면", "I-9", "서울특별시 강남구 역삼1동")),
				"NDE2NDNfMTEwNDYw"));

		mockMvc.perform(get("/api/friends/7/grids")
				.param("swLat", "37.50")
				.param("swLng", "127.00")
				.param("neLat", "37.55")
				.param("neLng", "127.05")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.grids", Matchers.hasSize(1)))
			.andExpect(jsonPath("$.data.grids[0].gridId").value("19422_9582"))
			.andExpect(jsonPath("$.data.grids[0].gridY").value(19422))
			.andExpect(jsonPath("$.data.grids[0].gridX").value(9582))
			// 이름 3필드는 내 뷰포트 조회와 공용 DTO 라 같이 실린다 (MSG-341 구역 2필드 + MSG-349 행정동)
			// — 색상 같은 여분 필드는 여전히 없다.
			.andExpect(jsonPath("$.data.grids[0].zoneName").value("서면"))
			.andExpect(jsonPath("$.data.grids[0].zoneCell").value("I-9"))
			.andExpect(jsonPath("$.data.grids[0].regionName").value("서울특별시 강남구 역삼1동"))
			.andExpect(jsonPath("$.data.grids[0].*", Matchers.hasSize(6)))
			.andExpect(jsonPath("$.data.nextCursor").value("NDE2NDNfMTEwNDYw"));
	}

	@Test
	@DisplayName("뷰포트 파라미터가 빠지면 4401 이다 — 내 격자 조회와 같은 에러 (MSG-187 FR-10)")
	void 뷰포트_파라미터_누락은_4401이다() throws Exception {
		mockMvc.perform(get("/api/friends/7/grids")
				.param("swLat", "37.50")
				.param("swLng", "127.00")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(4401));
	}

	@Test
	@DisplayName("친구 집계 응답은 내 집계 조회와 같은 형상이다 — principal·경로 userId·단위가 서비스로 전달된다 (MSG-356 FR-4)")
	void 친구_집계_응답은_내_집계_조회와_같은_형상이다() throws Exception {
		given(friendService.getFriendGridAggregates(
			USER_ID, 7L, new ViewportBounds(35.10, 128.90, 35.30, 129.20), RegionUnit.DONG))
			.willReturn(List.of(
				new RegionAggregateView("2623058000", "부전2동", 35.162, 129.065, 31),
				// 행정동 미판정 묶음도 제외 없이 항목으로 실린다 (FR-7).
				new RegionAggregateView(null, null, 35.101, 129.032, 2)));

		mockMvc.perform(get("/api/friends/7/grids/aggregation")
				.param("swLat", "35.10")
				.param("swLng", "128.90")
				.param("neLat", "35.30")
				.param("neLng", "129.20")
				.param("unit", "DONG")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data", Matchers.hasSize(2)))
			.andExpect(jsonPath("$.data[0].regionCode").value("2623058000"))
			.andExpect(jsonPath("$.data[0].name").value("부전2동"))
			.andExpect(jsonPath("$.data[0].lat").value(35.162))
			.andExpect(jsonPath("$.data[0].lng").value(129.065))
			.andExpect(jsonPath("$.data[0].count").value(31))
			.andExpect(jsonPath("$.data[0].*", Matchers.hasSize(5)))
			.andExpect(jsonPath("$.data[1].regionCode").value(Matchers.nullValue()))
			.andExpect(jsonPath("$.data[1].name").value(Matchers.nullValue()))
			.andExpect(jsonPath("$.data[1].count").value(2));
	}

	@Test
	@DisplayName("소문자 unit 도 허용된다 — 대소문자 무관 (MSG-356)")
	void 소문자_unit도_허용된다() throws Exception {
		given(friendService.getFriendGridAggregates(
			USER_ID, 7L, new ViewportBounds(35.10, 128.90, 35.30, 129.20), RegionUnit.SIGUNGU))
			.willReturn(List.of());

		mockMvc.perform(get("/api/friends/7/grids/aggregation")
				.param("swLat", "35.10")
				.param("swLng", "128.90")
				.param("neLat", "35.30")
				.param("neLng", "129.20")
				.param("unit", "sigungu")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data", Matchers.hasSize(0)));

		verify(friendService).getFriendGridAggregates(
			USER_ID, 7L, new ViewportBounds(35.10, 128.90, 35.30, 129.20), RegionUnit.SIGUNGU);
	}

	@Test
	@DisplayName("unit 누락·미지원 값은 4405 다 — 내 집계 조회와 같은 에러 (MSG-356)")
	void unit_누락이나_미지원_값은_4405다() throws Exception {
		mockMvc.perform(get("/api/friends/7/grids/aggregation")
				.param("swLat", "35.10")
				.param("swLng", "128.90")
				.param("neLat", "35.30")
				.param("neLng", "129.20")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(4405));

		mockMvc.perform(get("/api/friends/7/grids/aggregation")
				.param("swLat", "35.10")
				.param("swLng", "128.90")
				.param("neLat", "35.30")
				.param("neLng", "129.20")
				.param("unit", "EUPMYEON")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(4405));
	}

	@Test
	@DisplayName("집계 조회도 뷰포트 파라미터가 빠지면 4401 이다 (MSG-356)")
	void 집계_뷰포트_파라미터_누락은_4401이다() throws Exception {
		mockMvc.perform(get("/api/friends/7/grids/aggregation")
				.param("swLat", "35.10")
				.param("unit", "DONG")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(4401));
	}

	@Test
	@DisplayName("친구 격자 영상 목록은 영상 항목을 담는다 — principal 과 경로 userId 가 서비스로 전달된다 (MSG-187 FR-4)")
	void 친구_격자_영상_목록은_영상_항목을_담는다() throws Exception {
		given(friendService.getFriendGridVideos(USER_ID, 7L, "19422_9582")).willReturn(
			List.of(new FriendGridVideoResponseDto(1042L, "https://signed/thumb.jpg", (short) 12,
				LocalDateTime.of(2026, 7, 20, 18, 3, 11))));

		mockMvc.perform(get("/api/friends/7/grids/19422_9582/videos")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data", Matchers.hasSize(1)))
			.andExpect(jsonPath("$.data[0].videoId").value(1042))
			.andExpect(jsonPath("$.data[0].thumbnailUrl").value("https://signed/thumb.jpg"))
			.andExpect(jsonPath("$.data[0].durationSec").value(12))
			.andExpect(jsonPath("$.data[0].createdAt").value("2026-07-20T18:03:11"));
	}

	@Test
	@DisplayName("친구 프로필은 토큰 없이 호출하면 401 이다")
	void 친구_프로필은_토큰_없이_호출하면_401이다() throws Exception {
		mockMvc.perform(get("/api/friends/7/profile"))
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
