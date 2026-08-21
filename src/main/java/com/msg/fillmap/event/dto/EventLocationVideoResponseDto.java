package com.msg.fillmap.event.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 위치별 영상 피드 항목 (MSG-440 API 2). 16:9 카드 한 장의 재료다 — 상대 시간 표기("3시간 전")는 createdAt
 * 으로 클라이언트가 계산한다. 썸네일은 READY 게이트를 통과한 영상만 담기므로 항상 발급된다.
 * 하트 수·댓글 수는 집계 원천 테이블이 MSG-441 에서 생기므로 이번 범위에 필드가 없다(자리 예약).
 */
@Schema(description = "위치별 영상 피드 항목",
	requiredProperties = {"videoId", "thumbnailUrl", "durationSec", "createdAt"})
public record EventLocationVideoResponseDto(
	@Schema(description = "영상 ID — 상세 진입 키", example = "1042")
	Long videoId,

	@Schema(description = "썸네일 presigned GET URL")
	String thumbnailUrl,

	@Schema(description = "영상 길이(초)", example = "15")
	Short durationSec,

	@Schema(description = "업로드 시각", example = "2026-10-06T12:30:00Z")
	LocalDateTime createdAt
) {
}
