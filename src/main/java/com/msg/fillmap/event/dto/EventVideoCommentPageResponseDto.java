package com.msg.fillmap.event.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 행사 영상 댓글 페이지 (MSG-441 API 6). 정렬은 오래된 순이라 새 댓글이 아래에 쌓인다 — 피드가 최신순인
 * 것과 방향이 다른 의도된 차이다(피드는 목록, 댓글은 대화 흐름). 영상 상세가 이 페이지의 첫 장을 이미
 * 품고 있으므로 목록 API 는 둘째 페이지부터를 위한 것이다.
 * {@link EventLocationVideoPageResponseDto} 와 같은 형상이다.
 */
@Schema(description = "행사 영상 댓글 페이지 (keyset 커서 페이지네이션)",
	requiredProperties = {"comments", "hasNext", "nextCursor"})
public record EventVideoCommentPageResponseDto(
	@Schema(description = "이 페이지의 댓글 (오래된 순). 댓글이 없으면 빈 배열")
	List<EventVideoCommentResponseDto> comments,

	@Schema(description = "다음 페이지 존재 여부 (lookahead 판정)")
	boolean hasNext,

	@Schema(description = "다음 페이지 조회용 opaque 커서. 다음 요청 cursor 파라미터에 그대로 넣는다. "
		+ "마지막 페이지면 null", nullable = true)
	String nextCursor
) {
}
