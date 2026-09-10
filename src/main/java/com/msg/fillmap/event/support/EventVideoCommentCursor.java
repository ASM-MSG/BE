package com.msg.fillmap.event.support;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 댓글 목록 keyset 커서 (MSG-441 §API 6). 마지막으로 내려준 댓글의 경계값 (videoId, commentId)을
 * Base64URL("{videoId}:{commentId}") 로 감싼 opaque 토큰이다 — {@link EventVideoCursor} 의 형식과 규칙을
 * 물려받되 성분이 둘뿐이라 별도 타입이다. 시각 성분이 없는 것은 정렬 키가 {@code id ASC} 하나이기
 * 때문이다(BIGSERIAL 이 유일하고 단조 증가라 타이브레이커가 필요 없다).
 * <p>
 * videoId 성분은 <b>발급 영상 바인딩</b>이다 — 다른 영상의 목록 요청에 재사용된 커서는 경계값이 남의
 * keyset 으로 오적용돼 결과가 조용히 잘리므로, 서비스가 경로의 videoId 와 대조해 거부한다.
 * 디코드 실패(Base64 불량·필드 수/타입 불일치)는 IllegalArgumentException 으로 표면화한다 —
 * RuntimeException 이라 서비스가 한 번에 잡아 EventErrorCode.INVALID_CURSOR 로 변환한다.
 */
public record EventVideoCommentCursor(long videoId, long commentId) {

	public static String encode(long videoId, long commentId) {
		String raw = videoId + ":" + commentId;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	public static EventVideoCommentCursor decode(String token) {
		byte[] decoded = Base64.getUrlDecoder().decode(token);
		String raw = new String(decoded, StandardCharsets.UTF_8);
		String[] parts = raw.split(":", -1);
		if (parts.length != 2) {
			throw new IllegalArgumentException("커서 필드 수가 2가 아닙니다: " + raw);
		}
		try {
			return new EventVideoCommentCursor(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("커서 성분이 정수가 아닙니다: " + raw, e);
		}
	}
}
