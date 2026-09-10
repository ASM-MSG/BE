package com.msg.fillmap.global.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 로컬·dev 검증용 로그 발송 (MSG-497). 재설정 링크가 토큰까지 로그에 전문으로 찍혀 실발송 없이
 * reset 까지 수동 E2E 가 된다. <b>토큰 원문 로그 금지의 유일한 예외</b>이며, prod 에서는
 * {@code @Profile("!prod")} 때문에 이 빈이 뜰 수 없다.
 *
 * <p>이 프로파일 조건이 prod 안전장치의 절반이다: prod 에서 {@code fillmap.mail.enabled} 가 누락되거나
 * false 면 MailSender 빈이 하나도 없어 주입이 실패하고 기동 자체가 죽는다(코드 추가 없이 DI 가 집행).
 * {@code matchIfMissing} 만으로는 운영 프로퍼티 누락 시 링크 전문이 로그로 새는 fail-open 이었다.
 */
@Slf4j
@Component
@Profile("!prod")
@ConditionalOnProperty(name = "fillmap.mail.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingMailSender implements MailSender {

	@Override
	public void send(String to, String subject, String text) {
		log.info("[mail] 실발송 없이 로그로 대체합니다 — to={}, subject={}\n{}", to, subject, text);
	}

	/** HTML 은 길이만 남긴다. 평문 대체본이 같은 내용이라 검증에는 그것으로 충분하고, 마크업은 로그를 읽기 어렵게만 한다. */
	@Override
	public void send(String to, String subject, String text, String html) {
		log.info("[mail] 실발송 없이 로그로 대체합니다 — to={}, subject={}, html={}자\n{}", to, subject, html.length(), text);
	}
}
