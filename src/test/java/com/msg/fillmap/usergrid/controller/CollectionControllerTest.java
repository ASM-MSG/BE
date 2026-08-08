package com.msg.fillmap.usergrid.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.usergrid.service.CollectionGridView;
import com.msg.fillmap.usergrid.service.CollectionSummaryView;
import com.msg.fillmap.usergrid.service.RegionVideoView;
import com.msg.fillmap.usergrid.service.UserGridQueryService;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("CollectionController")
class CollectionControllerTest {

	private static final long USER_ID = 4152L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private UserGridQueryService userGridQueryService;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	@Test
	@DisplayName("도감 요약 조회는 200 과 세 집계 필드를 반환한다")
	void 도감_요약_조회는_200과_세_집계_필드를_반환한다() throws Exception {
		given(userGridQueryService.getCollectionSummary(anyLong()))
			.willReturn(new CollectionSummaryView(15, 42L, 6));

		mockMvc.perform(get("/api/collections/summary")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.totalGridCount").value(15))
			.andExpect(jsonPath("$.data.totalVideoCount").value(42))
			.andExpect(jsonPath("$.data.visitedRegionCount").value(6));
	}

	@Test
	@DisplayName("점령이 0건인 사용자는 모든 필드가 0인 요약을 받는다")
	void 점령이_0건인_사용자는_모든_필드가_0인_요약을_받는다() throws Exception {
		given(userGridQueryService.getCollectionSummary(anyLong()))
			.willReturn(new CollectionSummaryView(0, 0L, 0));

		mockMvc.perform(get("/api/collections/summary")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.totalGridCount").value(0))
			.andExpect(jsonPath("$.data.totalVideoCount").value(0))
			.andExpect(jsonPath("$.data.visitedRegionCount").value(0));
	}

	@Test
	@DisplayName("인증 없이 요약을 조회하면 401 이다")
	void 인증_없이_요약을_조회하면_401이다() throws Exception {
		mockMvc.perform(get("/api/collections/summary"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.developCode").value(2403));
	}

	@Test
	@DisplayName("인증된 요청은 200 과 갤러리 격자 리스트를 반환한다")
	void 인증된_요청은_200과_갤러리_격자_리스트를_반환한다() throws Exception {
		CollectionGridView view = new CollectionGridView(
			"19422_9582", 19422, 9582,
			LocalDateTime.of(2026, 7, 20, 18, 3, 11), LocalDateTime.of(2026, 7, 21, 9, 12, 0),
			3, 1042L, "https://s3.example/thumb.jpg?X-Amz-Signature=abc", "서울특별시 강남구 역삼1동",
			"서면", "I-9");
		given(userGridQueryService.getCollectionGrids(anyLong())).willReturn(List.of(view));

		mockMvc.perform(get("/api/collections/grids")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.length()").value(1))
			.andExpect(jsonPath("$.data[0].gridId").value("19422_9582"))
			.andExpect(jsonPath("$.data[0].gridY").value(19422))
			.andExpect(jsonPath("$.data[0].gridX").value(9582))
			.andExpect(jsonPath("$.data[0].videoCount").value(3))
			.andExpect(jsonPath("$.data[0].coverVideoId").value(1042))
			.andExpect(jsonPath("$.data[0].coverThumbnailUrl")
				.value("https://s3.example/thumb.jpg?X-Amz-Signature=abc"))
			.andExpect(jsonPath("$.data[0].regionName").value("서울특별시 강남구 역삼1동"))
			.andExpect(jsonPath("$.data[0].zoneName").value("서면"))
			.andExpect(jsonPath("$.data[0].zoneCell").value("I-9"));
	}

	@Test
	@DisplayName("점령 0건 사용자는 200 과 빈 배열을 받는다")
	void 점령0건_사용자는_200과_빈_배열을_받는다() throws Exception {
		given(userGridQueryService.getCollectionGrids(anyLong())).willReturn(List.of());

		mockMvc.perform(get("/api/collections/grids")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.length()").value(0));
	}

	@Test
	@DisplayName("인증 없이 갤러리 목록을 조회하면 401 이다")
	void 인증_없이_갤러리_목록을_조회하면_401이다() throws Exception {
		mockMvc.perform(get("/api/collections/grids"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.developCode").value(2403));
	}

	@Test
	@DisplayName("동 단위 영상 조회는 200 과 gridId 포함 영상 리스트를 반환한다")
	void 동_단위_영상_조회는_200과_gridId_포함_영상_리스트를_반환한다() throws Exception {
		RegionVideoView view = new RegionVideoView(
			1042L, "19422_9582", "https://s3.example/thumb.jpg?X-Amz-Signature=abc",
			"READY", 12, LocalDateTime.of(2026, 7, 20, 18, 3, 11), "서면", "I-9");
		given(userGridQueryService.getRegionVideos(anyLong(), anyString())).willReturn(List.of(view));

		mockMvc.perform(get("/api/collections/videos")
				.param("regionCode", "1168051500")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.length()").value(1))
			.andExpect(jsonPath("$.data[0].videoId").value(1042))
			.andExpect(jsonPath("$.data[0].gridId").value("19422_9582"))
			.andExpect(jsonPath("$.data[0].thumbnailUrl")
				.value("https://s3.example/thumb.jpg?X-Amz-Signature=abc"))
			.andExpect(jsonPath("$.data[0].processingStatus").value("READY"))
			.andExpect(jsonPath("$.data[0].durationSec").value(12))
			.andExpect(jsonPath("$.data[0].zoneName").value("서면"))
			.andExpect(jsonPath("$.data[0].zoneCell").value("I-9"));
	}

	@Test
	@DisplayName("그 행정동에 내 영상이 없으면 200 과 빈 배열이다")
	void 그_행정동에_내_영상이_없으면_200과_빈_배열이다() throws Exception {
		given(userGridQueryService.getRegionVideos(anyLong(), anyString())).willReturn(List.of());

		mockMvc.perform(get("/api/collections/videos")
				.param("regionCode", "9995399999")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.length()").value(0));
	}

	@Test
	@DisplayName("regionCode 파라미터가 없으면 400 이다")
	void regionCode_파라미터가_없으면_400이다() throws Exception {
		mockMvc.perform(get("/api/collections/videos")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(400));
	}

	@Test
	@DisplayName("인증 없이 동 단위 영상을 조회하면 401 이다")
	void 인증_없이_동_단위_영상을_조회하면_401이다() throws Exception {
		mockMvc.perform(get("/api/collections/videos")
				.param("regionCode", "1168051500"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.developCode").value(2403));
	}
}
