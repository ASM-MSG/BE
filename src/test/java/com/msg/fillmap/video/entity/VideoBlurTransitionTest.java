package com.msg.fillmap.video.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.msg.fillmap.video.support.GeoSupport;

/**
 * MSG-149/150: AI 활성 흐름의 BLURRING 상태 전이(markEncoded·markReadyFromBlurring)를 엔티티 단위로 검증한다.
 * markReady 는 손대지 않으므로(VideoStatusTransitionTest 소관) 여기서는 새 전이만 다룬다.
 */
@DisplayName("Video AI 블러 상태 전이")
class VideoBlurTransitionTest {

	private Video newVideo() {
		return Video.create(1L, "41716_110483", "videos/original/1/x.mp4",
			GeoSupport.toPoint(37.5445, 127.0560), (short) 10, LocalDateTime.now());
	}

	@Test
	void BLURRING_동안_thumbnailUrl은_null이다() {
		Video video = newVideo();
		video.markEncoding();

		video.markEncoded("videos/encoded/1/7.mp4");

		assertThat(video.getProcessingStatus()).isEqualTo(ProcessingStatus.BLURRING);
		assertThat(video.getEncodedUrl()).isEqualTo("videos/encoded/1/7.mp4");
		// 미블러 썸네일을 안 올렸으므로 키도 null — my-videos 가 null 을 반환해 FE 가 플레이스홀더 처리(R5 불변식).
		assertThat(video.getThumbnailUrl()).isNull();
	}

	@Test
	void markReadyFromBlurring은_thumbnailUrl을_채우며_BLURRING에서_READY로_바뀐다() {
		Video video = newVideo();
		video.markEncoding();
		video.markEncoded("videos/encoded/1/7.mp4");

		video.markReadyFromBlurring("videos/thumb/1/7.jpg");

		assertThat(video.getProcessingStatus()).isEqualTo(ProcessingStatus.READY);
		assertThat(video.getEncodedUrl()).isEqualTo("videos/encoded/1/7.mp4");
		assertThat(video.getThumbnailUrl()).isEqualTo("videos/thumb/1/7.jpg");   // 업로드 완료 후에만 키 기록
	}

	@Test
	void recordAiJob은_job_id를_기록한다() {
		Video video = newVideo();
		video.markEncoding();
		video.markEncoded("videos/encoded/1/7.mp4");

		video.recordAiJob("job-42");

		assertThat(video.getAiJobId()).isEqualTo("job-42");
	}
}
