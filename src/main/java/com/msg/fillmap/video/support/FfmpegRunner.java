package com.msg.fillmap.video.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ffmpeg/ffprobe 호출 래퍼. PATH 에 있는 바이너리를 쓴다 (로컬 brew, EC2 apt).
 * 실패는 IllegalStateException 계열로 올리고, 처리 정책(FAILED 기록)은 호출자가 정한다.
 * 파일 불량(도구가 돌았는데 입력을 거부)은 {@link InvalidMediaException} 으로 구분한다 — 바이너리 부재·
 * 타임아웃 같은 인프라 실패를 사용자 파일 탓(4xx)으로 오분류하지 않기 위해서다 (MSG-351 교차 리뷰 P2-2).
 */
@Slf4j
@Component
public class FfmpegRunner {

	private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);
	/**
	 * 스트림 probe 와 remux 의 호출별 타임아웃 (MSG-615 D3). 둘 다 헤더 읽기·디스크 복사라 수 초면 끝나고,
	 * 기본 10분을 쓰면 한 작업의 ffmpeg 최악 예산이 MSG-494 D5 의 임대 35분을 넘긴다.
	 */
	static final Duration REMUX_TIMEOUT = Duration.ofSeconds(60);
	// ffprobe JSON 파싱 전용 — 시각 필드가 없어 전역 UTC 코덱(UtcLocalDateTimeJsonCodec)과 무관하다.
	private static final ObjectMapper MAPPER = new ObjectMapper();

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

	/** 원본을 probe 해 remux 가능 여부를 판정한다 (MSG-615 D2). 타임아웃은 remux 와 같은 60초다 (D3). */
	public boolean isRemuxable(Path input) {
		JsonNode streams = parseStreams(run(List.of("ffprobe", "-v", "error", "-show_streams", "-of", "json",
			input.toString()), REMUX_TIMEOUT));
		boolean remuxable = isRemuxable(streams);
		// D6 실측 표의 "ffprobe 판정" 열이 이 줄에서 나온다. input 의 상위 디렉터리명(encode-{videoId}-…)이 영상을 가리킨다.
		log.info("remux 판정: input={} remuxable={} {}", input, remuxable, describe(streams));
		return remuxable;
	}

	/**
	 * ffprobe {@code -show_streams} JSON 으로만 판정한다 (MSG-615 D2). 테스트가 고정 JSON 을 넣는 진입점이라
	 * public static 이다. 첫 비디오가 h264 + 8비트 4:2:0 + 720p 안(방향 무관: 짧은 변 720·긴 변 1280 이하)이고
	 * 첫 오디오가 없거나 aac 면 참. 회전 메타데이터는 읽지 않는다 — width/height 가 회전 적용 전 코딩 크기라
	 * 방향과 무관하게 같은 판정이 나오고, 회전 보존은 remux 명령(-c copy 의 side data 복사)이 맡는다.
	 */
	public static boolean isRemuxable(String ffprobeJson) {
		return isRemuxable(parseStreams(ffprobeJson));
	}

	private static JsonNode parseStreams(String ffprobeJson) {
		try {
			return MAPPER.readTree(ffprobeJson).path("streams");
		} catch (JacksonException e) {
			// exit 0 인데 JSON 이 아닌 출력 — probeDurationSec 의 숫자 파싱 실패와 같은 분류다.
			throw new InvalidMediaException("ffprobe streams 파싱 실패: " + ffprobeJson, e);
		}
	}

	private static boolean isRemuxable(JsonNode streams) {
		JsonNode video = firstStreamOf(streams, "video");
		if (video == null || !"h264".equals(video.path("codec_name").asString(""))) {
			return false;
		}
		String pixFmt = video.path("pix_fmt").asString("");
		if (!"yuv420p".equals(pixFmt) && !"yuvj420p".equals(pixFmt)) {
			return false;
		}
		int width = video.path("width").asInt(0);
		int height = video.path("height").asInt(0);
		if (Math.min(width, height) > 720 || Math.max(width, height) > 1280) {
			return false;
		}
		JsonNode audio = firstStreamOf(streams, "audio");
		return audio == null || "aac".equals(audio.path("codec_name").asString(""));
	}

	/** 판정 로그용 요약 — 예: {@code video=h264 1280x720 yuv420p audio=aac}. 판정이 읽은 필드만 적는다. */
	private static String describe(JsonNode streams) {
		JsonNode video = firstStreamOf(streams, "video");
		JsonNode audio = firstStreamOf(streams, "audio");
		String videoPart = video == null ? "none" : "%s %dx%d %s".formatted(
			video.path("codec_name").asString("?"), video.path("width").asInt(0), video.path("height").asInt(0),
			video.path("pix_fmt").asString("?"));
		String audioPart = audio == null ? "none" : audio.path("codec_name").asString("?");
		return "video=" + videoPart + " audio=" + audioPart;
	}

	/** ffmpeg 의 {@code 0:v:0} / {@code 0:a:0} 선택과 같은 "그 타입의 첫 스트림". data·subtitle 은 자연히 건너뛴다. */
	private static JsonNode firstStreamOf(JsonNode streams, String codecType) {
		for (JsonNode stream : streams) {
			if (codecType.equals(stream.path("codec_type").asString(""))) {
				return stream;
			}
		}
		return null;
	}

	/**
	 * 재인코딩 없이 첫 비디오·첫 오디오만 mp4 로 다시 담는다 (MSG-615 D3). 명시 map 이 iPhone mov 의 mebx
	 * 데이터 트랙을 버린다 — 그 트랙이 끼면 -c copy 가 "codec not currently supported in container" 로 실패한다.
	 */
	public void remux(Path input, Path output) {
		run(List.of(
			"ffmpeg", "-y", "-i", input.toString(),
			"-map", "0:v:0", "-map", "0:a:0?",
			"-c", "copy",
			"-movflags", "+faststart",
			output.toString()), REMUX_TIMEOUT);
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
