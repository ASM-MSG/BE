package com.msg.fillmap.video.dto;

public record PresignedUrlResponseDto(
	String uploadUrl,
	String s3Key,
	long expiresInSec
) {
}
