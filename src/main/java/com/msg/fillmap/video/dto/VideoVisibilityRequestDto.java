package com.msg.fillmap.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;

/**
 * 영상 공개 범위 전환 요청 (MSG-162). 미지 값이 역직렬화 단계에서 500 을 내지 않도록 enum 이 아니라 String 으로
 * 받고, 서비스에서 {@code Visibility.valueOf} 로 파싱해 실패 시 INVALID_VISIBILITY(400)로 변환한다.
 */
@Schema(description = "영상 공개 범위 전환 요청. PUBLIC 또는 PRIVATE.")
public record VideoVisibilityRequestDto(
	@Schema(description = "공개 범위. PUBLIC 또는 PRIVATE (대소문자 무관)", example = "PUBLIC")
	@NotBlank String visibility
) {
}
