package com.msg.fillmap.event.submission.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import org.springframework.data.domain.Page;

/**
 * 승인 행사 목록 (MSG-500 §API 5). 심사 큐와 같은 오프셋 페이징이고, 건수 3종은 탭과 무관한 전체 집계다
 * (화면 탭 뱃지 재료). 건수의 기준 날짜는 목록과 같은 KST 오늘이다.
 */
@Schema(description = "승인 행사 목록 — 탭 기준 한 페이지와 탭별 전체 건수.",
	requiredProperties = {"exposedCount", "upcomingCount", "endedCount", "totalElements", "page", "size", "events"})
public record AdminApprovedEventListResponseDto(
	@Schema(description = "노출 중 건수 (탭과 무관한 전체 집계)", example = "4")
	long exposedCount,

	@Schema(description = "예정 건수 (탭과 무관한 전체 집계)", example = "2")
	long upcomingCount,

	@Schema(description = "종료 건수 (탭과 무관한 전체 집계)", example = "9")
	long endedCount,

	@Schema(description = "탭에 해당하는 전체 행사 수", example = "4")
	long totalElements,

	@Schema(description = "현재 페이지 번호 (0부터)", example = "0")
	int page,

	@Schema(description = "페이지 크기", example = "20")
	int size,

	@Schema(description = "이 페이지의 행사 목록. 정렬은 시작일 최신순 고정")
	List<AdminApprovedEventItemResponseDto> events
) {

	public static AdminApprovedEventListResponseDto of(Page<AdminApprovedEventItemResponseDto> page,
		long exposedCount, long upcomingCount, long endedCount) {
		return new AdminApprovedEventListResponseDto(exposedCount, upcomingCount, endedCount,
			page.getTotalElements(), page.getNumber(), page.getSize(), page.getContent());
	}
}
