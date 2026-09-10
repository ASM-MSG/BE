package com.msg.fillmap.event.submission.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.event.submission.dto.AdminApprovedEventListResponseDto;
import com.msg.fillmap.event.submission.dto.AdminEventUnpublishRequestDto;
import com.msg.fillmap.event.submission.dto.AdminEventUnpublishResponseDto;
import com.msg.fillmap.event.submission.service.AdminApprovedEventService;
import com.msg.fillmap.response.SuccessResponse;

/**
 * 관리자 승인 행사 API (MSG-500 §API 5·6). 심사 큐(신청)와 경로를 가르는 것은 다루는 대상이 다르기
 * 때문이다 — 여기는 이미 승인돼 지도에 실린 행사다. ADMIN 검사는 SecurityConfig 의
 * {@code /api/admin/**} matcher 가 필터 단계에서 한다.
 */
@Tag(
	name = "관리자 승인 행사 (Admin Approved Event)",
	description = "승인된 행사의 상태별 조회와 노출 중지 API (MSG-500). ADMIN 권한 필수."
)
@RestController
@RequestMapping("/api/admin/events")
@RequiredArgsConstructor
public class AdminApprovedEventController {

	private final AdminApprovedEventService adminApprovedEventService;

	@Operation(
		summary = "승인 행사 목록 조회",
		description = "승인된 행사를 노출 중(EXPOSED)·예정(UPCOMING)·종료(ENDED) 탭으로 조회한다. 기본은 노출 "
			+ "중이다. 상태는 저장값이 아니라 <b>조회 시점 KST 오늘과 행사 기간으로 파생</b>하므로 시작일 당일은 "
			+ "노출 중, 종료일 당일도 노출 중이고 그 다음 날부터 종료다.\n\n"
			+ "탭 건수 3종은 탭과 무관한 전체 집계라 화면 뱃지에 그대로 쓴다. <b>노출이 중지된 행사도 탭에 "
			+ "그대로 남고</b> unpublished·unpublishedAt·unpublishReason 으로 구분된다 — 무엇을 왜 내렸는지 "
			+ "관리자가 계속 확인할 수 있어야 하기 때문이다.\n\n"
			+ "지원하지 않는 status 는 400(13455), page 음수나 size 범위(1~100) 밖은 400(13456) 이다."
	)
	@GetMapping
	public SuccessResponse<AdminApprovedEventListResponseDto> getEvents(
		@Parameter(description = "탭 필터 (EXPOSED, UPCOMING, ENDED — 대소문자 무관)", example = "EXPOSED")
		@RequestParam(defaultValue = "EXPOSED") String status,

		@Parameter(description = "페이지 번호 (0부터)", example = "0")
		@RequestParam(defaultValue = "0") int page,

		@Parameter(description = "페이지 크기 (1~100)", example = "20")
		@RequestParam(defaultValue = "20") int size
	) {
		return SuccessResponse.of(adminApprovedEventService.getEvents(status, page, size));
	}

	@Operation(
		summary = "행사 노출 중지",
		description = "승인된 행사의 지도 노출을 사유와 함께 중지한다. 중지하면 그 승인 미션이 지도 칩 목록·격자 "
			+ "선택·미션 상세·영상 목록·스탬프 판정·미션 경유 업로드에서 <b>즉시</b> 빠진다(재기동 불요). 알고 있는 "
			+ "missionId 로 여는 상세와 영상 목록도 없는 미션과 같은 404 가 된다.\n\n"
			+ "이미 완료한 사용자의 스탬프와 진행 기록은 그대로 남는다 — 중지는 노출을 끊는 것이지 기록을 "
			+ "회수하는 것이 아니다.\n\n"
			+ "사유는 신청 계정의 공식 이메일로 발송된다. 발송이 실패해도 중지는 유지되며 emailSent 가 false 로 "
			+ "온다 — 저장된 사유가 수기 재통지의 재료이고 재발송 API 는 없다. 중지 해제(재노출)도 이 티켓 "
			+ "범위 밖이다.\n\n"
			+ "없거나 승인되지 않은 신청은 404(13430), 이미 중지된 행사는 409(13453) 다."
	)
	@PostMapping("/{submissionId}/unpublish")
	public SuccessResponse<AdminEventUnpublishResponseDto> unpublish(
		@Parameter(description = "중지할 승인 행사 식별자 (= 신청 id)", example = "7") @PathVariable Long submissionId,
		@Valid @RequestBody AdminEventUnpublishRequestDto request
	) {
		return SuccessResponse.of(adminApprovedEventService.unpublish(submissionId, request));
	}
}
