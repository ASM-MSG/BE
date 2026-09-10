package com.msg.fillmap.event.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 도움돼요 변경 결과 (MSG-441 API 4·5). helpfulCount 는 처리 후 같은 트랜잭션에서 다시 센 값이라
 * 그 사이 다른 사용자가 누른 것도 반영된다(카운터를 저장하지 않는다). helpfulByMe 는 추가면 항상 true,
 * 취소면 항상 false 다 — 둘 다 멱등이라 요청 종류가 곧 결과 상태다.
 */
@Schema(description = "행사 영상 도움돼요 변경 결과", requiredProperties = {"helpfulCount", "helpfulByMe"})
public record EventVideoHelpfulResponseDto(
	@Schema(description = "처리 후 현재 도움돼요 수", example = "12")
	long helpfulCount,

	@Schema(description = "내가 누른 상태인지", example = "true")
	boolean helpfulByMe
) {
}
