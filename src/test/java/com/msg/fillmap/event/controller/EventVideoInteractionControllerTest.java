package com.msg.fillmap.event.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.event.dto.EventVideoCommentPageResponseDto;
import com.msg.fillmap.event.dto.EventVideoCommentResponseDto;
import com.msg.fillmap.event.dto.EventVideoHelpfulResponseDto;
import com.msg.fillmap.event.service.EventVideoInteractionService;
import com.msg.fillmap.user.entity.UserRole;

/**
 * 행사 영상 댓글·도움돼요 HTTP 계약 (MSG-441 API 1~6). 서비스는 목이라 잠금·소유자·커서 판정은
 * EventVideoInteractionServiceTest 담당이고, 여기서는 컨트롤러 몫만 본다 — 경로·본문에서 뽑은 인자를
 * 서비스로 넘기는 시그니처, @Valid 검증 경계, 응답 껍데기다.
 * 비로그인 401 은 EventVideoPublicAccessHttpTest 가 이미 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("EventVideoInteractionController")
class EventVideoInteractionControllerTest {

	private static final String COMMENTS_PATH = "/api/event-videos/{videoId}/comments";
	private static final String COMMENT_PATH = "/api/event-videos/{videoId}/comments/{commentId}";
	private static final String HELPFUL_PATH = "/api/event-videos/{videoId}/helpful";

	private static final long USER_ID = 7007L;
	private static final long VIDEO_ID = 1042L;
	private static final long COMMENT_ID = 3021L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private EventVideoInteractionService eventVideoInteractionService;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	private EventVideoCommentResponseDto comment(String content) {
		return new EventVideoCommentResponseDto(COMMENT_ID, USER_ID, "필맵러", content,
			LocalDateTime.of(2026, 10, 6, 12, 30));
	}

	private String body(String content) {
		return "{\"content\":\"" + content + "\"}";
	}

	@Nested
	@DisplayName("댓글 작성")
	class CreateComment {

		@Test
		@DisplayName("본문 내용과 인증 사용자 id를 서비스에 전달하고 결과를 그대로 반환한다")
		void 댓글_작성은_내용과_사용자_id를_서비스에_전달한다() throws Exception {
			given(eventVideoInteractionService.createComment(USER_ID, VIDEO_ID, "저도 어제 다녀왔어요"))
				.willReturn(comment("저도 어제 다녀왔어요"));

			mockMvc.perform(post(COMMENTS_PATH, VIDEO_ID)
					.header(HttpHeaders.AUTHORIZATION, bearer())
					.contentType(MediaType.APPLICATION_JSON)
					.content(body("저도 어제 다녀왔어요")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200))
				.andExpect(jsonPath("$.data.commentId").value(COMMENT_ID))
				.andExpect(jsonPath("$.data.authorId").value(USER_ID))
				.andExpect(jsonPath("$.data.authorNickname").value("필맵러"))
				.andExpect(jsonPath("$.data.content").value("저도 어제 다녀왔어요"));

			then(eventVideoInteractionService).should()
				.createComment(USER_ID, VIDEO_ID, "저도 어제 다녀왔어요");
		}

		@Test
		@DisplayName("빈 내용은 400이고 서비스까지 가지 않는다 — @NotBlank")
		void 빈_내용은_400이다() throws Exception {
			mockMvc.perform(post(COMMENTS_PATH, VIDEO_ID)
					.header(HttpHeaders.AUTHORIZATION, bearer())
					.contentType(MediaType.APPLICATION_JSON)
					.content(body("   ")))
				.andExpect(status().isBadRequest());

			then(eventVideoInteractionService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("500자를 넘는 내용은 400이고 서비스까지 가지 않는다 — @Size")
		void 오백자를_넘는_내용은_400이다() throws Exception {
			mockMvc.perform(post(COMMENTS_PATH, VIDEO_ID)
					.header(HttpHeaders.AUTHORIZATION, bearer())
					.contentType(MediaType.APPLICATION_JSON)
					.content(body("가".repeat(501))))
				.andExpect(status().isBadRequest());

			then(eventVideoInteractionService).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("댓글 수정")
	class UpdateComment {

		@Test
		@DisplayName("경로의 댓글 id와 바꿀 내용을 서비스에 전달한다")
		void 댓글_수정은_댓글_id와_내용을_서비스에_전달한다() throws Exception {
			given(eventVideoInteractionService.updateComment(USER_ID, VIDEO_ID, COMMENT_ID, "오타 고쳤어요"))
				.willReturn(comment("오타 고쳤어요"));

			mockMvc.perform(patch(COMMENT_PATH, VIDEO_ID, COMMENT_ID)
					.header(HttpHeaders.AUTHORIZATION, bearer())
					.contentType(MediaType.APPLICATION_JSON)
					.content(body("오타 고쳤어요")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.commentId").value(COMMENT_ID))
				.andExpect(jsonPath("$.data.content").value("오타 고쳤어요"));

			then(eventVideoInteractionService).should()
				.updateComment(USER_ID, VIDEO_ID, COMMENT_ID, "오타 고쳤어요");
		}

		@Test
		@DisplayName("빈 내용은 400이다 — 작성과 같은 요청 타입이라 검증도 같다")
		void 수정_빈_내용은_400이다() throws Exception {
			mockMvc.perform(patch(COMMENT_PATH, VIDEO_ID, COMMENT_ID)
					.header(HttpHeaders.AUTHORIZATION, bearer())
					.contentType(MediaType.APPLICATION_JSON)
					.content("{}"))
				.andExpect(status().isBadRequest());

			then(eventVideoInteractionService).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("댓글 삭제")
	class DeleteComment {

		@Test
		@DisplayName("서비스를 부르고 data 없는 성공을 반환한다")
		void 댓글_삭제는_data_없는_성공을_반환한다() throws Exception {
			mockMvc.perform(delete(COMMENT_PATH, VIDEO_ID, COMMENT_ID)
					.header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200))
				.andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));

			then(eventVideoInteractionService).should().deleteComment(USER_ID, VIDEO_ID, COMMENT_ID);
		}
	}

	@Nested
	@DisplayName("도움돼요")
	class Helpful {

		@Test
		@DisplayName("추가는 처리 후 수와 helpfulByMe true를 반환한다")
		void 도움돼요_추가는_처리_후_수를_반환한다() throws Exception {
			given(eventVideoInteractionService.addHelpful(USER_ID, VIDEO_ID))
				.willReturn(new EventVideoHelpfulResponseDto(12L, true));

			mockMvc.perform(put(HELPFUL_PATH, VIDEO_ID)
					.header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.helpfulCount").value(12))
				.andExpect(jsonPath("$.data.helpfulByMe").value(true));

			then(eventVideoInteractionService).should().addHelpful(USER_ID, VIDEO_ID);
		}

		@Test
		@DisplayName("취소는 처리 후 수와 helpfulByMe false를 반환한다")
		void 도움돼요_취소는_helpfulByMe_false를_반환한다() throws Exception {
			given(eventVideoInteractionService.removeHelpful(USER_ID, VIDEO_ID))
				.willReturn(new EventVideoHelpfulResponseDto(11L, false));

			mockMvc.perform(delete(HELPFUL_PATH, VIDEO_ID)
					.header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.helpfulCount").value(11))
				.andExpect(jsonPath("$.data.helpfulByMe").value(false));

			then(eventVideoInteractionService).should().removeHelpful(USER_ID, VIDEO_ID);
		}
	}

	@Nested
	@DisplayName("댓글 목록")
	class GetComments {

		@Test
		@DisplayName("cursor·size 파라미터를 서비스에 그대로 넘긴다")
		void 댓글_목록은_cursor와_size를_그대로_넘긴다() throws Exception {
			given(eventVideoInteractionService.getComments(VIDEO_ID, "MTA0MjozMDIx", 5))
				.willReturn(new EventVideoCommentPageResponseDto(
					List.of(comment("저도 어제 다녀왔어요")), true, "MTA0MjozMDMw"));

			mockMvc.perform(get(COMMENTS_PATH, VIDEO_ID)
					.param("cursor", "MTA0MjozMDIx")
					.param("size", "5"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.comments.length()").value(1))
				.andExpect(jsonPath("$.data.comments[0].commentId").value(COMMENT_ID))
				.andExpect(jsonPath("$.data.hasNext").value(true))
				.andExpect(jsonPath("$.data.nextCursor").value("MTA0MjozMDMw"));

			then(eventVideoInteractionService).should().getComments(VIDEO_ID, "MTA0MjozMDIx", 5);
		}

		@Test
		@DisplayName("파라미터를 생략하면 cursor null·size 0으로 넘어간다 — 클램프는 서비스 몫")
		void 파라미터를_생략하면_cursor_null과_size_0이_넘어간다() throws Exception {
			given(eventVideoInteractionService.getComments(VIDEO_ID, null, 0))
				.willReturn(new EventVideoCommentPageResponseDto(List.of(), false, null));

			mockMvc.perform(get(COMMENTS_PATH, VIDEO_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.comments.length()").value(0))
				.andExpect(jsonPath("$.data.hasNext").value(false))
				.andExpect(jsonPath("$.data.nextCursor").value(org.hamcrest.Matchers.nullValue()));

			then(eventVideoInteractionService).should().getComments(VIDEO_ID, null, 0);
		}
	}
}
