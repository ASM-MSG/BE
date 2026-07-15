package com.msg.fillmap.global.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "aws")
public record AwsProperties(
	@NotBlank String region,
	@Valid @NotNull S3 s3
) {

	public record S3(
		@NotBlank String bucket,
		@Positive long maxUploadBytes
	) {
	}
}
