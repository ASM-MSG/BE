package com.msg.fillmap.event.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 위치별 영상 피드 항목 (MSG-440 API 2). 16:9 카드 한 장의 재료다 — 상대 시간 표기("3시간 전")는 createdAt
 * 으로 클라이언트가 계산한다. 썸네일은 READY 게이트를 통과한 영상만 담기므로 항상 발급된다.
 * 하트 수·댓글 수는 MSG-441 이 만든 반응 테이블을 조회 시점에 센 값이다 — 상세의 도움돼요 수·댓글 수와
 * 같은 원천을 같은 술어로 세므로 두 화면의 수가 갈릴 수 없다(US-006). 카드에는 토글이 없어 helpfulByMe 는
 * 싣지 않는다(상세에만 있다). 반응이 하나도 없는 영상은 0 이다.
 */
@Schema(description = "위치별 영상 피드 항목",
	requiredProperties = {"videoId", "thumbnailUrl", "durationSec", "createdAt", "helpfulCount", "commentCount"})
public record EventLocationVideoResponseDto(
	@Schema(description = "영상 ID — 상세 진입 키", example = "1042")
	Long videoId,

	@Schema(description = "썸네일 presigned GET URL")
	String thumbnailUrl,

	@Schema(description = "영상 길이(초)", example = "15")
	Short durationSec,

	@Schema(description = "업로드 시각", example = "2026-10-06T12:30:00Z")
	LocalDateTime createdAt,

	@Schema(description = "도움돼요 수", example = "12")
	long helpfulCount,

	@Schema(description = "댓글 수", example = "3")
	long commentCount
) {
}
