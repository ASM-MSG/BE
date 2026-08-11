package com.msg.fillmap.video.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 실제 ffmpeg 로 산출물을 검증한다. ffmpeg 가 없는 환경(CI)에서는 통째로 skip 된다 —
 * 목으로는 "정말 720p 로 나오는지"를 확인할 수 없어서 이 테스트가 따로 존재한다.
 */
@DisplayName("FfmpegRunner (실 ffmpeg)")
class FfmpegRunnerTest {

	private static boolean ffmpegAvailable;

	private final FfmpegRunner runner = new FfmpegRunner();

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

	/** lavfi 로 합성한 1080p 테스트 영상. 바이너리 픽스처를 리포에 넣지 않으려고 그때그때 만든다. */
	private Path sample1080p(Path dir, int durationSec) throws Exception {
		Path out = dir.resolve("src.mp4");
		Process p = new ProcessBuilder(List.of(
			"ffmpeg", "-y",
			"-f", "lavfi", "-i", "testsrc=size=1920x1080:rate=30:duration=" + durationSec,
			"-f", "lavfi", "-i", "sine=frequency=1000:duration=" + durationSec,
			"-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest",
			out.toString()))
			.redirectErrorStream(true)
			.redirectOutput(dir.resolve("gen.log").toFile())
			.start();
		assertThat(p.waitFor()).isZero();
		return out;
	}

	private String probe(Path file, String entries) throws Exception {
		Process p = new ProcessBuilder(List.of(
			"ffprobe", "-v", "error", "-select_streams", "v:0",
			"-show_entries", entries, "-of", "csv=p=0", file.toString()))
			.start();
		String out = new String(p.getInputStream().readAllBytes()).trim();
		p.waitFor();
		return out;
	}

	@Test
	void probeDurationSec_는_실제_길이를_읽는다(@TempDir Path dir) throws Exception {
		assumeTrue(ffmpegAvailable, "ffmpeg 없음 — skip");

		double duration = runner.probeDurationSec(sample1080p(dir, 5));

		assertThat(duration).isCloseTo(5.0, org.assertj.core.data.Offset.offset(0.2));
	}

	// 검증: FR-MEDIA-01
	@Test
	void encode720p_는_1080p를_1280x720_h264로_변환한다(@TempDir Path dir) throws Exception {
		assumeTrue(ffmpegAvailable, "ffmpeg 없음 — skip");
		Path out = dir.resolve("out.mp4");

		runner.encode720p(sample1080p(dir, 3), out);

		assertThat(Files.size(out)).isPositive();
		assertThat(probe(out, "stream=width,height,codec_name")).isEqualTo("h264,1280,720");
	}

	// 검증: FR-MEDIA-01
	@Test
	void extractThumbnail_은_jpg_한_장을_만든다(@TempDir Path dir) throws Exception {
		assumeTrue(ffmpegAvailable, "ffmpeg 없음 — skip");
		Path thumb = dir.resolve("t.jpg");

		runner.extractThumbnail(sample1080p(dir, 3), thumb, 3.0);

		assertThat(Files.size(thumb)).isPositive();
		assertThat(probe(thumb, "stream=codec_name")).isEqualTo("mjpeg");
	}

	// 검증: FR-MEDIA-01
	@Test
	void 짧은_영상도_썸네일이_나온다(@TempDir Path dir) throws Exception {
		assumeTrue(ffmpegAvailable, "ffmpeg 없음 — skip");
		Path thumb = dir.resolve("t.jpg");

		// seek 1초를 그대로 쓰면 짧은 영상에서 프레임을 못 잡아 빈 파일이 된다 — 첫 프레임 폴백 확인.
		runner.extractThumbnail(sample1080p(dir, 1), thumb, 0.5);

		assertThat(Files.size(thumb)).isPositive();
	}

	@Test
	void 손상된_파일이면_파일_불량_예외를_던진다(@TempDir Path dir) throws Exception {
		assumeTrue(ffmpegAvailable, "ffmpeg 없음 — skip");
		Path broken = dir.resolve("broken.mp4");
		Files.write(broken, new byte[2048]);

		// 인프라 실패(타임아웃·바이너리 부재)와 구분되는 파일 불량 타입 — 선분석 3426 분류 근거 (MSG-351 P2-2)
		assertThatThrownBy(() -> runner.probeDurationSec(broken))
			.isInstanceOf(FfmpegRunner.InvalidMediaException.class);
	}

	/**
	 * 출력을 닫지 않고 오래 버티는 프로세스(=행)에서 타임아웃이 실제로 걸리는지 본다.
	 * 스트림을 waitFor 보다 먼저 읽던 구현에서는 여기서 타임아웃이 무시돼 인코딩 풀(1개)이 영구 정지했다.
	 * ffmpeg 와 무관한 회귀 가드라 ffmpeg 없이도 돈다.
	 */
	@Test
	void 프로세스가_행이면_타임아웃으로_끊는다() {
		FfmpegRunner shortTimeout = new FfmpegRunner(Duration.ofMillis(300));
		long started = System.currentTimeMillis();

		assertThatThrownBy(() -> shortTimeout.runForTest(List.of("sleep", "30")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("타임아웃");

		assertThat(System.currentTimeMillis() - started)
			.as("타임아웃 300ms 안에 끊겨야 한다 (행 프로세스를 30초 기다리면 안 됨)")
			.isLessThan(5_000);
	}

	/** 선분석 경로가 쓰는 호출별 타임아웃(P1-2)이 인스턴스 기본값(10분)을 이기는지 본다. ffmpeg 없이도 돈다. */
	@Test
	void 호출별_타임아웃이_기본값보다_우선한다() {
		long started = System.currentTimeMillis();

		assertThatThrownBy(() -> runner.runForTest(List.of("sleep", "30"), Duration.ofMillis(300)))
			.isInstanceOf(IllegalStateException.class)
			.isNotInstanceOf(FfmpegRunner.InvalidMediaException.class)   // 타임아웃은 파일 불량이 아니다 (P2-2)
			.hasMessageContaining("타임아웃");

		assertThat(System.currentTimeMillis() - started)
			.as("기본 10분이 아니라 호출별 300ms 로 끊겨야 한다")
			.isLessThan(5_000);
	}
}
