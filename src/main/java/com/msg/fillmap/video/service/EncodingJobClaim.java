package com.msg.fillmap.video.service;

import java.time.LocalDateTime;
import java.util.UUID;

public record EncodingJobClaim(
	Long jobId,
	Long videoId,
	String originalS3Key,
	UUID claimToken,
	short attemptCount,
	LocalDateTime enqueuedAt
) {
}
