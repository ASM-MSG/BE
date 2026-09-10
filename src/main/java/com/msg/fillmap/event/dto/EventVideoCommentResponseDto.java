package com.msg.fillmap.event.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 행사 영상 댓글 한 건 (MSG-441 API 1·2·6). authorId 는 화면이 "내 댓글에만 수정·삭제 버튼"을 그리는
 * 재료다 — 로그인 사용자 id 와 비교하면 끝이라 서버가 mine 같은 파생 불리언을 따로 내리지 않는다.
 * 수정 이력은 남기지 않으므로 createdAt 은 수정 후에도 최초 작성 시각 그대로다.
 */
@Schema(description = "행사 영상 댓글",
	requiredProperties = {"commentId", "authorId", "authorNickname", "content", "createdAt"})
public record EventVideoCommentResponseDto(
	@Schema(description = "댓글 ID", example = "3021")
	Long commentId,

	@Schema(description = "작성자 사용자 ID", example = "7007")
	Long authorId,

	@Schema(description = "작성자 닉네임", example = "필맵러")
	String authorNickname,

	@Schema(description = "댓글 본문", example = "저도 어제 다녀왔어요")
	String content,

	@Schema(description = "작성 시각", example = "2026-10-06T12:30:00Z")
	LocalDateTime createdAt
) {
}
