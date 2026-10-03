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

	/** lavfi 로 합성한 1280×720 h264/aac — MSG-616 이 앱에서 줄여 보내는 규격이자 remux 대상의 대표형이다. */
	private Path sample720p(Path dir, int durationSec) throws Exception {
		Path out = dir.resolve("src720.mp4");
		Process p = new ProcessBuilder(List.of(
			"ffmpeg", "-y",
			"-f", "lavfi", "-i", "testsrc=size=1280x720:rate=30:duration=" + durationSec,
			"-f", "lavfi", "-i", "sine=frequency=1000:duration=" + durationSec,
			"-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest",
			out.toString()))
			.redirectErrorStream(true)
			.redirectOutput(dir.resolve("gen720.log").toFile())
			.start();
		assertThat(p.waitFor()).isZero();
		return out;
	}

	/**
	 * 세로 촬영본 흉내 — 폰이 센서 가로 프레임에 Display Matrix(회전 90)를 얹어 저장하는 것과 같은 구조다.
	 * ffmpeg 8 은 {@code -metadata rotate} 를 side data 로 쓰지 않아 {@code -display_rotation} 입력 옵션으로 찍는다.
	 */
	private Path rotated(Path dir, Path source, int degrees) throws Exception {
		Path out = dir.resolve("rotated.mp4");
		Process p = new ProcessBuilder(List.of(
			"ffmpeg", "-y", "-display_rotation", String.valueOf(degrees), "-i", source.toString(),
			"-c", "copy", out.toString()))
			.redirectErrorStream(true)
			.redirectOutput(dir.resolve("rot.log").toFile())
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

	// ── remux (MSG-615) ──

	// 검증: NFR-PERF-08, FR-MEDIA-01
	@Test
	void remux_는_720p_h264_입력을_재인코딩_없이_mp4로_옮긴다(@TempDir Path dir) throws Exception {
		assumeTrue(ffmpegAvailable, "ffmpeg 없음 — skip");
		Path source = sample720p(dir, 3);
		Path out = dir.resolve("out.mp4");

		assertThat(runner.isRemuxable(source)).isTrue();
		runner.remux(source, out);

		assertThat(Files.size(out)).isPositive();
		assertThat(probe(out, "stream=width,height,codec_name")).isEqualTo("h264,1280,720");
		assertThat(probe(out, "format=format_name")).contains("mp4");
		// 재인코딩이 없었다는 증거 — 패킷이 그대로 옮겨져 비디오 비트레이트가 원본과 같다.
		assertThat(probe(out, "stream=bit_rate")).isEqualTo(probe(source, "stream=bit_rate"));
	}

	// 검증: FR-MEDIA-01
	@Test
	void remux_는_회전_메타데이터를_보존한다(@TempDir Path dir) throws Exception {
		assumeTrue(ffmpegAvailable, "ffmpeg 없음 — skip");
		Path source = rotated(dir, sample720p(dir, 2), 90);
		assertThat(probe(source, "stream_side_data=rotation")).isEqualTo("90");   // 샘플 자체에 Display Matrix 가 있다
		Path out = dir.resolve("out.mp4");

		assertThat(runner.isRemuxable(source)).isTrue();   // 회전은 판정에 영향 없음 (D2) — width/height 는 코딩 크기
		runner.remux(source, out);

		assertThat(probe(out, "stream=width,height")).startsWith("1280,720");   // 픽셀은 그대로 (side data 행이 꼬리에 붙는다)
		assertThat(probe(out, "stream_side_data=rotation")).isEqualTo("90");  // 플레이어가 세로로 그릴 근거가 남는다
	}

	@Test
	void isRemuxable_은_1080p_샘플에_거짓이다(@TempDir Path dir) throws Exception {
		assumeTrue(ffmpegAvailable, "ffmpeg 없음 — skip");

		assertThat(runner.isRemuxable(sample1080p(dir, 1))).isFalse();
	}

	/** 스트림 probe 와 remux 가 인스턴스 기본값(10분)이 아니라 REMUX_TIMEOUT(60초)으로 도는지 본다 (AC-615-09). */
	// 검증: AC-615-09
	@Test
	void remux_와_스트림_probe_는_60초_호출별_타임아웃으로_돈다() {
		assertThat(FfmpegRunner.REMUX_TIMEOUT).isEqualTo(Duration.ofSeconds(60));

		// 60초 자체를 기다릴 수는 없으니, 같은 run(command, timeout) 오버로드가 인스턴스 기본값을 이기는지로 본다.
		FfmpegRunner tenMinutes = new FfmpegRunner(Duration.ofMinutes(10));
		long started = System.currentTimeMillis();
		assertThatThrownBy(() -> tenMinutes.runForTest(List.of("sleep", "30"), Duration.ofMillis(300)))
			.isInstanceOf(IllegalStateException.class)
			.isNotInstanceOf(FfmpegRunner.InvalidMediaException.class)   // 타임아웃은 폴백 대상(D4)이지 파일 불량이 아니다
			.hasMessageContaining("타임아웃");
		assertThat(System.currentTimeMillis() - started).isLessThan(5_000);
	}

	/**
	 * 인스턴스 기본 타임아웃을 1ms 로 줘서 쓸 수 없게 만들면, 60초 오버로드를 타는 호출만 살아남는다 —
	 * 스트림 probe 와 remux 가 정말 REMUX_TIMEOUT 으로 도는지 직접 본다 (AC-615-09, 리뷰 기록 지시 2).
	 */
	// 검증: AC-615-09
	@Test
	void 스트림_probe_와_remux_는_인스턴스_기본_타임아웃이_아니라_60초_오버로드로_돈다(@TempDir Path dir) throws Exception {
		assumeTrue(ffmpegAvailable, "ffmpeg 없음 — skip");
		FfmpegRunner unusableDefault = new FfmpegRunner(Duration.ofMillis(1));
		Path source = sample720p(dir, 1);
		Path out = dir.resolve("out.mp4");

		// 대조군 — 인스턴스 기본값을 쓰는 길이 probe 는 1ms 에 끊긴다.
		assertThatThrownBy(() -> unusableDefault.probeDurationSec(source)).hasMessageContaining("타임아웃");

		assertThat(unusableDefault.isRemuxable(source)).isTrue();
		unusableDefault.remux(source, out);
		assertThat(Files.size(out)).isPositive();
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
