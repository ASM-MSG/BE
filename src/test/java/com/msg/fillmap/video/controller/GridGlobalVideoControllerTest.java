package com.msg.fillmap.video.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import com.msg.fillmap.video.dto.GridGlobalVideoResponseDto;
import com.msg.fillmap.video.dto.GridVideoPageResponseDto;
import com.msg.fillmap.video.service.VideoService;

/**
 * 격자 전역 영상 목록 엔드포인트 (MSG-237). 클램프·커서·presign 은 서비스 계약(VideoGlobalListServiceTest)
 * 이라 여기서는 라우팅·직렬화(§D3 키 배제 포함)·인증 게이트만 본다. my-videos·cover 와 같은 컨트롤러지만
 * 기능별 테스트 클래스 분리 선례(VideoVisibility/VideoPlayback)를 따라 별도 클래스로 둔다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("GridVideoController 전역 영상 목록")
class GridGlobalVideoControllerTest {

	private static final long USER_ID = 42L;
	private static final String GRID_ID = "19422_9582";
	private static final String URL = "/api/grids/{gridId}/videos";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private VideoService videoService;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	private void givenPage() {
		given(videoService.getGridGlobalVideos(eq(GRID_ID), isNull(), eq(20))).willReturn(
			new GridVideoPageResponseDto(List.of(
				new GridGlobalVideoResponseDto(1042L, "https://bucket.s3/thumb.jpg?X-Amz-Signature=abc",
					(short) 12, 37L, LocalDateTime.of(2026, 7, 20, 18, 3, 11), "busan.vlog"),
				new GridGlobalVideoResponseDto(1039L, "https://bucket.s3/thumb2.jpg?X-Amz-Signature=def",
					(short) 8, 5L, LocalDateTime.of(2026, 7, 19, 21, 10, 0), "seoul.walk")),
				true, "NToxNzg0NDU1ODAwMDAwMDAwOjEwMzk"));
	}

	@Test
	@DisplayName("격자 전역 영상목록 조회는 200 과 videos 배열과 hasNext 를 반환한다")
	void 격자_전역_영상목록_조회는_200과_videos배열과_hasNext를_반환한다() throws Exception {
		givenPage();

		mockMvc.perform(get(URL, GRID_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.videos[0].videoId").value(1042))
			.andExpect(jsonPath("$.data.videos[0].thumbnailUrl")
				.value("https://bucket.s3/thumb.jpg?X-Amz-Signature=abc"))
			.andExpect(jsonPath("$.data.videos[0].durationSec").value(12))
			.andExpect(jsonPath("$.data.videos[0].viewCount").value(37))
			.andExpect(jsonPath("$.data.videos[0].recordedAt").value("2026-07-20T18:03:11"))
			.andExpect(jsonPath("$.data.videos[0].nickname").value("busan.vlog"))
			.andExpect(jsonPath("$.data.videos[1].videoId").value(1039))
			.andExpect(jsonPath("$.data.videos[1].nickname").value("seoul.walk"))
			.andExpect(jsonPath("$.data.hasNext").value(true))
			.andExpect(jsonPath("$.data.nextCursor").value("NToxNzg0NDU1ODAwMDAwMDAwOjEwMzk"));
	}

	@Test
	@DisplayName("목록 항목의 작성자 축은 닉네임뿐이고 processingStatus·title 은 없다 (§D3 계약 배제)")
	void 목록_항목의_작성자_축은_닉네임뿐이다() throws Exception {
		givenPage();

		mockMvc.perform(get(URL, GRID_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.videos[0].videoId").value(1042))   // 항목 실존 확인 — 공허 통과 방지
			.andExpect(jsonPath("$.data.videos[0].processingStatus").doesNotExist())   // 전역 목록은 항상 READY
			.andExpect(jsonPath("$.data.videos[0].nickname").value("busan.vlog"))      // 2026-08-04 확정(MSG-371)
			.andExpect(jsonPath("$.data.videos[0].userId").doesNotExist())             // 작성자 id 는 여전히 비노출
			.andExpect(jsonPath("$.data.videos[0].title").doesNotExist());             // MSG-240 후속 additive
	}

	@Test
	@DisplayName("영상이 없으면 200 과 빈 배열·hasNext false 다")
	void 영상이_없으면_200과_빈_배열_hasNext_false다() throws Exception {
		given(videoService.getGridGlobalVideos(eq(GRID_ID), isNull(), eq(20)))
			.willReturn(new GridVideoPageResponseDto(List.of(), false, null));

		mockMvc.perform(get(URL, GRID_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.videos").isEmpty())
			.andExpect(jsonPath("$.data.hasNext").value(false))
			.andExpect(jsonPath("$.data.nextCursor").value(org.hamcrest.Matchers.nullValue()));
	}

	@Test
	@DisplayName("인증없이 호출하면 401 이다")
	void 인증없이_호출하면_401이다() throws Exception {
		mockMvc.perform(get(URL, GRID_ID))
			.andExpect(status().isUnauthorized());
	}
}
