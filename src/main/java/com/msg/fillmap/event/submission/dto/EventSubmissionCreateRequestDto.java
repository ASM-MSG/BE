package com.msg.fillmap.event.submission.dto;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.msg.fillmap.event.submission.entity.EventSubmissionType;

/**
 * 행사 등재 신청 제출 요청 (MSG-498 FR-7). 유형별 항목 매핑은 title = 축제명/팝업명,
 * organizerName = 주최 기관/브랜드·운영사, startsOn·endsOn = 축제 기간/운영 기간이다.
 * 서술 항목은 구조화 없이 String 이고 최소 10자다 (피그마 #100).
 */
@Schema(description = "행사 등재 신청 제출 요청")
public record EventSubmissionCreateRequestDto(
	@Schema(description = "등록 유형 — FESTIVAL(지역축제) 또는 POPUP(팝업스토어)", example = "FESTIVAL")
	@NotNull EventSubmissionType type,

	@Schema(description = "축제명 / 팝업명", example = "부산불꽃축제")
	@NotBlank @Size(max = 100) String title,

	@Schema(description = "주최 기관 / 브랜드·운영사", example = "부산문화관광축제조직위원회")
	@NotBlank @Size(max = 100) String organizerName,

	@Schema(description = "행사 시작일 (KST 날짜)", example = "2026-11-07")
	@NotNull LocalDate startsOn,

	@Schema(description = "행사 종료일 (KST 날짜). 오늘 이전이면 13433", example = "2026-11-07")
	@NotNull LocalDate endsOn,

	@Schema(description = "운영 시간 — POPUP 전용 필수. FESTIVAL 에 실려 오면 13439", example = "11:00 ~ 20:00")
	@Size(max = 100) String operatingHours,

	@Schema(description = "주요 프로그램 — FESTIVAL 전용 필수. POPUP 에 실려 오면 13439",
		example = "멀티불꽃쇼, 뮤직 불꽃쇼, 드론 라이트쇼 운영")
	@Size(min = 10, max = 2000) String programDescription,

	@Schema(description = "행사 소개", example = "광안리해수욕장 일원에서 열리는 부산 대표 불꽃 축제")
	@NotBlank @Size(min = 10, max = 2000) String description,

	@Schema(description = "대표 이미지의 pending S3 키. presign 발급 응답의 s3Key 를 그대로 넣는다.",
		example = "event-submissions/pending/12/3f0c1f2e-....jpg")
	@NotBlank String imageS3Key,

	@Schema(description = "행사 위치 목록. 1개 이상 20개 이하이고 이름 필드가 없다.")
	List<@NotNull @Valid EventSubmissionLocationRequestDto> locations
) implements EventSubmissionForm {
}
