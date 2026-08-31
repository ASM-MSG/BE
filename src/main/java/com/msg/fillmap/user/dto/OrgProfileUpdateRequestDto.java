package com.msg.fillmap.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 행사 운영자 담당자 정보 수정 요청 (MSG-497 FR-23). 아이디(이메일)는 이 요청으로 못 바꾼다 —
 * 필드 자체가 없다.
 */
@Schema(description = "담당자 정보 수정 요청")
public record OrgProfileUpdateRequestDto(
	@Schema(description = "담당자 이름 (2~20자). users.nickname 에 저장되므로 가입 닉네임과 같은 제약이다", example = "김담당")
	@NotBlank(message = "담당자 이름은 필수 항목입니다")
	@Size(min = 2, max = 20, message = "담당자 이름은 2자 이상 20자 이하이어야 합니다")
	String contactName,

	@Schema(description = "담당자 연락처. 숫자로 시작하고 끝나는 숫자·하이픈 9~20자", example = "010-1234-5678")
	@NotBlank(message = "담당자 연락처는 필수 항목입니다")
	@Pattern(regexp = "^[0-9][0-9-]{7,18}[0-9]$", message = "올바른 연락처 형식이 아닙니다")
	String contactPhone
) {
}
