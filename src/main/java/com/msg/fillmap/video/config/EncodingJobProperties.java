package com.msg.fillmap.video.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "fillmap.video.encoding-job")
public record EncodingJobProperties(
	@DefaultValue("true") boolean enabled,
	@DefaultValue("PT1S") Duration pollInterval,
	@DefaultValue("PT5S") Duration retryDelay,
	@DefaultValue("PT35M") Duration leaseDuration
) {
}
