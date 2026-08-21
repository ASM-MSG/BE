package com.msg.fillmap.event.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 위치별 영상 피드 페이지 (MSG-440 API 2). videos 는 한 페이지, hasNext 는 lookahead 판정 결과,
 * nextCursor 는 다음 페이지 opaque 커서다(마지막 페이지면 null). 총계는 담지 않는다 — 위치의 영상 수는
 * 위치 목록(MSG-439)이 이미 준다. 격자 영상 목록(GridVideoPageResponseDto)과 구조가 같지만 항목 타입이
 * 다르고 MSG-441 이 항목에 필드를 더할 예정이라 행사 전용 타입으로 둔다.
 */
@Schema(description = "위치별 영상 피드 페이지 (keyset 커서 페이지네이션)",
	requiredProperties = {"videos", "hasNext", "nextCursor"})
public record EventLocationVideoPageResponseDto(
	@Schema(description = "이 페이지의 영상. 조건에 맞는 영상이 없으면 빈 배열")
	List<EventLocationVideoResponseDto> videos,

	@Schema(description = "다음 페이지 존재 여부 (lookahead 판정)")
	boolean hasNext,

	@Schema(description = "다음 페이지 조회용 opaque 커서. 다음 요청 cursor 파라미터에 그대로 넣는다. "
		+ "마지막 페이지면 null", nullable = true)
	String nextCursor
) {
}
