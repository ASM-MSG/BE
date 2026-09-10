package com.msg.fillmap.global.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 메일 발송 구현 선택과 prod 안전장치 (MSG-497). 검증 대상이 빈 구성 자체라 실제 컨텍스트가 아니라
 * {@link ApplicationContextRunner} 로 조건만 돌린다 — SES 클라이언트가 필요 없다.
 *
 * <p>안전장치는 둘이다. prod 에서 실발송이 꺼져 있으면 MailSender 빈이 하나도 없어 주입이 실패하고
 * (링크 전문이 로그로 새는 fail-open 차단), 실발송이 켜졌는데 링크 URL 이 비면 생성자가 기동을 막는다.
 */
@DisplayName("메일 발송 구성 (MSG-497)")
class MailSenderConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(LoggingMailSender.class, MailSenderConsumer.class);

	/** MailSender 를 생성자로 주입받는 서비스의 대역 — 빈이 없으면 이 빈이 컨텍스트를 실패시킨다. */
	static class MailSenderConsumer {

		MailSenderConsumer(MailSender mailSender) {
		}
	}

	@Nested
	@DisplayName("prod 안전장치")
	class ProdFailFast {

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("prod 에서 발송 프로퍼티가 없으면 컨텍스트가 뜨지 않는다 — DI 가 fail-fast 를 집행한다")
		void prod_프로파일에서_프로퍼티가_없으면_컨텍스트가_뜨지_않는다() {
			runner.withPropertyValues("spring.profiles.active=prod")
				.run(context -> assertThat(context).hasFailed());
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("prod 에서는 로깅 발송 구현이 빈으로 뜨지 않는다 — 토큰 원문 로그 차단")
		void prod_프로파일에서_로깅_발송_구현은_빈으로_뜨지_않는다() {
			new ApplicationContextRunner()
				.withUserConfiguration(LoggingMailSender.class)
				.withPropertyValues("spring.profiles.active=prod", "fillmap.mail.enabled=false")
				.run(context -> assertThat(context).doesNotHaveBean(LoggingMailSender.class));
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("발송이 활성인데 링크 URL 이 비면 기동이 실패한다")
		void 발송_활성인데_링크_URL이_비면_기동이_실패한다() {
			MailProperties blankUrl = new MailProperties(true, "contact@fillmap.kr", "  ");

			assertThatThrownBy(() -> new SesMailSender(null, blankUrl))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("reset-link-base-url");
		}
	}

	@Nested
	@DisplayName("로컬·dev 기본 구성")
	class LocalDefault {

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("prod 밖에서 발송 프로퍼티가 없으면 로그 발송 구현이 뜬다 — 실발송 없는 검증 경로")
		void 발송_프로퍼티가_없으면_로그_발송_구현이_뜬다() {
			runner.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(MailSender.class);
				assertThat(context).hasSingleBean(LoggingMailSender.class);
			});
		}

		// 검증: FR-AUTH-16
		@Test
		@DisplayName("실발송을 켜면 로그 발송 구현은 뜨지 않는다 — 구현이 겹치지 않는다")
		void 실발송을_켜면_로그_발송_구현은_뜨지_않는다() {
			new ApplicationContextRunner()
				.withUserConfiguration(LoggingMailSender.class)
				.withPropertyValues("fillmap.mail.enabled=true")
				.run(context -> assertThat(context).doesNotHaveBean(LoggingMailSender.class));
		}
	}
}
