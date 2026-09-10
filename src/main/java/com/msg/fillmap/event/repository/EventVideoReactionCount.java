package com.msg.fillmap.event.repository;

/**
 * 영상 하나의 반응 수 집계 한 행 (MSG-441). 댓글·도움돼요 배치 집계가 같은 형상이라 타입을 공유한다.
 * GROUP BY 결과라 count 는 항상 1 이상이고, 반응이 없는 영상은 행 자체가 없다 — 0 채움은 호출자 몫이다
 * (EventLocationVideoCount 와 같은 규칙).
 */
public record EventVideoReactionCount(Long videoId, Long count) {
}
