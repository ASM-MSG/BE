package com.msg.fillmap.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 계정 발급 요청 반려 결과 (MSG-575). 반려 자체는 항상 저장돼 있고, 요청자에게 보낸 반려 안내 메일의 발송
 * 성공 여부만 실린다. false 면 관리자가 저장된 사유로 수기 통보한다 (재발송 API 없음).
 */
@Schema(description = "계정 발급 요청 반려 결과", requiredProperties = {"emailSent"})
public record OrgAccountRequestRejectResponseDto(
	@Schema(description = "반려 안내 메일 발송 성공 여부. true 는 SES 접수까지의 성공이고 배달 확인은 아니다", example = "true")
	boolean emailSent
) {
}
