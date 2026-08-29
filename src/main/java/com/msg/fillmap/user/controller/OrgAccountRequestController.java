package com.msg.fillmap.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.response.SuccessResponse;
import com.msg.fillmap.user.dto.OrgAccountRequestCreateRequestDto;
import com.msg.fillmap.user.service.OrgAccountRequestService;

/**
 * 행사 운영자 계정 발급 요청 접수 API (MSG-499 FR-6). 신청자에게는 아직 계정이 없어 비로그인이다 —
 * SecurityConfig 가 이 POST 하나만 permitAll 로 연다.
 */
@Tag(
	name = "행사 운영자 계정 발급 요청 (Org Account Request)",
	description = "계정이 없는 행사 운영자가 발급을 신청하는 공개 폼. 비로그인 호출이다."
)
@RestController
@RequiredArgsConstructor
public class OrgAccountRequestController {

	private final OrgAccountRequestService orgAccountRequestService;

	@Operation(
		summary = "계정 발급 요청 접수",
		description = "계정 발급 신청을 대기 상태로 접수한다. 관리자가 큐에서 검토해 승인하면 계정이 만들어지고 "
			+ "초기 비밀번호가 공식 이메일로 발송된다.\n\n"
			+ "같은 공식 이메일의 대기 요청이 이미 있으면 그 요청이 새 내용으로 갱신된다 — 마지막 접수가 "
			+ "유효하므로 더블클릭 재제출과 오타 정정 재접수가 한 건으로 수렴한다. 신청 번호는 부여하지 않는다."
	)
	@PostMapping("/api/org-account-requests")
	public SuccessResponse<Void> create(@Valid @RequestBody OrgAccountRequestCreateRequestDto request) {
		orgAccountRequestService.create(request);
		return new SuccessResponse<>(null);
	}
}
