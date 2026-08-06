package com.msg.fillmap.moderation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 영상 신고 접수 요청 (MSG-192). reason 을 enum 이 아니라 String 으로 받는다 — 미지 값이 역직렬화
 * 단계에서 500 이 되지 않게 하고, 서비스에서 파싱해 INVALID_REASON(11400)으로 변환한다
 * (VideoVisibilityRequestDto 선례).
 */
@Schema(description = "영상 신고 접수 요청. 사유 5종 중 하나와 선택적 상세 설명.")
public record ReportCreateRequestDto(
	@Schema(description = "신고 사유. INAPPROPRIATE, PRIVACY, SPAM, COPYRIGHT, OTHER 중 하나 (대소문자 무관)",
		example = "INAPPROPRIATE")
	@NotBlank(message = "신고 사유는 필수 항목입니다")
	String reason,

	@Schema(description = "상세 설명. OTHER 사유는 필수, 나머지 사유는 선택. 최대 500자",
		example = "타인의 얼굴이 그대로 찍혀 있습니다")
	@Size(max = 500, message = "상세 설명은 500자를 넘을 수 없습니다")
	String detail
) {
}
