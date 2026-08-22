package com.msg.fillmap.event.service;

import java.util.Map;

/**
 * 피드 한 페이지의 반응 수 (MSG-441 §API 8). group by 배치 두 번의 결과를 영상 id 로 찾을 수 있게 묶은
 * event 도메인 내부 타입이다. 반응이 하나도 없는 영상은 맵에 키가 없으므로 조회가 0 을 돌려준다 —
 * 카드에 0 을 채우는 규칙이 호출자마다 갈리지 않게 여기서 한 번만 정한다.
 */
public record EventVideoReactionCounts(Map<Long, Long> commentCounts, Map<Long, Long> helpfulCounts) {

	public long commentCount(Long videoId) {
		return commentCounts.getOrDefault(videoId, 0L);
	}

	public long helpfulCount(Long videoId) {
		return helpfulCounts.getOrDefault(videoId, 0L);
	}
}
