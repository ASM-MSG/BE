package com.msg.fillmap.user.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.user.entity.User;

/**
 * 발급된 행사 운영자 계정 한 줄 (MSG-499 API 8). 화면의 "사용 중 / 초기 로그인 전" 라벨은
 * {@code mustChange} 의 파생이다(false 가 사용 중) — 전용 상태 컬럼을 따로 두지 않는다.
 */
@Schema(
	description = "발급된 행사 운영자 계정",
	requiredProperties = {"userId", "orgName", "contactName", "email", "contactPhone", "provider", "mustChange",
		"createdAt"}
)
public record AdminOrgAccountItemResponseDto(
	@Schema(description = "계정 id", example = "42")
	Long userId,

	@Schema(description = "기관명. 이 발급 경로 밖에서 만들어진 계정이면 null 일 수 있다", nullable = true,
		example = "부산진구청")
	String orgName,

	@Schema(description = "담당자 이름", example = "김담당")
	String contactName,

	@Schema(description = "공식 이메일 (계정 아이디)", example = "event@busanjin.go.kr")
	String email,

	@Schema(description = "담당자 연락처. 직접 발급에서 생략했으면 null 이다", nullable = true, example = "010-1234-5678")
	String contactPhone,

	@Schema(description = "로그인 제공자. 목록이 LOCAL 만 담는다는 사실의 확인 재료다", example = "LOCAL")
	String provider,

	@Schema(description = "초기 비밀번호 변경 강제 여부. true 면 초기 로그인 전, false 면 사용 중이다", example = "true")
	boolean mustChange,

	@Schema(description = "발급 시각")
	LocalDateTime createdAt
) {

	public static AdminOrgAccountItemResponseDto from(User user) {
		return new AdminOrgAccountItemResponseDto(
			user.getId(),
			user.getOrgName(),
			user.getNickname(),
			user.getEmail(),
			user.getContactPhone(),
			user.getProvider().name(),
			user.isPasswordMustChange(),
			user.getCreatedAt());
	}
}
