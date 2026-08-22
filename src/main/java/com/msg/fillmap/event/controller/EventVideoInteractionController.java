package com.msg.fillmap.event.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.event.dto.EventVideoCommentPageResponseDto;
import com.msg.fillmap.event.dto.EventVideoCommentRequestDto;
import com.msg.fillmap.event.dto.EventVideoCommentResponseDto;
import com.msg.fillmap.event.dto.EventVideoHelpfulResponseDto;
import com.msg.fillmap.event.service.EventVideoInteractionService;
import com.msg.fillmap.response.SuccessResponse;

/**
 * 행사 영상 댓글·도움돼요 API (MSG-441). 댓글 목록(GET)만 비로그인 열람이고 변경 다섯은 로그인부터다 —
 * MSG-439 가 확정한 "조회는 비로그인, 쓰기는 로그인" 경계의 연장이다. SecurityConfig 에는 댓글 목록 경로만
 * GET 한정으로 등록하고 나머지 다섯은 등록하지 않아 {@code anyRequest().authenticated()} 가 적용된다.
 */
@Tag(name = "행사 (Events)", description = "행사 영상의 댓글·도움돼요 API.")
@RestController
@RequiredArgsConstructor
public class EventVideoInteractionController {

	private final EventVideoInteractionService eventVideoInteractionService;

	@Operation(
		summary = "행사 영상 댓글 작성",
		description = "행사 영상에 댓글을 단다. 내용은 1~500자다.\n\n"
			+ "행사방이 아카이브로 넘어가면 댓글을 더 달 수 없다 — 종료 30일 후부터 409 + developCode 13422 "
			+ "다(기존 댓글은 계속 보인다). 그 전까지는 예정·진행 중은 물론 유예 기간(종료 후 30일)에도 쓸 수 "
			+ "있고, 유예 기간에 새로 올라온 영상에도 댓글을 남길 수 있다.\n\n"
			+ "상세에 보이는 영상에만 쓸 수 있다 — 삭제·블라인드·비공개·처리 미완료 영상과 행사 영상이 아닌 "
			+ "영상 id 는 올린 본인에게도 404 + 13406 이다."
	)
	@PostMapping("/api/event-videos/{videoId}/comments")
	public SuccessResponse<EventVideoCommentResponseDto> createComment(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "영상 id", example = "1042") @PathVariable long videoId,
		@Valid @RequestBody EventVideoCommentRequestDto request
	) {
		return SuccessResponse.of(
			eventVideoInteractionService.createComment(principal.userId(), videoId, request.content()));
	}

	@Operation(
		summary = "행사 영상 댓글 수정",
		description = "댓글 내용을 통째로 바꾼다. 본인 댓글만 고칠 수 있고 남의 댓글이면 403 + developCode "
			+ "13403, 없거나 다른 영상의 댓글이면 404 + 13407 이다.\n\n"
			+ "작성 시각은 그대로다(수정 이력을 남기지 않는다). 아카이브된 행사에서는 자기 댓글이든 남의 "
			+ "댓글이든 409 + 13422 로 같다 — 잠금이 권한 판정보다 앞이다."
	)
	@PatchMapping("/api/event-videos/{videoId}/comments/{commentId}")
	public SuccessResponse<EventVideoCommentResponseDto> updateComment(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "영상 id", example = "1042") @PathVariable long videoId,
		@Parameter(description = "댓글 id", example = "3021") @PathVariable long commentId,
		@Valid @RequestBody EventVideoCommentRequestDto request
	) {
		return SuccessResponse.of(eventVideoInteractionService.updateComment(
			principal.userId(), videoId, commentId, request.content()));
	}

	@Operation(
		summary = "행사 영상 댓글 삭제",
		description = "댓글을 실제로 지운다(복구 없음). 본인 댓글만 지울 수 있고 남의 댓글이면 403 + "
			+ "developCode 13403 이다.\n\n"
			+ "이미 지운 댓글을 다시 지우면 404 + 13407 이다 — 없는 댓글의 삭제를 성공으로 돌려주면 화면 상태 "
			+ "불일치가 감춰지기 때문이다(도움돼요 취소는 토글이라 멱등인 것과 다르다).\n\n"
			+ "아카이브된 행사(종료 30일 후)에서는 409 + 13422 다."
	)
	@DeleteMapping("/api/event-videos/{videoId}/comments/{commentId}")
	public SuccessResponse<Void> deleteComment(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "영상 id", example = "1042") @PathVariable long videoId,
		@Parameter(description = "댓글 id", example = "3021") @PathVariable long commentId
	) {
		eventVideoInteractionService.deleteComment(principal.userId(), videoId, commentId);
		return new SuccessResponse<>(null);   // data 없는 성공
	}

	@Operation(
		summary = "행사 영상 도움돼요 추가",
		description = "이 영상에 도움돼요를 누른다. 사용자당 한 번이고 이미 누른 상태에서 다시 불러도 성공하며 "
			+ "수가 늘지 않는다 — 네트워크 재시도가 수를 흔들지 않도록 PUT 으로 둔 이유다.\n\n"
			+ "응답의 helpfulCount 는 처리 후 다시 센 값이라 그 사이 다른 사람이 누른 것도 반영된다.\n\n"
			+ "아카이브된 행사(종료 30일 후)에서는 409 + developCode 13422 다 — 유예 기간까지는 계속 누를 수 "
			+ "있다. 상세에 보이지 않는 영상은 404 + 13406 이다."
	)
	@PutMapping("/api/event-videos/{videoId}/helpful")
	public SuccessResponse<EventVideoHelpfulResponseDto> addHelpful(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "영상 id", example = "1042") @PathVariable long videoId
	) {
		return SuccessResponse.of(eventVideoInteractionService.addHelpful(principal.userId(), videoId));
	}

	@Operation(
		summary = "행사 영상 도움돼요 취소",
		description = "누른 도움돼요를 되돌린다. 누른 적이 없어도 실패하지 않는다(멱등).\n\n"
			+ "아카이브된 행사(종료 30일 후)에서는 409 + developCode 13422 다 — 유예 기간까지는 취소할 수 "
			+ "있다. 상세에 보이지 않는 영상은 404 + 13406 이다."
	)
	@DeleteMapping("/api/event-videos/{videoId}/helpful")
	public SuccessResponse<EventVideoHelpfulResponseDto> removeHelpful(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "영상 id", example = "1042") @PathVariable long videoId
	) {
		return SuccessResponse.of(eventVideoInteractionService.removeHelpful(principal.userId(), videoId));
	}

	@Operation(
		summary = "행사 영상 댓글 목록 조회",
		description = "영상에 달린 댓글을 오래된 순으로 한 페이지 돌려준다 — 새 댓글이 아래에 쌓이는 배열이다.\n\n"
			+ "영상 상세가 첫 페이지(20건)를 이미 품고 있으므로 이 API 는 둘째 페이지부터를 위한 것이다. "
			+ "cursor 는 직전 응답의 nextCursor 를 그대로 넣는다(첫 페이지는 생략). 형식이 깨졌거나 다른 영상 "
			+ "목록에서 받은 커서면 400 + developCode 13402 다. size 는 1~50 범위 밖이면 잘라서 적용하고 "
			+ "생략하면 20 이다.\n\n"
			+ "아카이브된 행사에서도 조회할 수 있고 댓글이 없으면 실패가 아니라 빈 페이지다. 비로그인으로도 "
			+ "조회할 수 있다."
	)
	@GetMapping("/api/event-videos/{videoId}/comments")
	public SuccessResponse<EventVideoCommentPageResponseDto> getComments(
		@Parameter(description = "영상 id", example = "1042") @PathVariable long videoId,
		@Parameter(description = "직전 응답의 nextCursor. 첫 페이지는 생략") @RequestParam(required = false) String cursor,
		@Parameter(description = "페이지 크기 (1~50, 기본 20)", example = "20")
		@RequestParam(required = false, defaultValue = "0") int size
	) {
		return SuccessResponse.of(eventVideoInteractionService.getComments(videoId, cursor, size));
	}
}
