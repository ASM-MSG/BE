package com.msg.fillmap.moderation.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import org.springframework.data.domain.Page;

/**
 * 관리자 신고 목록 응답 (MSG-195 FR-1). 사용자향 목록은 전부 keyset 커서지만 이 API 만 오프셋
 * 페이징이다 — 관리자 전용 소량 데이터라 커서의 이점이 없고, 페이지 번호로 앞뒤를 오가는 편이
 * 검토 작업에 맞는다 (§D5). 총 건수를 함께 내려 남은 신고량을 가늠할 수 있게 한다.
 */
@Schema(
	description = "관리자 신고 목록 응답 — 상태 필터 기준 한 페이지.",
	requiredProperties = {"items", "page", "size", "totalElements", "totalPages"}
)
public record AdminReportListResponseDto(
	@Schema(description = "이 페이지의 신고 목록. 정렬은 접수 최신순 고정")
	List<AdminReportItemResponseDto> items,

	@Schema(description = "현재 페이지 번호 (0부터)", example = "0")
	int page,

	@Schema(description = "페이지 크기", example = "20")
	int size,

	@Schema(description = "필터에 해당하는 전체 신고 수", example = "1")
	long totalElements,

	@Schema(description = "전체 페이지 수", example = "1")
	int totalPages
) {

	public static AdminReportListResponseDto from(Page<AdminReportItemResponseDto> page) {
		return new AdminReportListResponseDto(
			page.getContent(),
			page.getNumber(),
			page.getSize(),
			page.getTotalElements(),
			page.getTotalPages());
	}
}
