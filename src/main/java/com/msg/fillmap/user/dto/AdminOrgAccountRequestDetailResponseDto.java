package com.msg.fillmap.user.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.user.entity.OrgAccountRequest;

/**
 * 계정 발급 요청 상세 (MSG-499 API 3). 접수 필드 전체와 처리 결과를 담는다.
 *
 * <p>{@code updatedAt} 은 표시용이 아니라 <b>승인·반려 요청이 그대로 되돌려 보내야 하는 검토 기준
 * 시각</b>이다 — 이 값이 심사 사이의 재접수·변조를 잡아낸다.
 */
@Schema(
	description = "계정 발급 요청 상세",
	requiredProperties = {"id", "orgName", "contactName", "contactPhone", "email", "eventName", "content", "status",
		"rejectReason", "issuedUserId", "createdAt", "updatedAt", "processedAt"}
)
public record AdminOrgAccountRequestDetailResponseDto(
	@Schema(description = "요청 id", example = "7")
	Long id,

	@Schema(description = "기관명", example = "부산진구청")
	String orgName,

	@Schema(description = "담당자 이름", example = "김담당")
	String contactName,

	@Schema(description = "담당자 연락처", example = "010-1234-5678")
	String contactPhone,

	@Schema(description = "공식 이메일", example = "event@busanjin.go.kr")
	String email,

	@Schema(description = "예정 행사명", example = "서면 겨울 축제")
	String eventName,

	@Schema(description = "요청 내용")
	String content,

	@Schema(description = "처리 상태 (PENDING, ISSUED, REJECTED)", example = "PENDING")
	String status,

	@Schema(description = "반려 사유. 반려 건에만 값이 있다", nullable = true)
	String rejectReason,

	@Schema(description = "발급된 계정 id. 발급 건에만 값이 있다", nullable = true, example = "42")
	Long issuedUserId,

	@Schema(description = "최초 접수 시각")
	LocalDateTime createdAt,

	@Schema(description = "마지막 접수 시각 — 승인·반려 요청에 그대로 에코해야 하는 검토 기준 시각")
	LocalDateTime updatedAt,

	@Schema(description = "처리 시각. 승인·반려 건에만 값이 있다", nullable = true)
	LocalDateTime processedAt
) {

	public static AdminOrgAccountRequestDetailResponseDto from(OrgAccountRequest request) {
		return new AdminOrgAccountRequestDetailResponseDto(
			request.getId(),
			request.getOrgName(),
			request.getContactName(),
			request.getContactPhone(),
			request.getEmail(),
			request.getEventName(),
			request.getContent(),
			request.getStatus().name(),
			request.getRejectReason(),
			request.getIssuedUserId(),
			request.getCreatedAt(),
			request.getUpdatedAt(),
			request.getProcessedAt());
	}
}
