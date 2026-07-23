package com.msg.fillmap.usergrid.controller;

import static org.mockito.ArgumentMatchers.anyLong;
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
			.andExpect(jsonPath("$.body.totalGridCount").value(15))
			.andExpect(jsonPath("$.body.totalVideoCount").value(42))
			.andExpect(jsonPath("$.body.visitedRegionCount").value(6));
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
			.andExpect(jsonPath("$.body.totalGridCount").value(0))
			.andExpect(jsonPath("$.body.totalVideoCount").value(0))
			.andExpect(jsonPath("$.body.visitedRegionCount").value(0));
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
			"41642_110458", 41642, 110458,
			LocalDateTime.of(2026, 7, 20, 18, 3, 11), LocalDateTime.of(2026, 7, 21, 9, 12, 0),
			3, 1042L, "https://s3.example/thumb.jpg?X-Amz-Signature=abc", "서울특별시 강남구 역삼1동");
		given(userGridQueryService.getCollectionGrids(anyLong())).willReturn(List.of(view));

		mockMvc.perform(get("/api/collections/grids")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.body.length()").value(1))
			.andExpect(jsonPath("$.body[0].gridId").value("41642_110458"))
			.andExpect(jsonPath("$.body[0].gridY").value(41642))
			.andExpect(jsonPath("$.body[0].gridX").value(110458))
			.andExpect(jsonPath("$.body[0].videoCount").value(3))
			.andExpect(jsonPath("$.body[0].coverVideoId").value(1042))
			.andExpect(jsonPath("$.body[0].coverThumbnailUrl")
				.value("https://s3.example/thumb.jpg?X-Amz-Signature=abc"))
			.andExpect(jsonPath("$.body[0].regionName").value("서울특별시 강남구 역삼1동"));
	}

	@Test
	@DisplayName("점령 0건 사용자는 200 과 빈 배열을 받는다")
	void 점령0건_사용자는_200과_빈_배열을_받는다() throws Exception {
		given(userGridQueryService.getCollectionGrids(anyLong())).willReturn(List.of());

		mockMvc.perform(get("/api/collections/grids")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.body.length()").value(0));
	}

	@Test
	@DisplayName("인증 없이 갤러리 목록을 조회하면 401 이다")
	void 인증_없이_갤러리_목록을_조회하면_401이다() throws Exception {
		mockMvc.perform(get("/api/collections/grids"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.developCode").value(2403));
	}
}
