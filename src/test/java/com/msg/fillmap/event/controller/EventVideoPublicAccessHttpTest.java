package com.msg.fillmap.event.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.event.dto.EventLocationVideoPageResponseDto;
import com.msg.fillmap.event.dto.EventVideoCommentPageResponseDto;
import com.msg.fillmap.event.dto.EventVideoDetailResponseDto;
import com.msg.fillmap.event.dto.EventVideoUploadRequestDto;
import com.msg.fillmap.event.dto.EventVideoUploadResponseDto;
import com.msg.fillmap.event.exception.EventErrorCode;
import com.msg.fillmap.event.service.EventVideoInteractionService;
import com.msg.fillmap.event.service.EventVideoService;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.UserRole;

/**
 * 행사 영상 API 의 인증 계약 (MSG-440 §API 명세 인증 정책). 피드와 상세는 비로그인 열람이고 업로드는
 * 로그인부터다 — 업로드 POST 가 피드 GET 과 <b>같은 경로</b>라, 메서드 무제한 문자열 패턴으로 열면 업로드가
 * 조용히 익명에 풀린다. 서비스는 목이라 이 테스트의 변수는 SecurityConfig 등록과 컨트롤러 배선뿐이다.
 * <p>
 * 인증된 업로드 두 건은 그 배선을 본다 — 토큰의 사용자 id 가 서비스 첫 인자로 넘어가는지(다른 사람 이름으로
 * 영상이 저장되는 걸 막는 지점)와 요청 본문 검증이 실제로 발동하는지다. 인증은 레포 선례대로 실제 JWT 를
 * 발급해 Authorization 헤더로 싣는다(AdminAuthorizationTest) — 시큐리티 실체인과 필터를 그대로 통과해야
 * principal 이 채워지므로 목 인증으로는 이 배선이 검증되지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("행사 영상 공개 접근 (SecurityConfig)")
class EventVideoPublicAccessHttpTest {

	private static final long OCCURRENCE_ID = 12L;
	private static final long LOCATION_ID = 34L;
	private static final long VIDEO_ID = 1042L;
	private static final long UPLOADER_ID = 7007L;
	private static final String FEED_PATH = "/api/event-occurrences/{occurrenceId}/locations/{locationId}/videos";
	private static final String DETAIL_PATH = "/api/event-videos/{videoId}";
	private static final String COMMENTS_PATH = "/api/event-videos/{videoId}/comments";
	private static final String COMMENT_PATH = "/api/event-videos/{videoId}/comments/{commentId}";
	private static final String HELPFUL_PATH = "/api/event-videos/{videoId}/helpful";
	private static final long COMMENT_ID = 3021L;
	private static final String COMMENT_BODY = """
		{"content":"저도 어제 다녀왔어요"}""";
	private static final String UPLOAD_BODY = """
		{"s3Key":"videos/pending/7007/6f1c1f0e.mp4","durationSec":10,"recordedAt":"2026-10-06T12:00:00Z"}""";
	/** durationSec 0 은 @Min(1) 위반 — @Valid 가 안 걸려 있으면 그대로 서비스까지 흘러간다. */
	private static final String INVALID_UPLOAD_BODY = """
		{"s3Key":"videos/pending/7007/6f1c1f0e.mp4","durationSec":0,"recordedAt":"2026-10-06T12:00:00Z"}""";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private EventVideoService eventVideoService;

	@MockitoBean
	private EventVideoInteractionService eventVideoInteractionService;

	private String bearer(long userId) {
		return "Bearer " + tokenProvider.issueAccessToken(userId, UserRole.USER);
	}

	private EventVideoDetailResponseDto 상세() {
		return new EventVideoDetailResponseDto(VIDEO_ID, OCCURRENCE_ID, "LIVE", LOCATION_ID, "영화의전당",
			"19422_9582", null, null, "부산광역시 부산진구 부전2동", "https://example.test/playback", (short) 10,
			LocalDateTime.of(2026, 10, 6, 12, 0), LocalDateTime.of(2026, 10, 6, 12, 30), "필맵러", false,
			3L, false, 2L, 댓글페이지());
	}

	private EventVideoCommentPageResponseDto 댓글페이지() {
		return new EventVideoCommentPageResponseDto(List.of(), false, null);
	}

	@Test
	@DisplayName("무인증 피드와 상세는 200으로 성공한다")
	void 무인증_피드와_상세는_200으로_성공한다() throws Exception {
		given(eventVideoService.getLocationVideos(anyLong(), anyLong(), any(), anyInt()))
			.willReturn(new EventLocationVideoPageResponseDto(List.of(), false, null));
		given(eventVideoService.getVideoDetail(anyLong(), any())).willReturn(상세());

		mockMvc.perform(get(FEED_PATH, OCCURRENCE_ID, LOCATION_ID)).andExpect(status().isOk());
		mockMvc.perform(get(DETAIL_PATH, VIDEO_ID)).andExpect(status().isOk());
	}

	@Test
	@DisplayName("무인증 상세 HEAD 는 부수효과 없이 200이다")
	void 무인증_상세_HEAD는_부수효과_없이_200이다() throws Exception {
		mockMvc.perform(head(DETAIL_PATH, VIDEO_ID)).andExpect(status().isOk());

		// 명시 HEAD 매핑이 없으면 GET 핸들러로 폴백해 조회수가 오른다 — 서비스 호출 자체가 없어야 한다.
		then(eventVideoService).should(never()).getVideoDetail(anyLong(), any());
	}

	@Test
	@DisplayName("무인증 업로드는 401로 거절된다 (피드와 같은 경로가 함께 풀리지 않는다)")
	void 무인증_업로드는_401로_거절된다() throws Exception {
		mockMvc.perform(post(FEED_PATH, OCCURRENCE_ID, LOCATION_ID)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"s3Key":"videos/pending/1/x.mp4","durationSec":10,"recordedAt":"2026-10-06T12:00:00Z"}"""))
			.andExpect(status().isUnauthorized());
		then(eventVideoService).should(never()).upload(anyLong(), anyLong(), anyLong(), any());
	}

	// 검증: FR-EVENT-09
	@Test
	@DisplayName("인증된 업로드는 토큰의 사용자 id 로 서비스에 위임된다")
	void 인증된_업로드는_토큰의_사용자_id로_서비스에_위임된다() throws Exception {
		given(eventVideoService.upload(anyLong(), anyLong(), anyLong(), any()))
			.willReturn(new EventVideoUploadResponseDto(1001L, "19422_9582", "UPLOADED", true, List.of()));

		mockMvc.perform(post(FEED_PATH, OCCURRENCE_ID, LOCATION_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer(UPLOADER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content(UPLOAD_BODY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.videoId").value(1001))
			.andExpect(jsonPath("$.data.gridId").value("19422_9582"))
			.andExpect(jsonPath("$.data.processingStatus").value("UPLOADED"))
			.andExpect(jsonPath("$.data.occupied").value(true))
			.andExpect(jsonPath("$.data.newBadges").isEmpty());

		// 업로더가 경로나 본문이 아니라 토큰에서 온다 — 여기가 어긋나면 남의 이름으로 영상이 저장된다.
		ArgumentCaptor<EventVideoUploadRequestDto> request = ArgumentCaptor.forClass(EventVideoUploadRequestDto.class);
		then(eventVideoService).should()
			.upload(eq(UPLOADER_ID), eq(OCCURRENCE_ID), eq(LOCATION_ID), request.capture());
		assertThat(request.getValue().s3Key()).isEqualTo("videos/pending/7007/6f1c1f0e.mp4");
		assertThat(request.getValue().durationSec()).isEqualTo((short) 10);
	}

	@Test
	@DisplayName("길이가 범위 밖인 업로드 요청은 서비스에 닿기 전에 400이다")
	void 길이가_범위_밖인_업로드_요청은_서비스에_닿기_전에_400이다() throws Exception {
		mockMvc.perform(post(FEED_PATH, OCCURRENCE_ID, LOCATION_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer(UPLOADER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content(INVALID_UPLOAD_BODY))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(400));
		then(eventVideoService).should(never()).upload(anyLong(), anyLong(), anyLong(), any());
	}

	@Test
	@DisplayName("공개 GET 에서도 무효 토큰은 2401로 거절된다 (토큰 검증 성질 보존)")
	void 공개_GET에서도_무효_토큰은_2401로_거절된다() throws Exception {
		mockMvc.perform(get(DETAIL_PATH, VIDEO_ID)
				.header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.developCode").value(2401));
	}

	@Test
	@DisplayName("무인증 상세 요청에 다른 영상 경로가 함께 열리지 않는다 (기존 보호 유지)")
	void 무인증_상세_요청에_다른_영상_경로가_함께_열리지_않는다() throws Exception {
		mockMvc.perform(get("/api/videos/{videoId}", VIDEO_ID)).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/event-occurrences/{id}/locations/{lid}/videos/{vid}",
			OCCURRENCE_ID, LOCATION_ID, VIDEO_ID)).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("비로그인 댓글 목록 조회는 허용된다")
	void 비로그인_댓글_목록_조회는_허용된다() throws Exception {
		given(eventVideoInteractionService.getComments(anyLong(), any(), anyInt())).willReturn(댓글페이지());

		// 기존 "/api/event-videos/*" 패턴은 세그먼트 하나만 매치해 이 하위 경로를 덮지 않는다.
		mockMvc.perform(get(COMMENTS_PATH, VIDEO_ID)).andExpect(status().isOk());
	}

	@Test
	@DisplayName("비로그인 댓글 작성과 도움돼요 변경은 401이다")
	void 비로그인_댓글_작성과_도움돼요_변경은_401이다() throws Exception {
		// GET 한정 매처라 같은 경로의 쓰기가 함께 풀리지 않는다 — 문자열 패턴으로 열면 여기가 200 이 된다.
		mockMvc.perform(post(COMMENTS_PATH, VIDEO_ID)
				.contentType(MediaType.APPLICATION_JSON).content(COMMENT_BODY))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(patch(COMMENT_PATH, VIDEO_ID, COMMENT_ID)
				.contentType(MediaType.APPLICATION_JSON).content(COMMENT_BODY))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(delete(COMMENT_PATH, VIDEO_ID, COMMENT_ID)).andExpect(status().isUnauthorized());
		mockMvc.perform(put(HELPFUL_PATH, VIDEO_ID)).andExpect(status().isUnauthorized());
		mockMvc.perform(delete(HELPFUL_PATH, VIDEO_ID)).andExpect(status().isUnauthorized());

		then(eventVideoInteractionService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("무효 커서 파라미터는 인증 없이도 도메인 코드 13402로 응답한다")
	void 무효_커서_파라미터는_인증_없이도_도메인_코드_13402로_응답한다() throws Exception {
		given(eventVideoService.getLocationVideos(anyLong(), anyLong(), anyString(), anyInt()))
			.willThrow(new ApiException(EventErrorCode.INVALID_CURSOR));

		mockMvc.perform(get(FEED_PATH, OCCURRENCE_ID, LOCATION_ID).param("cursor", "broken"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.developCode").value(13402));
	}
}
