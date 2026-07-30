package com.msg.fillmap.video.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 격자 전역 영상 목록 페이지 응답 (MSG-237). videos 는 인기순(viewCount → createdAt → id DESC) 한 페이지,
 * hasNext 는 lookahead 판정 결과, nextCursor 는 다음 페이지 opaque 커서(VideoCursor) — 마지막 페이지면 null.
 * 총계(total)는 담지 않는다 — Slice 의미(§D6, MSG-90/156 no-total 선례).
 */
@Schema(description = "격자 전역 영상 목록 페이지 응답 (keyset 커서 페이지네이션)")
public record GridVideoPageResponseDto(
	@Schema(description = "이 페이지의 전역 공개·READY 영상 (인기순). 없으면 빈 배열")
	List<GridGlobalVideoResponseDto> videos,

	@Schema(description = "다음 페이지 존재 여부 (lookahead 판정)")
	boolean hasNext,

	@Schema(description = "다음 페이지 조회용 opaque 커서. 다음 요청 cursor 파라미터에 넣는다. 마지막 페이지면 null.",
		example = "NDE2NDJfMTEwNDU4OjU6MTc4NDQ1NTgwMDAwMDAwMDoxMDM5", nullable = true)
	String nextCursor
) {
}
