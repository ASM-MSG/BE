package com.msg.fillmap.event.submission.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.event.submission.dto.EventSubmissionCreateRequestDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionDetailResponseDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionImagePresignRequestDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionImagePresignResponseDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionMyListResponseDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionSubmitResponseDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionUpdateRequestDto;
import com.msg.fillmap.event.submission.service.EventSubmissionService;
import com.msg.fillmap.response.SuccessResponse;

/**
 * 행사 등재 신청 API (MSG-498). 역할 인가는 SecurityConfig 의 {@code /api/org/**} matcher 가 전담하고
 * (MSG-496 — 비로그인 401, USER·ADMIN 403), 초기 비밀번호 상태의 계정은 게이트 인터셉터(MSG-497)가 여기
 * 닿기 전에 막는다. 컨트롤러에 역할 검사가 없는 이유이고, 내 신청인지의 판정(FR-14)은 서비스 계층 몫이다.
 */
@Tag(name = "행사 등재 신청 (Org Submission)", description = "행사 운영자가 행사를 신청하고 반려본을 고쳐 다시 낸다.")
@RestController
@RequestMapping("/api/org/event-submissions")
@RequiredArgsConstructor
public class EventSubmissionController {

	private final EventSubmissionService eventSubmissionService;

	@Operation(
		summary = "대표 이미지 presigned URL 발급",
		description = "받은 uploadUrl 로 S3 에 직접 PUT 업로드한 뒤, 응답의 s3Key 를 신청 제출·재제출 요청의 "
			+ "imageS3Key 로 넘긴다. jpg·jpeg·png 만 받고 상한은 10MB 다."
	)
	@PostMapping("/image/presigned-url")
	public SuccessResponse<EventSubmissionImagePresignResponseDto> issueImagePresignedUrl(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Valid @RequestBody EventSubmissionImagePresignRequestDto request
	) {
		return SuccessResponse.of(eventSubmissionService.issueImagePresignedUrl(principal.userId(), request));
	}

	@Operation(
		summary = "행사 등재 신청 제출",
		description = "심사 중 상태로 접수하고 신청 번호(FM-2026-XXXX 꼴)를 부여한다. 위치마다 대표 격자를 "
			+ "서버가 계산해 저장하며, 위치 하나의 영역은 겹침을 한 번만 세는 합집합 기준 최대 81칸이다.\n\n"
			+ "유형별 필수 항목이 다르다 — FESTIVAL 은 주요 프로그램, POPUP 은 운영 시간, EVENT 는 참여 방식과 "
			+ "참여할 승인 이벤트 회차(parentOccurrenceId)이고 자기 유형이 아닌 항목이 실려 오면 거부한다. "
			+ "EVENT 의 위치는 대표 위치 정확히 1곳이고, 참여할 회차가 이미 끝났으면 접수하지 않는다.\n\n"
			+ "위치에는 이름 필드가 없고 배열 순서가 곧 순번이다."
	)
	@PostMapping
	public SuccessResponse<EventSubmissionSubmitResponseDto> submit(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Valid @RequestBody EventSubmissionCreateRequestDto request
	) {
		return SuccessResponse.of(eventSubmissionService.submit(principal.userId(), request));
	}

	@Operation(
		summary = "내 신청 목록",
		description = "콘솔 홈 현황 카드와 최근 신청 목록의 재료다. 상태별 건수는 내 신청 전체 기준이고 "
			+ "목록은 최신 제출 순이다. 페이지네이션은 없다."
	)
	@GetMapping("/my")
	public SuccessResponse<EventSubmissionMyListResponseDto> getMySubmissions(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal
	) {
		return SuccessResponse.of(eventSubmissionService.getMySubmissions(principal.userId()));
	}

	@Operation(
		summary = "신청 상세",
		description = "기본 정보와 위치 목록(순번·대표 격자·표시명 재료·제출 원본 사각형), 상태 이력, 반려 "
			+ "항목과 사유를 돌려준다. 반려 항목은 현재 상태가 반려일 때만 값이 있고, 과거 반려는 재제출 "
			+ "뒤에도 이력에 남는다.\n\n"
			+ "없는 신청과 남의 신청은 완전히 같은 실패 응답이다 — 응답 차이로 남의 신청 존재를 추측할 수 없다."
	)
	@GetMapping("/{submissionId}")
	public SuccessResponse<EventSubmissionDetailResponseDto> getSubmission(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "신청 id", example = "7") @PathVariable Long submissionId
	) {
		return SuccessResponse.of(eventSubmissionService.getSubmission(principal.userId(), submissionId));
	}

	@Operation(
		summary = "반려본 수정 재제출",
		description = "반려된 신청만 수정할 수 있고, 재제출하면 상태가 심사 중으로 돌아간다(신청 번호는 그대로다). "
			+ "부분 수정이 아니라 전체 교체이고 등록 유형은 바꿀 수 없다 — 유형을 바꾸려면 새로 제출한다.\n\n"
			+ "imageS3Key 를 생략하거나 null 로 보내면 기존 대표 이미지가 유지되고, 새 pending 키를 보내면 교체된다."
	)
	@PatchMapping("/{submissionId}")
	public SuccessResponse<EventSubmissionSubmitResponseDto> resubmit(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "신청 id", example = "7") @PathVariable Long submissionId,
		@Valid @RequestBody EventSubmissionUpdateRequestDto request
	) {
		return SuccessResponse.of(eventSubmissionService.resubmit(principal.userId(), submissionId, request));
	}
}
