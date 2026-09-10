package com.msg.fillmap.user.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.user.entity.OrgEmailChangeStatus;

/**
 * 아이디 변경 요청 큐의 한 줄 (MSG-500 §API 7). 현재 아이디({@code email})와 바꾸려는 값
 * ({@code requestedEmail})을 나란히 주는 것이 심사의 전부다 — 관리자는 둘과 기관명을 대조해 판단한다.
 *
 * <p>{@code createdAt} 은 <b>승인·반려 요청에 그대로 되돌려 보내야 하는 검토 기준 시각</b>이다. 접수가
 * 같은 대기 행을 제자리 갱신하므로(재제출), 이 값이 어긋나면 관리자가 본 적 없는 이메일을 승인하게 된다.
 */
@Schema(description = "아이디 변경 요청 큐 항목",
	requiredProperties = {"id", "userId", "orgName", "email", "requestedEmail", "status", "createdAt",
		"processedAt", "rejectReason"})
public record AdminEmailChangeRequestItemResponseDto(
	@Schema(description = "요청 id", example = "3")
	Long id,

	@Schema(description = "요청한 계정 id", example = "42")
	Long userId,

	@Schema(description = "기관명", example = "부산광역시 부산진구청", nullable = true)
	String orgName,

	@Schema(description = "현재 아이디(로그인 이메일)", example = "event@busanjin.go.kr")
	String email,

	@Schema(description = "바꾸려는 이메일", example = "festival@busanjin.go.kr")
	String requestedEmail,

	@Schema(description = "처리 상태", example = "PENDING")
	OrgEmailChangeStatus status,

	@Schema(description = "마지막 접수 시각 (UTC) — 승인·반려 요청에 되돌려 보내는 검토 기준 시각",
		example = "2026-08-28T02:00:00Z")
	LocalDateTime createdAt,

	@Schema(description = "처리 시각 (UTC) — 대기 중이면 null", nullable = true)
	LocalDateTime processedAt,

	@Schema(description = "반려 사유 — 반려된 요청에만 있다", nullable = true)
	String rejectReason
) {
}
