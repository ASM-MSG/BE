package com.msg.fillmap.event.submission.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.event.submission.entity.EventSubmissionType;

/**
 * 승인 행사 목록의 한 줄 (MSG-500 §API 5, D-11). 승인 행사의 안정 식별자는 신청 id 다 — 산출물(미션·위치)은
 * 유형마다 형태가 달라 목록의 키가 될 수 없다.
 *
 * <p>{@code status} 는 저장값이 아니라 조회 시점 KST 오늘과 기간으로 만든 파생값이고, 목록 필터와 같은
 * 식에서 나온다. {@code unpublishedAt} 이 있으면 중지된 행사이며 <b>파생 탭에는 그대로 남는다</b> —
 * 목록에서 지우면 관리자가 무엇을 왜 내렸는지 다시 볼 자리가 사라진다.
 */
@Schema(description = "승인 행사 목록 항목",
	requiredProperties = {"submissionId", "approvalNo", "submissionNo", "type", "title", "organizerName", "orgName",
		"startsOn", "endsOn", "status", "unpublished", "unpublishedAt", "unpublishReason"})
public record AdminApprovedEventItemResponseDto(
	@Schema(description = "승인 행사 식별자 (= 신청 id)", example = "7")
	Long submissionId,

	@Schema(description = "승인 번호", example = "APR-2026-0001")
	String approvalNo,

	@Schema(description = "신청 번호", example = "FM-2026-0007")
	String submissionNo,

	@Schema(description = "등록 유형", example = "FESTIVAL")
	EventSubmissionType type,

	@Schema(description = "축제명 / 팝업명", example = "부산불꽃축제")
	String title,

	@Schema(description = "주최 기관 — 신청 폼에 적힌 값")
	String organizerName,

	@Schema(description = "기관명 — 신청 계정에 등록된 값", nullable = true)
	String orgName,

	@Schema(description = "행사 시작일", example = "2026-11-07")
	LocalDate startsOn,

	@Schema(description = "행사 종료일", example = "2026-11-09")
	LocalDate endsOn,

	@Schema(description = "파생 상태 (UPCOMING 예정 · EXPOSED 노출 중 · ENDED 종료)", example = "EXPOSED")
	String status,

	@Schema(description = "노출 중지 여부", example = "false")
	boolean unpublished,

	@Schema(description = "노출 중지 시각 (UTC) — 중지되지 않았으면 null", nullable = true)
	LocalDateTime unpublishedAt,

	@Schema(description = "노출 중지 사유 — 중지되지 않았으면 null", nullable = true)
	String unpublishReason
) {

	/**
	 * 리포지토리 생성자 프로젝션이 부르는 형태 — 중지 여부는 중지 시각에서 파생하므로 쿼리가 따로 계산하지
	 * 않는다. 별도 생성자로 두는 것은 {@code unpublished} 가 <b>진짜 필드</b>여야 하기 때문이다: 파생
	 * 메서드로 두면 Jackson 이 레코드 컴포넌트만 직렬화해 응답에서 키가 통째로 빠진다(스키마 가드가 잡는다).
	 */
	public AdminApprovedEventItemResponseDto(Long submissionId, String approvalNo, String submissionNo,
		EventSubmissionType type, String title, String organizerName, String orgName, LocalDate startsOn,
		LocalDate endsOn, String status, LocalDateTime unpublishedAt, String unpublishReason) {
		this(submissionId, approvalNo, submissionNo, type, title, organizerName, orgName, startsOn, endsOn,
			status, unpublishedAt != null, unpublishedAt, unpublishReason);
	}
}
