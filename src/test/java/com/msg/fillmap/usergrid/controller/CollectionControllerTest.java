package com.msg.fillmap.usergrid.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
}
