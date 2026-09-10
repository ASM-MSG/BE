package com.msg.fillmap.event.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 댓글 목록 keyset 커서 (MSG-441 §API 6). 성분은 (videoId, commentId) 2개고 와이어 포맷은
 * Base64URL("{videoId}:{commentId}") 다. 여기서는 라운드트립 대칭과 형식 위반 예외만 본다 —
 * 발급 영상 바인딩 거부(13402)는 서비스 몫이라 댓글 목록 테스트가 본다.
 */
@DisplayName("EventVideoCommentCursor 인코드·디코드")
class EventVideoCommentCursorTest {

	private static final long VIDEO_ID = 1042L;
	private static final long COMMENT_ID = 3021L;

	private static String base64Url(String raw) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("인코딩한 커서를 디코드하면 원래 성분이 그대로다")
	void 인코딩한_커서를_디코드하면_원래_성분이_그대로다() {
		String token = EventVideoCommentCursor.encode(VIDEO_ID, COMMENT_ID);

		assertThat(EventVideoCommentCursor.decode(token))
			.isEqualTo(new EventVideoCommentCursor(VIDEO_ID, COMMENT_ID));
	}

	@Test
	@DisplayName("깨진 Base64 토큰은 예외다")
	void 깨진_Base64_토큰은_예외다() {
		assertThatThrownBy(() -> EventVideoCommentCursor.decode("!!!not-base64!!!"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("성분 수가 2가 아닌 토큰은 예외다")
	void 성분_수가_2가_아닌_토큰은_예외다() {
		assertThatThrownBy(() -> EventVideoCommentCursor.decode(base64Url("1:2:3")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> EventVideoCommentCursor.decode(base64Url("1042")))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("정수가 아닌 성분은 예외다")
	void 정수가_아닌_성분은_예외다() {
		assertThatThrownBy(() -> EventVideoCommentCursor.decode(base64Url("a:b")))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
