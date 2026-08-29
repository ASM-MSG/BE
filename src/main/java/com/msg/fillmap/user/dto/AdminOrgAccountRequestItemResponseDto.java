package com.msg.fillmap.user.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.user.entity.OrgAccountRequest;

/** 계정 발급 요청 큐의 한 줄 (MSG-499 API 2). 상세 전용 필드(연락처·내용·사유)는 여기 싣지 않는다. */
@Schema(
	description = "계정 발급 요청 목록 항목",
	requiredProperties = {"id", "orgName", "contactName", "email", "eventName", "status", "createdAt", "updatedAt"}
)
public record AdminOrgAccountRequestItemResponseDto(
	@Schema(description = "요청 id", example = "7")
	Long id,

	@Schema(description = "기관명", example = "부산진구청")
	String orgName,

	@Schema(description = "담당자 이름", example = "김담당")
	String contactName,

	@Schema(description = "공식 이메일", example = "event@busanjin.go.kr")
	String email,

	@Schema(description = "예정 행사명", example = "서면 겨울 축제")
	String eventName,

	@Schema(description = "처리 상태 (PENDING, ISSUED, REJECTED)", example = "PENDING")
	String status,

	@Schema(description = "최초 접수 시각")
	LocalDateTime createdAt,

	@Schema(description = "마지막 접수 시각 — 정렬 기준이자 심사의 검토 기준 시각")
	LocalDateTime updatedAt
) {

	public static AdminOrgAccountRequestItemResponseDto from(OrgAccountRequest request) {
		return new AdminOrgAccountRequestItemResponseDto(
			request.getId(),
			request.getOrgName(),
			request.getContactName(),
			request.getEmail(),
			request.getEventName(),
			request.getStatus().name(),
			request.getCreatedAt(),
			request.getUpdatedAt());
	}
}
