package com.msg.fillmap.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.user.entity.User;

/**
 * 행사 운영자 계정 설정 응답 (MSG-497 FR-23). 조회와 수정이 같은 형태를 반환해 응답이 어긋날 수 없다.
 * 아이디(이메일)는 읽기 전용이다 — 바꾸려면 변경 요청을 접수해 관리자 승인을 받아야 한다.
 */
@Schema(description = "행사 운영자 계정 설정 응답", requiredProperties = {"email", "contactName", "contactPhone"})
public record OrgProfileResponseDto(
	@Schema(description = "아이디(공식 이메일). 읽기 전용", example = "organizer@fillmap.dev")
	String email,

	@Schema(description = "담당자 이름", example = "김담당")
	String contactName,

	@Schema(description = "담당자 연락처. 아직 입력한 적이 없으면 null", example = "010-1234-5678", nullable = true)
	String contactPhone
) {

	public static OrgProfileResponseDto from(User user) {
		return new OrgProfileResponseDto(user.getEmail(), user.getNickname(), user.getContactPhone());
	}
}
