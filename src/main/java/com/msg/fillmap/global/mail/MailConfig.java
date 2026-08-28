package com.msg.fillmap.global.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

import com.msg.fillmap.global.config.AwsProperties;

/**
 * SES 클라이언트 등록 (MSG-497, S3Config 패턴). 자격증명은 DefaultCredentialsProvider 체인에 맡기고
 * region 은 기존 {@code aws.region} 을 재사용한다 — 새 시크릿도 새 설정 축도 없다.
 * 실발송이 꺼진 환경(로컬·dev 기본)에서는 클라이언트 자체를 만들지 않는다.
 */
@Configuration
@ConditionalOnProperty(name = "fillmap.mail.enabled", havingValue = "true")
public class MailConfig {

	@Bean
	public SesV2Client sesV2Client(AwsProperties properties) {
		return SesV2Client.builder()
			.region(Region.of(properties.region()))
			.build();
	}
}
