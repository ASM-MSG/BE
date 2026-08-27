package com.msg.fillmap.video.support;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 한 원본 인코딩 시도에서 만드는 파생 S3 키 묶음. */
public record VideoAssetKeys(String encoded, String blurred, String thumbnail) {

	public static VideoAssetKeys from(long userId, long videoId, String originalKey) {
		UUID attemptId = UUID.nameUUIDFromBytes(originalKey.getBytes(StandardCharsets.UTF_8));
		String prefix = "%d/%d/%s".formatted(userId, videoId, attemptId);
		return new VideoAssetKeys(
			"videos/encoded/%s.mp4".formatted(prefix),
			"videos/blurred/%s.mp4".formatted(prefix),
			"videos/thumb/%s.jpg".formatted(prefix));
	}
}
