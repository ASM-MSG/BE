package com.msg.fillmap.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 행사 운영자 계정 직접 발급 (MSG-499 API 6). 공문으로 먼저 확인된 기관이라 발급 요청 행 없이 만든다.
 *
 * <p>연락처만 선택 항목이다 — 발급의 필수 재료는 기관명·담당자·공식 이메일 셋이고(FR-1) 연락처는
 * 공문에 없을 수 있다.
 */
@Schema(description = "행사 운영자 계정 직접 발급 요청")
public record OrgAccountCreateRequestDto(
	@Schema(description = "기관명", example = "부산진구청")
	@NotBlank(message = "기관명은 필수 항목입니다")
	@Size(max = 100, message = "기관명은 100자 이하이어야 합니다")
	String orgName,

	@Schema(description = "담당자 이름 (2~20자)", example = "김담당")
	@NotBlank(message = "담당자 이름은 필수 항목입니다")
	@Size(min = 2, max = 20, message = "담당자 이름은 2자 이상 20자 이하이어야 합니다")
	String contactName,

	@Schema(description = "공식 이메일. 계정 아이디이자 초기 비밀번호를 받을 주소다", example = "event@busanjin.go.kr")
	@NotBlank(message = "공식 이메일은 필수 항목입니다")
	@Email(message = "올바른 이메일 형식이 아닙니다")
	@Size(max = 255, message = "공식 이메일은 255자 이하이어야 합니다")
	String email,

	@Schema(description = "담당자 연락처 (선택). 값이 있으면 숫자로 시작하고 끝나는 숫자·하이픈 9~20자", example = "010-1234-5678")
	@Pattern(regexp = "^[0-9][0-9-]{7,18}[0-9]$", message = "올바른 연락처 형식이 아닙니다")
	String contactPhone
) {
}
