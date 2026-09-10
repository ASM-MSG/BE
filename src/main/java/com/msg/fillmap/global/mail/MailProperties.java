package com.msg.fillmap.global.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 메일 발송 설정 (MSG-497). {@code enabled} 가 발송 구현을 가른다 — true 면 SES 실발송,
 * 기본(false)이면 로그 발송이라 로컬·dev 는 실발송 없이 재설정 흐름을 끝까지 검증할 수 있다.
 *
 * <p>{@code resetLinkBaseUrl} 의 기본값을 공통 application.yml 에 두지 않는다 — 두면 prod 가 상속받아
 * 실발송이 켜진 채 URL 미주입이면 localhost 링크가 실제 메일로 나간다. 로컬·dev 프로파일 문서에만 둔다.
 */
@ConfigurationProperties(prefix = "fillmap.mail")
public record MailProperties(
	boolean enabled,
	String from,
	String resetLinkBaseUrl
) {
}
