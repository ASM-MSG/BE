package com.msg.fillmap.user.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;

/**
 * 계정 발급 요청 승인 (MSG-499 API 4). 상세 조회가 내려준 마지막 접수 시각을 그대로 에코한다 —
 * 접수 폼이 비로그인으로 열려 있어 관리자가 검토한 내용과 승인 클릭 시점의 행이 다를 수 있고
 * (재접수 또는 변조), 그 경우 발급하지 않고 재검토를 요구한다(1426).
 */
@Schema(description = "계정 발급 요청 승인 요청")
public record OrgAccountRequestApproveRequestDto(
	@Schema(description = "상세 조회로 받은 마지막 접수 시각. 값이 다르면 검토 이후 요청이 바뀐 것이라 승인이 거부된다")
	@NotNull(message = "검토 기준 시각은 필수 항목입니다")
	LocalDateTime updatedAt
) {
}
