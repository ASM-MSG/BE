package com.msg.fillmap.video.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

	@Test
	void encode720p_는_1080p를_1280x720_h264로_변환한다(@TempDir Path dir) throws Exception {
		assumeTrue(ffmpegAvailable, "ffmpeg 없음 — skip");
		Path out = dir.resolve("out.mp4");

		runner.encode720p(sample1080p(dir, 3), out);

		assertThat(Files.size(out)).isPositive();
		assertThat(probe(out, "stream=width,height,codec_name")).isEqualTo("h264,1280,720");
	}

	@Test
	void extractThumbnail_은_jpg_한_장을_만든다(@TempDir Path dir) throws Exception {
		assumeTrue(ffmpegAvailable, "ffmpeg 없음 — skip");
		Path thumb = dir.resolve("t.jpg");

		runner.extractThumbnail(sample1080p(dir, 3), thumb, 3.0);

		assertThat(Files.size(thumb)).isPositive();
		assertThat(probe(thumb, "stream=codec_name")).isEqualTo("mjpeg");
	}

	@Test
	void 짧은_영상도_썸네일이_나온다(@TempDir Path dir) throws Exception {
		assumeTrue(ffmpegAvailable, "ffmpeg 없음 — skip");
		Path thumb = dir.resolve("t.jpg");

		// seek 1초를 그대로 쓰면 짧은 영상에서 프레임을 못 잡아 빈 파일이 된다 — 첫 프레임 폴백 확인.
		runner.extractThumbnail(sample1080p(dir, 1), thumb, 0.5);

		assertThat(Files.size(thumb)).isPositive();
	}

	@Test
	void 손상된_파일이면_예외를_던진다(@TempDir Path dir) throws Exception {
		assumeTrue(ffmpegAvailable, "ffmpeg 없음 — skip");
		Path broken = dir.resolve("broken.mp4");
		Files.write(broken, new byte[2048]);

		assertThatThrownBy(() -> runner.probeDurationSec(broken))
			.isInstanceOf(IllegalStateException.class);
	}
}
