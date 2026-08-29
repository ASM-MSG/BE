package com.msg.fillmap.event.submission.dto;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 반려본 수정 재제출 요청 (MSG-498 FR-13). 제출 요청에서 유형을 뺀 전체이고, 부분 수정이 아니라 전체
 * 교체다 — 신청 하나가 폼 하나로 쓰였다 폼 하나로 고쳐지는 단위라서다 (D-8). 유형을 바꾸려면 새로 제출한다.
 * <p>
 * 이미지만 예외적으로 유지 선택이 있다 — {@code imageS3Key} 를 null 로 보내거나 생략하면 기존 이미지가
 * 유지되고, pending 키를 보내면 교체다. 상세 응답이 저장 키를 노출하지 않으므로 클라이언트가 확정 키를
 * 알 수 없고, 알 필요도 없어야 한다.
 */
@Schema(description = "반려본 수정 재제출 요청 — 유형을 뺀 전체 교체")
public record EventSubmissionUpdateRequestDto(
	@Schema(description = "축제명 / 팝업명", example = "부산불꽃축제")
	@NotBlank @Size(max = 100) String title,

	@Schema(description = "주최 기관 / 브랜드·운영사", example = "부산문화관광축제조직위원회")
	@NotBlank @Size(max = 100) String organizerName,

	@Schema(description = "행사 시작일 (KST 날짜)", example = "2026-11-07")
	@NotNull LocalDate startsOn,

	@Schema(description = "행사 종료일 (KST 날짜). 오늘 이전이면 13433", example = "2026-11-07")
	@NotNull LocalDate endsOn,

	@Schema(description = "운영 시간 — POPUP 전용 필수", example = "11:00 ~ 20:00")
	@Size(max = 100) String operatingHours,

	@Schema(description = "주요 프로그램 — FESTIVAL 전용 필수", example = "멀티불꽃쇼, 뮤직 불꽃쇼, 드론 라이트쇼 운영")
	@Size(min = 10, max = 2000) String programDescription,

	@Schema(description = "행사 소개", example = "광안리해수욕장 일원에서 열리는 부산 대표 불꽃 축제")
	@NotBlank @Size(min = 10, max = 2000) String description,

	@Schema(description = "대표 이미지의 pending S3 키. 생략하거나 null 이면 기존 이미지를 유지한다.",
		example = "event-submissions/pending/12/3f0c1f2e-....jpg")
	String imageS3Key,

	@Schema(description = "행사 위치 목록. 통째로 갈아끼우고 대표 격자를 전부 재계산한다.")
	List<@NotNull @Valid EventSubmissionLocationRequestDto> locations
) implements EventSubmissionForm {
}
