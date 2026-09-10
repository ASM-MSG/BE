package com.msg.fillmap.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.response.SuccessResponse;
import com.msg.fillmap.user.dto.OrgEmailChangeRequestDto;
import com.msg.fillmap.user.dto.OrgProfileResponseDto;
import com.msg.fillmap.user.dto.OrgProfileUpdateRequestDto;
import com.msg.fillmap.user.service.OrgAccountService;

/**
 * 행사 운영자 계정 설정 API (MSG-497 FR-23). 인가는 SecurityConfig 의 {@code /api/org/**} matcher 가
 * 이미 ORG 로 걸어 두었고(MSG-496), 초기 비밀번호 상태의 계정은 게이트 인터셉터가 여기 닿기 전에 막는다.
 */
@Tag(name = "행사 운영자 계정 (Org Account)", description = "담당자 정보 조회·수정과 아이디 변경 요청. 행사 운영자 전용이다.")
@RestController
@RequestMapping("/api/org")
@RequiredArgsConstructor
public class OrgAccountController {

	private final OrgAccountService orgAccountService;

	@Operation(
		summary = "계정 설정 조회",
		description = "계정 설정 화면의 초기값이다. 아이디(이메일)는 읽기 전용으로 함께 내려간다."
	)
	@GetMapping("/profile")
	public SuccessResponse<OrgProfileResponseDto> getProfile(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal
	) {
		return SuccessResponse.of(orgAccountService.getProfile(principal.userId()));
	}

	@Operation(
		summary = "담당자 정보 수정",
		description = "담당자 이름과 연락처를 바꾸고 변경 후 값을 반환한다. 아이디(이메일)는 이 API 로 바꿀 수 없다."
	)
	@PatchMapping("/profile")
	public SuccessResponse<OrgProfileResponseDto> updateProfile(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Valid @RequestBody OrgProfileUpdateRequestDto request
	) {
		return SuccessResponse.of(orgAccountService.updateProfile(principal.userId(), request));
	}

	@Operation(
		summary = "아이디 변경 요청",
		description = "아이디(공식 이메일)는 기관 인증의 근거라 자체 변경이 불가하다. 이 API 는 변경 요청을 접수만 하고, "
			+ "관리자가 승인해야 실제로 바뀐다.\n\n"
			+ "대기 중인 요청이 있으면 그 요청이 새 값으로 갱신된다 — 마지막 요청이 유효하다."
	)
	@PostMapping("/email-change-request")
	public SuccessResponse<Void> requestEmailChange(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Valid @RequestBody OrgEmailChangeRequestDto request
	) {
		orgAccountService.requestEmailChange(principal.userId(), request);
		return new SuccessResponse<>(null);
	}
}
