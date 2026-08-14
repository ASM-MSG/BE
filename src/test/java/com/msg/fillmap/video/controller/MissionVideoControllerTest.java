package com.msg.fillmap.video.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.video.dto.GridGlobalVideoResponseDto;
import com.msg.fillmap.video.dto.GridVideoPageResponseDto;
import com.msg.fillmap.video.service.VideoService;

/**
 * 미션 영상 목록 엔드포인트 (MSG-390). 클램프·커서·후보 술어는 서비스·리포지토리 계약
 * (MissionVideoListServiceTest·MissionVideoListQueryTest)이라 여기서는 라우팅·직렬화·인증 게이트만 본다.
 * 경로 접두사는 /api/missions 지만 videos 를 다루는 코드라 video 패키지에 둔다(GridVideoController 선례).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("MissionVideoController 미션 영상 목록")
class MissionVideoControllerTest {

	private static final long USER_ID = 42L;
	private static final long MISSION_ID = 12L;
	private static final String URL = "/api/missions/{missionId}/videos";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private VideoService videoService;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	@Test
	@DisplayName("응답 항목은 격자 전역 목록과 같은 여섯 필드다")
	void 응답_항목은_격자_전역_목록과_같은_여섯_필드다() throws Exception {
		given(videoService.getMissionVideos(eq(MISSION_ID), isNull(), eq(20))).willReturn(
			new GridVideoPageResponseDto(List.of(
				new GridGlobalVideoResponseDto(1042L, "https://bucket.s3/thumb.jpg?X-Amz-Signature=abc",
					(short) 27, 1200L, LocalDateTime.of(2026, 7, 20, 18, 3, 11), "busan.vlog")),
				true, "MTI6MTc4NDQ1NTgwMDAwMDAwMDoxMDM5"));

		mockMvc.perform(get(URL, MISSION_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.videos[0].videoId").value(1042))
			.andExpect(jsonPath("$.data.videos[0].thumbnailUrl")
				.value("https://bucket.s3/thumb.jpg?X-Amz-Signature=abc"))
			.andExpect(jsonPath("$.data.videos[0].durationSec").value(27))
			.andExpect(jsonPath("$.data.videos[0].viewCount").value(1200))
			.andExpect(jsonPath("$.data.videos[0].recordedAt").value("2026-07-20T18:03:11Z"))
			.andExpect(jsonPath("$.data.videos[0].nickname").value("busan.vlog"))
			.andExpect(jsonPath("$.data.videos[0].userId").doesNotExist())            // 작성자 id 비노출
			.andExpect(jsonPath("$.data.videos[0].processingStatus").doesNotExist())   // 목록은 항상 READY
			.andExpect(jsonPath("$.data.hasNext").value(true))
			.andExpect(jsonPath("$.data.nextCursor").value("MTI6MTc4NDQ1NTgwMDAwMDAwMDoxMDM5"));
	}

	@Test
	@DisplayName("조건에 맞는 영상이 없으면 빈 페이지 200 이다")
	void 조건에_맞는_영상이_없으면_빈_페이지_200이다() throws Exception {
		// 존재하지 않는 미션 ID 도 같은 응답이다 — 서비스가 빈 페이지를 돌려주고 컨트롤러는 그대로 싣는다.
		given(videoService.getMissionVideos(eq(MISSION_ID), isNull(), eq(20)))
			.willReturn(new GridVideoPageResponseDto(List.of(), false, null));

		mockMvc.perform(get(URL, MISSION_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.videos").isEmpty())
			.andExpect(jsonPath("$.data.hasNext").value(false))
			.andExpect(jsonPath("$.data.nextCursor").value(Matchers.nullValue()));
	}

	@Test
	@DisplayName("미인증 요청은 401 이다")
	void 미인증_요청은_401이다() throws Exception {
		mockMvc.perform(get(URL, MISSION_ID))
			.andExpect(status().isUnauthorized());
	}
}
