package com.msg.fillmap.video.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 전역 공개 영상 목록 페이지 응답 (MSG-237). videos 는 한 페이지, hasNext 는 lookahead 판정 결과,
 * nextCursor 는 다음 페이지 opaque 커서 — 마지막 페이지면 null. 정렬 축과 커서 성분 형식은 엔드포인트가
 * 정한다(격자 전역 목록은 인기순·VideoCursor, 미션 영상 목록은 촬영 시각순·MissionVideoCursor).
 * 총계(total)는 담지 않는다 — Slice 의미(§D6, MSG-90/156 no-total 선례).
 */
@Schema(description = "전역 공개 영상 목록 페이지 응답 (keyset 커서 페이지네이션)",
	requiredProperties = {"videos", "hasNext", "nextCursor"})
public record GridVideoPageResponseDto(
	@Schema(description = "이 페이지의 전역 공개·READY 영상. 없으면 빈 배열")
	List<GridGlobalVideoResponseDto> videos,

	@Schema(description = "다음 페이지 존재 여부 (lookahead 판정)")
	boolean hasNext,

	@Schema(description = "다음 페이지 조회용 opaque 커서. 다음 요청 cursor 파라미터에 넣는다. 마지막 페이지면 null.",
		nullable = true)
	String nextCursor
) {
}
