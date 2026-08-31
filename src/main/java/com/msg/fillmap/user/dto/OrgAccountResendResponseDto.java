package com.msg.fillmap.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 초기 비밀번호 재발송 결과 (MSG-499 API 7). <b>재발송은 재발급이다</b> — 평문을 저장하지 않아 보냈던
 * 비밀번호를 다시 보낼 수 없으므로 새 비밀번호로 교체해 보내고, 이전 초기 비밀번호는 즉시 무효가 된다.
 */
@Schema(description = "초기 비밀번호 재발송 결과", requiredProperties = "emailSent")
public record OrgAccountResendResponseDto(
	@Schema(description = "메일 발송 성공 여부. true 는 SES 접수까지의 성공이고 배달 확인은 아니다", example = "true")
	boolean emailSent
) {
}
