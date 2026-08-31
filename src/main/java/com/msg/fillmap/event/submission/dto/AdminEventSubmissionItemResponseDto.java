package com.msg.fillmap.event.submission.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.event.submission.entity.EventSubmissionStatus;
import com.msg.fillmap.event.submission.entity.EventSubmissionType;

/**
 * 관리자 심사 큐의 한 줄 (MSG-500 §API 1). 리포지토리 생성자 프로젝션이 직접 만드는 타입이라 유형·상태가
 * enum 그대로다 — 와이어에는 Jackson 이 상수명을 실어 다른 응답의 문자열과 같은 값이 나간다.
 * <p>
 * 주최 기관({@code organizerName}, 신청 폼 값)과 기관명({@code orgName}, 신청 계정 값)이 둘 다 있는 것은
 * 심사자가 <b>대조</b>해야 하는 두 값이기 때문이다 — 폼에 적힌 주최자와 계정 발급 시 확인된 기관이 다르면
 * 그 자체가 심사 신호다.
 */
@Schema(description = "관리자 심사 큐 항목",
	requiredProperties = {"id", "submissionNo", "type", "status", "title", "organizerName", "orgName", "startsOn",
		"endsOn", "locationCount", "createdAt", "updatedAt"})
public record AdminEventSubmissionItemResponseDto(
	@Schema(description = "신청 id", example = "7")
	Long id,

	@Schema(description = "신청 번호", example = "FM-2026-0007")
	String submissionNo,

	@Schema(description = "등록 유형", example = "FESTIVAL")
	EventSubmissionType type,

	@Schema(description = "신청 상태", example = "IN_REVIEW")
	EventSubmissionStatus status,

	@Schema(description = "축제명 / 팝업명", example = "부산불꽃축제")
	String title,

	@Schema(description = "주최 기관 — 신청 폼에 적힌 값", example = "부산문화관광축제조직위원회")
	String organizerName,

	@Schema(description = "기관명 — 신청 계정에 등록된 값", example = "부산광역시 부산진구청", nullable = true)
	String orgName,

	@Schema(description = "행사 시작일", example = "2026-11-07")
	LocalDate startsOn,

	@Schema(description = "행사 종료일", example = "2026-11-07")
	LocalDate endsOn,

	@Schema(description = "신청에 담긴 위치 수", example = "2")
	int locationCount,

	@Schema(description = "접수 시각 (UTC)", example = "2026-08-28T02:00:00Z")
	LocalDateTime createdAt,

	@Schema(description = "마지막 변경 시각 (UTC)", example = "2026-08-28T02:11:00Z")
	LocalDateTime updatedAt
) {
}
