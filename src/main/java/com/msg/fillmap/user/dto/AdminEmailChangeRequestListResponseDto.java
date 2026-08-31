package com.msg.fillmap.user.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import org.springframework.data.domain.Page;

/**
 * 아이디 변경 요청 큐 (MSG-500 §API 7). 계정 발급 요청 큐(MSG-499)와 같은 구조다 — 관리자 전용 소량
 * 데이터라 오프셋 페이징이고, 건수 3종은 상태 필터와 무관한 전체 집계로 탭 뱃지의 재료다.
 */
@Schema(description = "아이디 변경 요청 목록 — 상태 필터 기준 한 페이지와 상태별 전체 건수.",
	requiredProperties = {"pendingCount", "approvedCount", "rejectedCount", "totalElements", "page", "size",
		"requests"})
public record AdminEmailChangeRequestListResponseDto(
	@Schema(description = "대기 건수 (필터와 무관한 전체 집계)", example = "2")
	long pendingCount,

	@Schema(description = "승인 건수 (필터와 무관한 전체 집계)", example = "7")
	long approvedCount,

	@Schema(description = "반려 건수 (필터와 무관한 전체 집계)", example = "1")
	long rejectedCount,

	@Schema(description = "필터에 해당하는 전체 요청 수", example = "2")
	long totalElements,

	@Schema(description = "현재 페이지 번호 (0부터)", example = "0")
	int page,

	@Schema(description = "페이지 크기", example = "20")
	int size,

	@Schema(description = "이 페이지의 요청 목록. 정렬은 마지막 접수 최신순 고정")
	List<AdminEmailChangeRequestItemResponseDto> requests
) {

	public static AdminEmailChangeRequestListResponseDto of(Page<AdminEmailChangeRequestItemResponseDto> page,
		long pendingCount, long approvedCount, long rejectedCount) {
		return new AdminEmailChangeRequestListResponseDto(pendingCount, approvedCount, rejectedCount,
			page.getTotalElements(), page.getNumber(), page.getSize(), page.getContent());
	}
}
