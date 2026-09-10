package com.msg.fillmap.global.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/**
 * AWS SES 실발송 (MSG-497). 자격 증명은 S3 와 같은 DefaultCredentialsProvider 체인이라 새 시크릿이
 * 하나도 생기지 않는다(로컬 환경변수, dev·prod 는 EC2 인스턴스 role).
 *
 * <p>생성자가 재설정 링크 base URL 을 검증하는 것이 prod 안전장치의 나머지 절반이다 — 실발송이 켜진
 * 채 URL 이 비어 있으면 재설정 메일이 열 수 없는 링크를 싣고 나가므로, 그 상태로 뜨지 않게 기동을
 * 실패시킨다("enabled + 빈 필수 값 = 기동 실패", MSG-483 appKey 선례).
 */
@Component
@ConditionalOnProperty(name = "fillmap.mail.enabled", havingValue = "true")
public class SesMailSender implements MailSender {

	private static final String CHARSET = "UTF-8";

	private final SesV2Client sesClient;
	private final String from;

	public SesMailSender(SesV2Client sesClient, MailProperties properties) {
		if (!StringUtils.hasText(properties.resetLinkBaseUrl())) {
			throw new IllegalStateException(
				"fillmap.mail.enabled=true 인데 fillmap.mail.reset-link-base-url 이 비었습니다 (MSG-497)");
		}
		this.sesClient = sesClient;
		this.from = properties.from();
	}

	@Override
	public void send(String to, String subject, String text) {
		send(to, subject, text, null);
	}

	@Override
	public void send(String to, String subject, String text, String html) {
		Body.Builder body = Body.builder().text(content(text));
		if (html != null) {
			body.html(content(html));
		}
		sesClient.sendEmail(SendEmailRequest.builder()
			.fromEmailAddress(from)
			.destination(Destination.builder().toAddresses(to).build())
			.content(EmailContent.builder()
				.simple(Message.builder()
					.subject(content(subject))
					.body(body.build())
					.build())
				.build())
			.build());
	}

	private Content content(String data) {
		return Content.builder().data(data).charset(CHARSET).build();
	}
}
