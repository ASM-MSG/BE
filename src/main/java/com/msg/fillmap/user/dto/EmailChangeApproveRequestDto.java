package com.msg.fillmap.user.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;

/**
 * 아이디 변경 요청 승인 (MSG-500 §API 7). 본문이 검토 기준 시각 하나인 것은 <b>낙관 가드</b>이기 때문이다 —
 * 목록·상세에서 본 {@code createdAt} 을 그대로 돌려보내면 전이 UPDATE 술어가 그 값과 원자로 비교해,
 * 검토 이후 재제출된 요청을 승인하는 사고를 막는다 (MSG-499 발급 요청 승인과 같은 패턴).
 */
@Schema(description = "아이디 변경 요청 승인", requiredProperties = {"requestedAt"})
public record EmailChangeApproveRequestDto(
	@Schema(description = "검토한 요청의 접수 시각 (목록의 createdAt 을 그대로)", example = "2026-08-28T02:00:00Z")
	@NotNull LocalDateTime requestedAt
) {
}
