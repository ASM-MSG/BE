package com.msg.fillmap.global.mail;

/**
 * 메일 발송 유틸 (MSG-497). 구현이 둘이다 — SES 실발송({@link SesMailSender})과 로컬 검증용 로그 발송
 * ({@link LoggingMailSender}). 초기 비밀번호 발송(MSG-499)이 이 {@code send} 하나를 그대로 재사용한다.
 *
 * <p><b>실패는 예외로 올린다.</b> 삼키는 것은 호출부 책임이다 — 재설정 요청은 계정 존재 은닉 때문에
 * 실패를 감춰야 하지만, 관리자 발급 흐름(MSG-499)은 실패를 관리자에게 보여줘야 한다.
 */
public interface MailSender {

	void send(String to, String subject, String text);

	/**
	 * HTML 본문을 함께 싣는 발송 (MSG-575). {@code text} 는 HTML 을 못 그리는 클라이언트용 대체본이라 두 본문이
	 * 같은 내용이어야 한다. 서식은 {@link FillMapMailTemplate} 이 만든다.
	 */
	void send(String to, String subject, String text, String html);
}
