package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.video.config.AiProperties;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.FfmpegRunner;
import com.msg.fillmap.video.support.GeoSupport;
import com.msg.fillmap.video.support.VideoAssetKeys;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * 실제 ffmpeg 로 만든 영상을 실제 ffprobe 로 재서 길이 계산이 맞는지 본다 (MSG-470).
 *
 * {@link VideoEncodingServiceTest} 는 {@code probeDurationSec} 을 목으로 스텁하므로 "우리가 정한 값이
 * 그대로 흘러가는지"만 검증한다. 신고값을 버리고 실측을 쓰기로 한 이 티켓에서 정작 확인이 필요한 것은
 * <b>진짜 파일의 진짜 길이가 맞게 계산되는가</b>인데, 스텁으로는 그 질문에 답할 수 없다. ffprobe 출력
 * 형식, 파싱, 반올림, 클램프, 초과 판정 순서가 한 줄로 이어지는 것은 여기서만 검증된다.
 *
 * <p><b>이 테스트가 확인하는 것은 "전이에 실리는 값"까지다.</b> 라이터를 목으로 두므로 실제로
 * {@code videos.duration_sec} 행이 갱신되는지는 보지 않는다. 그 구간은 {@code VideoStatusWriterTest}
 * (가드·전이 적용)와 {@code VideoStatusTransitionTest}(엔티티 필드 갱신)가 덮는다. 여기서 목을 쓰는
 * 이유는 DB 왕복 없이 실 ffmpeg 왕복만 떼어 보기 위해서다.
 *
 * <p>ffmpeg 가 없으면 통째로 skip 된다 ({@link com.msg.fillmap.video.support.FfmpegRunnerTest} 와 같은
 * 방식). CI 는 ffmpeg 를 설치하므로 실제로 돈다 — 설치를 빼면 이 클래스가 조용히 건너뛰어지면서
 * {@code docs/rtm.md} 만 검증이 있는 것처럼 남는다.
 */
@DisplayName("영상 길이 계산 (실 ffmpeg)")
class VideoEncodingDurationRealFfmpegTest {

	private static final long VIDEO_ID = 7L;
	private static final String ORIGINAL_KEY = "videos/original/1/x.mp4";
	private static final VideoAssetKeys ASSET_KEYS = VideoAssetKeys.from(1L, VIDEO_ID, ORIGINAL_KEY);
	private static final String ENCODED_KEY = ASSET_KEYS.encoded();
	private static final String THUMBNAIL_KEY = ASSET_KEYS.thumbnail();
	/** 신고값. 실측과 일부러 다르게 둬서 저장값이 어느 쪽에서 왔는지 구분된다. */
	private static final short 신고값 = 30;

	private static boolean ffmpegAvailable;

	private VideoRepository videoRepository;
	private VideoStatusWriter statusWriter;
	private S3Client s3Client;
	private VideoEncodingService encodingService;
	private Video video;
	private EncodingJobClaim claim;

	@BeforeAll
	static void checkFfmpeg() {
		ffmpegAvailable = which("ffmpeg") && which("ffprobe");
	}

	private static boolean which(String binary) {
		try {
			return new ProcessBuilder("which", binary).start().waitFor() == 0;
		} catch (IOException e) {
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		videoRepository = mock(VideoRepository.class);
		statusWriter = mock(VideoStatusWriter.class);
		s3Client = mock(S3Client.class);

		AwsProperties properties = new AwsProperties(
			"ap-northeast-2", new AwsProperties.S3("fillmap-video-dev", 104857600L, 2147483648L));
		encodingService = new VideoEncodingServiceImpl(
			videoRepository, statusWriter, new FfmpegRunner(), s3Client, properties,
			new VideoProcessingMetrics(new SimpleMeterRegistry()),
			new AiProperties(false, false, "http://ai.test", Duration.ofMinutes(30), 30000L),
			mock(ObjectProvider.class), mock(ThreadPoolTaskExecutor.class));

		video = Video.create(1L, "19495_9607", ORIGINAL_KEY,
			GeoSupport.toPoint(37.5445, 127.0560), 신고값, LocalDateTime.now(java.time.ZoneOffset.UTC),
			Visibility.PRIVATE);
		claim = new EncodingJobClaim(1L, VIDEO_ID, ORIGINAL_KEY, UUID.randomUUID(),
			(short) 1, LocalDateTime.of(2026, 8, 27, 0, 0));
		given(videoRepository.findById(VIDEO_ID)).willReturn(Optional.of(video));
		given(statusWriter.markEncoding(claim)).willAnswer(invocation -> {
			video.markEncoding();
			return true;
		});
	}

	// 검증: FR-MEDIA-19
	@Test
	void 십이점칠초_영상은_십삼초가_전이에_실린다(@TempDir Path dir) throws Exception {
		assumeTrue(ffmpegAvailable, "ffmpeg 없음 — skip");

		encodeReal(dir, "12.7");

		verify(statusWriter).markReady(claim, ENCODED_KEY, THUMBNAIL_KEY, (short) 13);
	}

	// 검증: FR-MEDIA-19
	@Test
	void 영점삼초_영상은_하한에_걸려_일초가_전이에_실린다(@TempDir Path dir) throws Exception {
		assumeTrue(ffmpegAvailable, "ffmpeg 없음 — skip");

		encodeReal(dir, "0.3");

		// 반올림하면 0 이라 CHECK(duration_sec > 0) 위반이다. 클램프가 없으면 전이가 깨진다.
		verify(statusWriter).markReady(claim, ENCODED_KEY, THUMBNAIL_KEY, (short) 1);
	}

	// 검증: FR-MEDIA-19
	@Test
	void 삼십점육초_영상은_상한에_걸려_삼십초가_전이에_실린다(@TempDir Path dir) throws Exception {
		assumeTrue(ffmpegAvailable, "ffmpeg 없음 — skip");

		encodeReal(dir, "30.6");

		// MSG-370 이 허용한 여유 구간이라 실패가 아니다. 반올림하면 31 이지만 CHECK 상한이 30 이다.
		verify(statusWriter).markReady(claim, ENCODED_KEY, THUMBNAIL_KEY, (short) 30);
		verify(statusWriter, never()).markFailed(claim);
	}

	// 검증: FR-MEDIA-03
	@Test
	void 삼십일점사초_영상은_클램프되지_않고_실패한다(@TempDir Path dir) throws Exception {
		assumeTrue(ffmpegAvailable, "ffmpeg 없음 — skip");

		encodeReal(dir, "31.4");

		// 초과 판정이 클램프보다 앞이라는 순서가 계약이다. 뒤집히면 30 으로 뭉개져 통과한다.
		verify(statusWriter).markFailed(claim);
		verify(statusWriter, never()).markReady(eq(claim), any(), any(), anyShort());
	}

	/** 신고값과 무관하게 실측이 이긴다는 것이 이 티켓의 요지다. */
	// 검증: FR-MEDIA-19
	@Test
	void 신고값이_틀려도_실측이_전이에_실린다(@TempDir Path dir) throws Exception {
		assumeTrue(ffmpegAvailable, "ffmpeg 없음 — skip");
		assertThat(video.getDurationSec()).isEqualTo(신고값);   // 확정 시점엔 신고값 30 이 들어 있다

		encodeReal(dir, "5.0");

		verify(statusWriter).markReady(claim, ENCODED_KEY, THUMBNAIL_KEY, (short) 5);
	}

	/**
	 * 실제 영상을 만들어 S3 다운로드 자리에 놓고 인코딩 경로를 그대로 태운다. 워커가 임시 디렉터리에
	 * 받아 가므로 목 S3 는 요청받은 경로로 파일을 복사해 준다.
	 */
	private void encodeReal(Path dir, String durationSec) throws Exception {
		Path source = synthesize(dir, durationSec);
		given(s3Client.getObject(any(GetObjectRequest.class), any(Path.class)))
			.willAnswer(invocation -> {
				Path target = invocation.getArgument(1);
				Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				return null;
			});
		given(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).willReturn(null);

		encodingService.encode(claim);
	}

	/** lavfi 합성 영상. 바이너리 픽스처를 리포에 넣지 않으려고 그때그때 만든다 (FfmpegRunnerTest 와 같은 방식). */
	private Path synthesize(Path dir, String durationSec) throws Exception {
		Path out = dir.resolve("src-" + durationSec + ".mp4");
		Process p = new ProcessBuilder(List.of(
			"ffmpeg", "-y",
			"-f", "lavfi", "-i", "testsrc=size=640x360:rate=30:duration=" + durationSec,
			"-f", "lavfi", "-i", "sine=frequency=1000:duration=" + durationSec,
			"-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest",
			out.toString()))
			.redirectErrorStream(true)
			.redirectOutput(dir.resolve("gen-" + durationSec + ".log").toFile())
			.start();
		assertThat(p.waitFor()).isZero();
		return out;
	}
}
