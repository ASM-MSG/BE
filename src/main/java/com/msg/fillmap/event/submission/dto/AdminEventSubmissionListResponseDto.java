package com.msg.fillmap.event.submission.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import org.springframework.data.domain.Page;

/**
 * 관리자 심사 큐 (MSG-500 §API 1). 관리자 전용 소량 데이터라 커서가 아니라 오프셋 페이징이다
 * (MSG-499 요청 큐 선례). 건수 3종은 상태 필터와 무관한 전체 집계로 화면 탭 뱃지의 재료다.
 */
@Schema(description = "관리자 심사 큐 — 상태 필터 기준 한 페이지와 상태별 전체 건수.",
	requiredProperties = {"counts", "totalElements", "page", "size", "submissions"})
public record AdminEventSubmissionListResponseDto(
	@Schema(description = "상태별 전체 건수 (필터와 무관)")
	EventSubmissionStatusCountsResponseDto counts,

	@Schema(description = "필터에 해당하는 전체 신청 수", example = "3")
	long totalElements,

	@Schema(description = "현재 페이지 번호 (0부터)", example = "0")
	int page,

	@Schema(description = "페이지 크기", example = "20")
	int size,

	@Schema(description = "이 페이지의 신청 목록. 정렬은 접수 최신순 고정")
	List<AdminEventSubmissionItemResponseDto> submissions
) {

	public static AdminEventSubmissionListResponseDto of(Page<AdminEventSubmissionItemResponseDto> page,
		long inReview, long approved, long rejected) {
		return new AdminEventSubmissionListResponseDto(
			new EventSubmissionStatusCountsResponseDto(inReview, approved, rejected),
			page.getTotalElements(),
			page.getNumber(),
			page.getSize(),
			page.getContent());
	}
}
