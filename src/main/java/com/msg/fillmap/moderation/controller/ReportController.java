package com.msg.fillmap.moderation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.moderation.dto.ReportCreateRequestDto;
import com.msg.fillmap.moderation.dto.ReportCreateResponseDto;
import com.msg.fillmap.moderation.service.ReportService;
import com.msg.fillmap.response.SuccessResponse;

@Tag(name = "신고 (Report)", description = "영상 신고 접수 API (MSG-192). 인증 필수.")
@RestController
@RequestMapping("/api/videos/{videoId}/reports")
@RequiredArgsConstructor
public class ReportController {

	private final ReportService reportService;

	@Operation(
		summary = "영상 신고 접수",
		description = "다른 사람의 영상을 사유 5종(INAPPROPRIATE, PRIVACY, SPAM, COPYRIGHT, OTHER) 중 하나와 함께 "
			+ "신고한다. 접수된 신고는 PENDING 으로 쌓여 관리자 처리의 입력이 되며, 접수 자체는 영상 상태를 "
			+ "바꾸지 않는다. 같은 영상 재신고는 409, 자기 영상 신고는 400, 없는 영상·삭제·블라인드 영상은 "
			+ "재생 조회와 같은 404 다."
	)
	@PostMapping
	public SuccessResponse<ReportCreateResponseDto> report(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "신고할 영상 ID", example = "1042") @PathVariable Long videoId,
		@Valid @RequestBody ReportCreateRequestDto request
	) {
		return SuccessResponse.of(reportService.report(principal.userId(), videoId, request));
	}
}
