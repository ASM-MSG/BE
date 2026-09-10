package com.msg.fillmap.video.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cloudfront")
public record CloudFrontProperties(
	boolean enabled,
	String domain,
	String keyPairId,
	Path privateKeyPath
) {
}
