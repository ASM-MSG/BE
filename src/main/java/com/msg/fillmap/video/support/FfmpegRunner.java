package com.msg.fillmap.video.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

/**
 * ffmpeg/ffprobe 호출 래퍼. PATH 에 있는 바이너리를 쓴다 (로컬 brew, EC2 apt).
 * 실패는 모두 IllegalStateException 으로 올리고, 처리 정책(FAILED 기록)은 호출자가 정한다.
 */
@Component
public class FfmpegRunner {

	private static final Duration TIMEOUT = Duration.ofMinutes(10);

	/** 영상 길이(초). 손상 파일이면 ffprobe 가 실패하므로 여기서 걸러진다. */
	public double probeDurationSec(Path input) {
		String out = run(List.of(
			"ffprobe", "-v", "error",
			"-show_entries", "format=duration",
			"-of", "default=noprint_wrappers=1:nokey=1",
			input.toString()));
		try {
			return Double.parseDouble(out.trim());
		} catch (NumberFormatException e) {
			throw new IllegalStateException("ffprobe duration 파싱 실패: " + out, e);
		}
	}

	/** 720p H.264 + AAC 로 변환 (MSG-65 D4). 세로 720 기준, 가로는 짝수로 맞춘다(-2). */
	public void encode720p(Path input, Path output) {
		run(List.of(
			"ffmpeg", "-y", "-i", input.toString(),
			"-vf", "scale=-2:720",
			"-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
			"-c:a", "aac", "-b:a", "128k",
			"-movflags", "+faststart",
			output.toString()));
	}

	/** 썸네일 1장. 1초 지점을 뽑되, 그보다 짧은 영상이면 첫 프레임으로 폴백한다. */
	public void extractThumbnail(Path input, Path output, double durationSec) {
		String seek = durationSec > 1.0 ? "1" : "0";
		run(List.of(
			"ffmpeg", "-y", "-ss", seek, "-i", input.toString(),
			"-frames:v", "1", "-vf", "scale=-2:720", "-q:v", "2",
			output.toString()));
	}

	private String run(List<String> command) {
		Process process = null;
		try {
			Path stderr = Files.createTempFile("ffmpeg-err", ".log");
			process = new ProcessBuilder(command)
				.redirectErrorStream(false)
				.redirectError(stderr.toFile())
				.start();

			String stdout = new String(process.getInputStream().readAllBytes());
			if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
				process.destroyForcibly();
				throw new IllegalStateException("ffmpeg 타임아웃(" + TIMEOUT.toMinutes() + "분): " + command);
			}
			if (process.exitValue() != 0) {
				String err = Files.exists(stderr) ? Files.readString(stderr) : "";
				throw new IllegalStateException(
					"ffmpeg 실패(exit %d): %s%n%s".formatted(process.exitValue(), command, tail(err)));
			}
			Files.deleteIfExists(stderr);
			return stdout;
		} catch (IOException e) {
			throw new IllegalStateException("ffmpeg 실행 실패: " + command, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("ffmpeg 대기 중 인터럽트: " + command, e);
		} finally {
			if (process != null && process.isAlive()) {
				process.destroyForcibly();
			}
		}
	}

	/** ffmpeg stderr 는 진행 로그까지 길게 나오므로 원인이 담긴 끝부분만 남긴다. */
	private String tail(String text) {
		String[] lines = text.split("\n");
		int from = Math.max(0, lines.length - 5);
		return String.join("\n", List.of(lines).subList(from, lines.length));
	}
}
