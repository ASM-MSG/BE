package com.msg.fillmap.global.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "aws")
public record AwsProperties(
	@NotBlank String region,
	@Valid @NotNull S3 s3
) {

	/**
	 * bucket 에 S3 명명 규칙을 강제한다. @ConfigurationProperties 바인딩은 @Value 와 달리 해석하지 못한
	 * 플레이스홀더를 예외 없이 리터럴로 통과시키므로(PropertySourcesPlaceholdersResolver), 환경변수가
	 * 빠지면 버킷명이 "${S3_BUCKET_VIDEO}" 가 된 채 기동해버린다. 이 패턴이 그 경우를 기동 시점에 잡는다.
	 */
	public record S3(
		@NotBlank
		@Pattern(regexp = "^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", message = "유효한 S3 버킷 이름이 아닙니다")
		String bucket,
		@Positive long maxUploadBytes
	) {
	}
}
