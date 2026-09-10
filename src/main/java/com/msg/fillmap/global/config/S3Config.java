package com.msg.fillmap.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
public class S3Config {

	/**
	 * 자격증명은 기본값인 DefaultCredentialsProvider 체인에 맡긴다 —
	 * local 은 환경변수/프로파일, dev·prod 는 EC2 인스턴스 role(IMDS)에서 해석된다.
	 * lazy 해석이라 자격증명이 없는 CI 에서도 컨텍스트 로드는 성공한다.
	 */
	@Bean
	public S3Presigner s3Presigner(AwsProperties properties) {
		return S3Presigner.builder()
			.region(Region.of(properties.region()))
			.build();
	}

	/**
	 * 인코딩 워커(MSG-65)가 원본을 내려받고 결과를 올리는 데 쓴다.
	 * presign 과 달리 실제 바이트를 주고받으므로 S3Client 가 따로 필요하다.
	 */
	@Bean
	public S3Client s3Client(AwsProperties properties) {
		return S3Client.builder()
			.region(Region.of(properties.region()))
			.build();
	}
}
