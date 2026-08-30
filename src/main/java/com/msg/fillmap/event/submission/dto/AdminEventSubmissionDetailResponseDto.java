package com.msg.fillmap.event.submission.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 관리자 심사 상세 (MSG-500 §API 2). 행사 운영자 상세(MSG-498)와 <b>같은 폼 필드</b>에 심사 재료 셋을
 * 더한다 — 신청 계정 정보(누가 냈나), 노출 영역 사각형(어디에 깔리나), 상태 이력이다.
 * <p>
 * 존재 은닉이 없다: 없는 id 는 그대로 404 다(관리자는 전체를 보는 주체라 숨길 것이 없다).
 */
@Schema(description = "관리자 심사 상세",
	requiredProperties = {"id", "submissionNo", "type", "status", "title", "organizerName", "startsOn", "endsOn",
		"operatingHours", "programDescription", "description", "imageUrl", "orgName", "contactName", "email",
		"locations", "exposureRect", "history", "createdAt", "updatedAt"})
public record AdminEventSubmissionDetailResponseDto(
	@Schema(description = "신청 id", example = "7")
	Long id,

	@Schema(description = "신청 번호", example = "FM-2026-0007")
	String submissionNo,

	@Schema(description = "등록 유형", example = "FESTIVAL")
	String type,

	@Schema(description = "신청 상태", example = "IN_REVIEW")
	String status,

	@Schema(description = "축제명 / 팝업명")
	String title,

	@Schema(description = "주최 기관 — 신청 폼에 적힌 값")
	String organizerName,

	@Schema(description = "행사 시작일", example = "2026-11-07")
	LocalDate startsOn,

	@Schema(description = "행사 종료일", example = "2026-11-07")
	LocalDate endsOn,

	@Schema(description = "운영 시간 — POPUP 만 값이 있다", nullable = true)
	String operatingHours,

	@Schema(description = "주요 프로그램 — FESTIVAL 만 값이 있다", nullable = true)
	String programDescription,

	@Schema(description = "행사 소개")
	String description,

	@Schema(description = "대표 이미지 열람용 presigned GET URL")
	String imageUrl,

	@Schema(description = "신청 계정의 기관명", example = "부산광역시 부산진구청", nullable = true)
	String orgName,

	@Schema(description = "신청 계정의 담당자 이름", example = "김담당")
	String contactName,

	@Schema(description = "신청 계정의 공식 이메일 (로그인 아이디)", example = "event@busanjin.go.kr")
	String email,

	@Schema(description = "위치 목록 — 순번 오름차순")
	List<EventSubmissionLocationResponseDto> locations,

	@Schema(description = "전 위치 셀 합집합의 경계 사각형 — 조회 시점 계산이고 저장하지 않는다")
	EventSubmissionAreaRectDto exposureRect,

	@Schema(description = "상태 이력 — 발생 순")
	List<EventSubmissionHistoryResponseDto> history,

	@Schema(description = "접수 시각 (UTC)", example = "2026-08-28T02:00:00Z")
	LocalDateTime createdAt,

	@Schema(description = "마지막 변경 시각 (UTC)", example = "2026-08-28T02:11:00Z")
	LocalDateTime updatedAt
) {
}
