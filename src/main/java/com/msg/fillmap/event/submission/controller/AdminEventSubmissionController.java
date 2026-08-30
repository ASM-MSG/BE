package com.msg.fillmap.event.submission.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.event.submission.dto.AdminEventSubmissionDetailResponseDto;
import com.msg.fillmap.event.submission.dto.AdminEventSubmissionListResponseDto;
import com.msg.fillmap.event.submission.service.AdminEventSubmissionService;
import com.msg.fillmap.response.SuccessResponse;

/**
 * 관리자 행사 등재 심사 API (MSG-500). ADMIN 검사는 여기에 없다 — SecurityConfig 의 {@code /api/admin/**}
 * matcher 가 필터 단계에서 거른다 (AdminOrgAccountController 선례).
 */
@Tag(
	name = "관리자 행사 등재 심사 (Admin Event Submission)",
	description = "행사 등재 신청을 검토하고 승인·반려하는 API (MSG-500). ADMIN 권한 필수."
)
@RestController
@RequestMapping("/api/admin/event-submissions")
@RequiredArgsConstructor
public class AdminEventSubmissionController {

	private final AdminEventSubmissionService adminEventSubmissionService;

	@Operation(
		summary = "심사 큐 조회",
		description = "상태 필터 기준으로 신청을 접수 최신순 페이지 단위로 조회한다. 기본은 심사 중(IN_REVIEW) "
			+ "신청이다. 상태별 건수 3종이 필터와 무관하게 함께 실려 탭 뱃지를 그릴 수 있다.\n\n"
			+ "항목의 organizerName 은 신청 폼의 주최 기관이고 orgName 은 신청 계정에 등록된 기관명이라, 둘이 "
			+ "다르면 그 자체가 심사 신호다.\n\n"
			+ "지원하지 않는 status 는 400(13455), page 음수나 size 범위(1~100) 밖은 400(13456) 이다."
	)
	@GetMapping
	public SuccessResponse<AdminEventSubmissionListResponseDto> getSubmissions(
		@Parameter(description = "신청 상태 필터 (IN_REVIEW, APPROVED, REJECTED — 대소문자 무관)",
			example = "IN_REVIEW")
		@RequestParam(defaultValue = "IN_REVIEW") String status,

		@Parameter(description = "페이지 번호 (0부터)", example = "0")
		@RequestParam(defaultValue = "0") int page,

		@Parameter(description = "페이지 크기 (1~100)", example = "20")
		@RequestParam(defaultValue = "20") int size
	) {
		return SuccessResponse.of(adminEventSubmissionService.getSubmissions(status, page, size));
	}

	@Operation(
		summary = "심사 상세 조회",
		description = "신청 폼 필드 전체(대표 이미지는 presigned GET URL)에 심사 재료를 더해 조회한다 — 신청 "
			+ "계정 정보, 전 위치를 감싸는 노출 영역 사각형, 상태 이력이다. 노출 영역은 조회 시점 계산값이라 "
			+ "저장되지 않는다.\n\n"
			+ "관리자 조회에는 존재 은닉이 없다 — 없는 신청은 그대로 404(13430) 다."
	)
	@GetMapping("/{submissionId}")
	public SuccessResponse<AdminEventSubmissionDetailResponseDto> getSubmission(
		@Parameter(description = "조회할 신청 id", example = "7") @PathVariable Long submissionId
	) {
		return SuccessResponse.of(adminEventSubmissionService.getSubmission(submissionId));
	}
}
