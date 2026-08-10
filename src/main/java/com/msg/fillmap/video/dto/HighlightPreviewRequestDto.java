package com.msg.fillmap.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;

@Schema(description = "하이라이트 선분석 요청 (MSG-351). 원본은 presign(purpose=HIGHLIGHT_PREVIEW)으로 먼저 올린다.")
public record HighlightPreviewRequestDto(
	@Schema(description = "presign 으로 올린 원본의 pending 키. videos/pending/{내 userId}/ prefix 여야 한다",
		example = "videos/pending/42/550e8400-e29b-41d4-a716-446655440000.mp4")
	@NotBlank String s3Key
) {
}
