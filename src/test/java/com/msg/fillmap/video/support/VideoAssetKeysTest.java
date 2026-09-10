package com.msg.fillmap.video.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("인코딩 시도별 미디어 키")
class VideoAssetKeysTest {

	@Test
	@DisplayName("같은 원본 시도는 같은 파생 키를 만든다")
	void 같은_originalKey는_같은_파생키를_만든다() {
		VideoAssetKeys first = VideoAssetKeys.from(7L, 42L, "videos/original/7/attempt-a.mp4");
		VideoAssetKeys second = VideoAssetKeys.from(7L, 42L, "videos/original/7/attempt-a.mp4");

		assertThat(second).isEqualTo(first);
		assertThat(first.encoded()).startsWith("videos/encoded/7/42/").endsWith(".mp4");
		assertThat(first.blurred()).startsWith("videos/blurred/7/42/").endsWith(".mp4");
		assertThat(first.thumbnail()).startsWith("videos/thumb/7/42/").endsWith(".jpg");
	}

	@Test
	@DisplayName("원본 시도가 바뀌면 캐시 경로도 바뀐다")
	void 다른_originalKey는_다른_파생키를_만든다() {
		VideoAssetKeys first = VideoAssetKeys.from(7L, 42L, "videos/original/7/attempt-a.mp4");
		VideoAssetKeys second = VideoAssetKeys.from(7L, 42L, "videos/original/7/attempt-b.mp4");

		assertThat(second.encoded()).isNotEqualTo(first.encoded());
		assertThat(second.blurred()).isNotEqualTo(first.blurred());
		assertThat(second.thumbnail()).isNotEqualTo(first.thumbnail());
	}
}
