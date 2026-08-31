package com.msg.fillmap.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 아이디 변경 승인 결과 (MSG-500 §API 7). {@code emailSent} 가 false 여도 아이디는 이미 바뀌었다 —
 * 발송 실패가 교체를 되돌리지 않는다. 이 흐름만 메일을 보내는 이유는 <b>로그인 수단 자체가 바뀌는
 * 사건</b>이라, 알리지 않으면 행사 운영자가 계정 접근을 잃기 때문이다(발급·반려 통보는 수기).
 */
@Schema(description = "아이디 변경 승인 결과", requiredProperties = {"requestId", "email", "emailSent"})
public record EmailChangeApproveResponseDto(
	@Schema(description = "승인한 요청 id", example = "3")
	Long requestId,

	@Schema(description = "교체된 새 아이디(로그인 이메일)", example = "festival@busanjin.go.kr")
	String email,

	@Schema(description = "새 이메일로 보낸 통지 성공 여부 — false 여도 교체는 유지된다", example = "true")
	boolean emailSent
) {
}
