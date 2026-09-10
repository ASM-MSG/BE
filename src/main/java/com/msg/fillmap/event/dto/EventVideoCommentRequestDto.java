package com.msg.fillmap.event.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 행사 영상 댓글 본문 (MSG-441 API 1·2). 작성과 수정이 같은 타입을 쓴다 — 필드와 검증이 같고, 수정이
 * 부분 갱신이 아니라 내용 전체 교체이기 때문이다. 500자는 컬럼 폭과 같은 값이다(짧은 반응 전제).
 */
@Schema(description = "행사 영상 댓글 작성·수정 요청", requiredProperties = {"content"})
public record EventVideoCommentRequestDto(
	@Schema(description = "댓글 본문 (1~500자)", example = "저도 어제 다녀왔어요")
	@NotBlank(message = "댓글 내용을 입력해주세요")
	@Size(max = 500, message = "댓글은 500자 이하로 입력해주세요")
	String content
) {
}
