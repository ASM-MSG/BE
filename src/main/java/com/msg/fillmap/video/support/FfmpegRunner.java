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
 * 실패는 IllegalStateException 계열로 올리고, 처리 정책(FAILED 기록)은 호출자가 정한다.
 * 파일 불량(도구가 돌았는데 입력을 거부)은 {@link InvalidMediaException} 으로 구분한다 — 바이너리 부재·
 * 타임아웃 같은 인프라 실패를 사용자 파일 탓(4xx)으로 오분류하지 않기 위해서다 (MSG-351 교차 리뷰 P2-2).
 */
@Component
public class FfmpegRunner {

	private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

	private final Duration timeout;

	public FfmpegRunner() {
		this(DEFAULT_TIMEOUT);
	}

	/** 타임아웃 주입은 테스트에서 행 상황을 검증하기 위한 것이다 — 운영은 기본 생성자를 쓴다. */
	FfmpegRunner(Duration timeout) {
		this.timeout = timeout;
	}

	/** 영상 길이(초). 손상 파일이면 ffprobe 가 실패하므로 여기서 걸러진다. */
	public double probeDurationSec(Path input) {
		return probeDurationSec(input, timeout);
	}

	/**
	 * 호출별 타임아웃 오버로드 (MSG-351 교차 리뷰 P1-2) — 기본 10분은 인코딩용이라, 사용자가 HTTP 응답을
	 * 기다리는 동기 선분석 경로는 훨씬 짧은 상한으로 probe 한다. 기존 호출자는 1-인자 버전 그대로.
	 */
	public double probeDurationSec(Path input, Duration probeTimeout) {
		String out = run(List.of(
			"ffprobe", "-v", "error",
			"-show_entries", "format=duration",
			"-of", "default=noprint_wrappers=1:nokey=1",
			input.toString()), probeTimeout);
		try {
			return Double.parseDouble(out.trim());
		} catch (NumberFormatException e) {
			// exit 0 인데 duration 이 없는 파일(N/A 등) — 우리 목적엔 못 여는 파일과 같다.
			throw new InvalidMediaException("ffprobe duration 파싱 실패: " + out, e);
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

	/**
	 * 표준 출력·에러를 모두 파일로 받은 뒤 waitFor 로 기다린다.
	 * 스트림을 직접 읽으면(readAllBytes) 프로세스가 출력을 닫을 때까지 블로킹되므로, 프로세스가 행에 걸리면
	 * 아래 타임아웃에 도달하지 못한다. 인코딩 풀이 1개짜리라 그 경우 인코딩 전체가 멈춘다.
	 */
	private String run(List<String> command) {
		return run(command, timeout);
	}

	private String run(List<String> command, Duration runTimeout) {
		Process process = null;
		Path outFile = null;
		Path errFile = null;
		try {
			outFile = Files.createTempFile("ffmpeg-out", ".log");
			errFile = Files.createTempFile("ffmpeg-err", ".log");
			process = new ProcessBuilder(command)
				.redirectOutput(outFile.toFile())
				.redirectError(errFile.toFile())
				.start();

			if (!process.waitFor(runTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
				throw new IllegalStateException("ffmpeg 타임아웃(" + runTimeout + "): " + command);
			}
			if (process.exitValue() != 0) {
				// 도구는 정상 실행됐고 입력을 거부한 것 — 파일 불량으로 분류한다 (P2-2)
				throw new InvalidMediaException("ffmpeg 실패(exit %d): %s%n%s"
					.formatted(process.exitValue(), command, tail(Files.readString(errFile))));
			}
			return Files.readString(outFile);
		} catch (IOException e) {
			throw new IllegalStateException("ffmpeg 실행 실패: " + command, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("ffmpeg 대기 중 인터럽트: " + command, e);
		} finally {
			if (process != null && process.isAlive()) {
				process.destroyForcibly();
			}
			deleteQuietly(outFile);
			deleteQuietly(errFile);
		}
	}

	/** 행·타임아웃 회귀 테스트 전용 진입점 (같은 패키지에서만 보인다). */
	String runForTest(List<String> command) {
		return run(command);
	}

	/** 호출별 타임아웃 회귀 테스트 전용 진입점 (같은 패키지에서만 보인다). */
	String runForTest(List<String> command, Duration runTimeout) {
		return run(command, runTimeout);
	}

	private void deleteQuietly(Path file) {
		if (file == null) {
			return;
		}
		try {
			Files.deleteIfExists(file);
		} catch (IOException ignored) {
			// 임시 로그 파일이라 삭제 실패가 인코딩 결과를 바꾸지 않는다.
		}
	}

	/** ffmpeg stderr 는 진행 로그까지 길게 나오므로 원인이 담긴 끝부분만 남긴다. */
	private String tail(String text) {
		String[] lines = text.split("\n");
		int from = Math.max(0, lines.length - 5);
		return String.join("\n", List.of(lines).subList(from, lines.length));
	}

	/**
	 * 입력 파일 불량 — 도구가 정상 실행됐는데 입력을 거부(exit != 0)했거나 duration 을 못 읽은 경우.
	 * IllegalStateException 서브타입이라 기존의 넓은 catch(인코딩 파이프라인)는 동작이 변하지 않고,
	 * 사용자 대면 경로(선분석 3426)만 이 타입으로 좁혀 잡는다 (MSG-351 교차 리뷰 P2-2).
	 */
	public static class InvalidMediaException extends IllegalStateException {

		public InvalidMediaException(String message) {
			super(message);
		}

		public InvalidMediaException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
