package com.msg.fillmap.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 행사 운영자 계정 발급 요청 접수 (MSG-499 FR-6). 계정이 없는 신청자가 비로그인 공개 폼으로 보낸다.
 *
 * <p><b>여기 담긴 값은 전부 검증되지 않은 자기 신고다.</b> 형식 검증만 통과하면 저장되며, 기관의 실재와
 * 신청 사실은 관리자가 공문·공식 채널로 확인한 뒤 승인한다 — 신뢰 경계는 이 폼이 아니라 관리자 심사다.
 */
@Schema(description = "행사 운영자 계정 발급 요청 (비로그인 공개 폼)")
public record OrgAccountRequestCreateRequestDto(
	@Schema(description = "기관명", example = "부산진구청")
	@NotBlank(message = "기관명은 필수 항목입니다")
	@Size(max = 100, message = "기관명은 100자 이하이어야 합니다")
	String orgName,

	@Schema(description = "담당자 이름 (2~20자). 승인 시 계정 담당자 이름이 되므로 계정 설정과 같은 제약이다", example = "김담당")
	@NotBlank(message = "담당자 이름은 필수 항목입니다")
	@Size(min = 2, max = 20, message = "담당자 이름은 2자 이상 20자 이하이어야 합니다")
	String contactName,

	@Schema(description = "담당자 연락처. 숫자로 시작하고 끝나는 숫자·하이픈 9~20자", example = "010-1234-5678")
	@NotBlank(message = "담당자 연락처는 필수 항목입니다")
	@Pattern(regexp = "^[0-9][0-9-]{7,18}[0-9]$", message = "올바른 연락처 형식이 아닙니다")
	String contactPhone,

	@Schema(description = "공식 이메일. 승인 시 계정 아이디이자 초기 비밀번호를 받을 주소다", example = "event@busanjin.go.kr")
	@NotBlank(message = "공식 이메일은 필수 항목입니다")
	@Email(message = "올바른 이메일 형식이 아닙니다")
	@Size(max = 255, message = "공식 이메일은 255자 이하이어야 합니다")
	String email,

	@Schema(description = "예정 행사명", example = "서면 겨울 축제")
	@NotBlank(message = "예정 행사명은 필수 항목입니다")
	@Size(max = 200, message = "예정 행사명은 200자 이하이어야 합니다")
	String eventName,

	@Schema(description = "요청 내용", example = "12월 서면 일대 겨울 축제 등재를 위해 계정을 신청합니다.")
	@NotBlank(message = "요청 내용은 필수 항목입니다")
	@Size(max = 2000, message = "요청 내용은 2000자 이하이어야 합니다")
	String content
) {
}
