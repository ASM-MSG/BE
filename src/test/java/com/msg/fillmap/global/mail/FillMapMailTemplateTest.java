package com.msg.fillmap.global.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 필맵 메일 서식 (MSG-575). 검증 대상은 서식의 모양이 아니라 <b>사용자 입력의 이스케이프</b> 하나다 — 관리자가
 * 적은 반려 사유가 마크업으로 해석되면 본문이 깨지거나 링크가 주입된다.
 */
@DisplayName("필맵 메일 서식 (MSG-575)")
class FillMapMailTemplateTest {

	// 검증: FR-AUTH-18, AC-575-03, AC-575-07
	@Test
	@DisplayName("삽입 문자열은 이스케이프되고 줄바꿈은 br 로 바뀐다")
	void 삽입_문자열은_이스케이프되고_줄바꿈은_br로_바뀐다() {
		String html = FillMapMailTemplate.html("제목 <b>", "본문", "사유", "1행 <script>x</script>\n2행 & 끝",
			"마무리", "링크", "https://fillmap.kr/?a=1&b=2");

		assertThat(html)
			.contains("제목 &lt;b&gt;")
			.contains("1행 &lt;script&gt;x&lt;/script&gt;<br>2행 &amp; 끝")
			.contains("href=\"https://fillmap.kr/?a=1&amp;b=2\"")
			.contains("FillMap", FillMapMailTemplate.CONTACT, FillMapMailTemplate.BRAND_COLOR)
			.doesNotContain("<script>", "{title}", "{note}");
	}

	// 검증: FR-AUTH-18, AC-575-03
	@Test
	@DisplayName("삽입된 값에 자리표시자 모양이 있어도 다시 치환되지 않는다")
	void 삽입된_값에_자리표시자_모양이_있어도_다시_치환되지_않는다() {
		String html = FillMapMailTemplate.html("제목", "행사명 {note} 안내 $1", "사유", "사유 {closing}",
			"마무리", "링크", "https://fillmap.kr");

		assertThat(html)
			.contains("행사명 {note} 안내 $1")
			.contains("사유 {closing}")
			.containsOnlyOnce("마무리");
	}
}
