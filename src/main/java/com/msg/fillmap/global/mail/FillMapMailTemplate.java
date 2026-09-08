package com.msg.fillmap.global.mail;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.web.util.HtmlUtils;

/**
 * 필맵 전용 메일 서식 (MSG-575). 상단 워드마크, 제목, 본문, 강조 상자, 하단 안내를 한 장의 HTML 로 조립한다.
 * 메일 클라이언트 호환을 위해 인라인 CSS 와 테이블 레이아웃만 쓴다.
 *
 * <p>모든 인자는 <b>평문</b>으로 받아 여기서 이스케이프한다. 관리자가 자유롭게 적은 반려 사유처럼 사용자
 * 입력이 본문에 실리므로, 호출부가 마크업을 섞어 넣을 수 없게 하는 것이 계약이다. 줄바꿈만 {@code <br>} 로 살린다.
 *
 * <p>치환은 자리표시자를 한 번만 훑는 단일 패스다. 서식 안에 CSS 의 {@code %} 가 있어 {@code formatted} 를 쓸 수
 * 없고, {@code replace} 를 이어 붙이면 앞서 삽입된 값 안의 {@code {note}} 같은 토큰이 다시 치환된다 (Codex 리뷰).
 */
public final class FillMapMailTemplate {

	// ponytail: 브랜드 색 정본(디자인 토큰) 미확인 — 확정되면 이 상수 하나만 바꾼다
	static final String BRAND_COLOR = "#1F7A5C";
	static final String CONTACT = "contact@fillmap.kr";

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

	private static final String LAYOUT = """
		<!DOCTYPE html>
		<html lang="ko">
		<body style="margin:0;padding:0;background:#f6f7f9;">
		<table role="presentation" width="100%" cellpadding="0" cellspacing="0"
			style="background:#f6f7f9;padding:32px 16px;">
		<tr><td align="center">
		<table role="presentation" width="560" cellpadding="0" cellspacing="0"
			style="max-width:560px;width:100%;background:#ffffff;border-radius:12px;overflow:hidden;color:#1d1d1f;\
		font-family:-apple-system,BlinkMacSystemFont,'Apple SD Gothic Neo','Malgun Gothic',sans-serif;">
		<tr><td style="background:{brand};padding:20px 28px;color:#ffffff;font-size:20px;font-weight:700;">
			FillMap
		</td></tr>
		<tr><td style="padding:28px;">
		<h1 style="margin:0 0 16px;font-size:20px;line-height:1.4;">{title}</h1>
		<p style="margin:0 0 20px;font-size:15px;line-height:1.7;">{body}</p>
		<table role="presentation" width="100%" cellpadding="0" cellspacing="0"
			style="background:#f6f7f9;border-left:4px solid {brand};border-radius:6px;">
		<tr><td style="padding:14px 16px;">
		<div style="font-size:12px;color:#6b7280;margin-bottom:6px;">{noteLabel}</div>
		<div style="font-size:15px;line-height:1.7;">{note}</div>
		</td></tr>
		</table>
		<p style="margin:20px 0 0;font-size:15px;line-height:1.7;">{closing}<br>
			<a href="{linkUrl}" style="color:{brand};font-weight:600;">{linkLabel}</a></p>
		</td></tr>
		<tr><td style="padding:16px 28px;border-top:1px solid #ececec;font-size:12px;color:#6b7280;line-height:1.7;">
			이 메일은 필맵(FillMap)이 보냈습니다. 문의가 있으시면 이 메일에 회신해 주세요.<br>{contact}
		</td></tr>
		</table>
		</td></tr>
		</table>
		</body>
		</html>
		""";

	private FillMapMailTemplate() {
	}

	/**
	 * @param title 제목 한 줄
	 * @param body 본문 문단 (줄바꿈 허용)
	 * @param noteLabel 강조 상자 라벨 (예: "반려 사유")
	 * @param note 강조 상자 내용 (사용자 입력 허용, 줄바꿈 허용)
	 * @param closing 마무리 문장
	 * @param linkLabel 링크 문구
	 * @param linkUrl 링크 주소
	 */
	public static String html(String title, String body, String noteLabel, String note, String closing,
		String linkLabel, String linkUrl) {
		Map<String, String> values = Map.of(
			"brand", BRAND_COLOR,
			"contact", CONTACT,
			"title", escape(title),
			"body", escape(body),
			"noteLabel", escape(noteLabel),
			"note", escape(note),
			"closing", escape(closing),
			"linkLabel", escape(linkLabel),
			"linkUrl", HtmlUtils.htmlEscape(linkUrl));
		return PLACEHOLDER.matcher(LAYOUT)
			.replaceAll(match -> Matcher.quoteReplacement(values.get(match.group(1))));
	}

	private static String escape(String plain) {
		return HtmlUtils.htmlEscape(plain).replace("\r\n", "\n").replace("\n", "<br>");
	}
}
