package com.msg.fillmap.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 아이디(공식 이메일) 변경 요청 (MSG-497 FR-23). 접수와 저장까지가 이 요청의 전부다 —
 * 실제 아이디가 바뀌는 것은 관리자 승인 이후다.
 */
@Schema(description = "아이디(공식 이메일) 변경 요청")
public record OrgEmailChangeRequestDto(
	@Schema(description = "바꾸려는 공식 이메일", example = "new-organizer@fillmap.dev")
	@NotBlank(message = "이메일은 필수 항목입니다")
	@Email(message = "올바른 이메일 형식이 아닙니다")
	@Size(max = 255, message = "이메일은 최대 255자까지 가능합니다")
	String requestedEmail
) {
}
