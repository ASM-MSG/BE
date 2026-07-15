package com.msg.fillmap.video.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.msg.fillmap.video.support.GeoSupport;

@DisplayName("Video 인코딩 상태 전이")
class VideoStatusTransitionTest {

	private Video newVideo() {
		return Video.create(1L, "41716_110483", "videos/original/1/x.mp4",
			GeoSupport.toPoint(37.5445, 127.0560), (short) 10, LocalDateTime.now());
	}

	@Test
	void 생성_직후는_UPLOADED_다() {
		assertThat(newVideo().getProcessingStatus()).isEqualTo(ProcessingStatus.UPLOADED);
	}

	@Test
	void markEncoding_이면_ENCODING_으로_바뀐다() {
		Video video = newVideo();

		video.markEncoding();

		assertThat(video.getProcessingStatus()).isEqualTo(ProcessingStatus.ENCODING);
	}

	@Test
	void markReady_는_READY_와_함께_encoded_thumbnail_키를_채운다() {
		Video video = newVideo();
		video.markEncoding();

		video.markReady("videos/encoded/1/7.mp4", "videos/thumb/1/7.jpg");

		assertThat(video.getProcessingStatus()).isEqualTo(ProcessingStatus.READY);
		assertThat(video.getEncodedUrl()).isEqualTo("videos/encoded/1/7.mp4");
		assertThat(video.getThumbnailUrl()).isEqualTo("videos/thumb/1/7.jpg");
	}

	@Test
	void markFailed_이면_FAILED_이고_결과물_키는_비어있다() {
		Video video = newVideo();
		video.markEncoding();

		video.markFailed();

		assertThat(video.getProcessingStatus()).isEqualTo(ProcessingStatus.FAILED);
		assertThat(video.getEncodedUrl()).isNull();
		assertThat(video.getThumbnailUrl()).isNull();
	}
}
