package com.msg.fillmap.video.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.video.dto.HighlightPreviewRequestDto;
import com.msg.fillmap.video.dto.HighlightPreviewResponseDto;
import com.msg.fillmap.video.exception.VideoErrorCode;
import com.msg.fillmap.video.support.FfmpegRunner;

/**
 * 선분석 처리 흐름 전체 (MSG-351). 요청 스레드에서 동기 실행한다 (D-2) — AI 계약이 동기고, 결과를 저장할
 * 곳이 없어(PRD 비목표) 폴링형이 성립하지 않는다. 대기 상한은 AiClient 의 read timeout 60초 (D-5).
 * 트랜잭션 없음 — DB 를 한 번도 건드리지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HighlightPreviewServiceImpl implements HighlightPreviewService {

	private static final String PENDING_PREFIX = "videos/pending/";
	// FE 1차 차단과 같은 기준 — 180.00초 정각 허용, 초과 거부 (D-3, FR-8 서버 방어선)
	private static final double MAX_SOURCE_DURATION_SEC = 180.0;

	// ObjectProvider: AiClient 는 ai.enabled 일 때만 뜨는 빈이라 직접 주입하면 비활성 환경에서 기동이 깨진다.
	private final ObjectProvider<AiClient> aiClientProvider;
	private final S3Client s3Client;
	private final AwsProperties awsProperties;
	private final FfmpegRunner ffmpegRunner;

	@Override
	public HighlightPreviewResponseDto analyze(long userId, HighlightPreviewRequestDto request) {
		// 1. 내 pending 키인지 — confirmUpload 의 첫 가드와 같은 술어. S3 부수효과 전에 거부한다.
		if (!request.s3Key().startsWith("%s%d/".formatted(PENDING_PREFIX, userId))) {
			throw new ApiException(VideoErrorCode.INVALID_S3_KEY);
		}
		// 2. AI 가용 fail fast — 비활성 환경이면 S3 호출 낭비 없이 3502 로 끝낸다.
		AiClient aiClient = aiClientProvider.getIfAvailable();
		if (aiClient == null) {
			log.warn("하이라이트 선분석 불가 — AI 비활성 환경 (ai.enabled=false)");
			throw new ApiException(VideoErrorCode.HIGHLIGHT_UPSTREAM_ERROR);
		}

		Path workDir = null;
		try {
			workDir = createWorkDir();
			Path source = workDir.resolve("source");
			// 3. S3 원본 → 파일 (VideoEncodingServiceImpl.download 선례, 스트리밍이라 힙 무관)
			download(request.s3Key(), source);
			// 4. 실측 길이 방어선 (D-3) — FE 신고값은 위조 가능해서 서버가 직접 잰다.
			validateDuration(source);
			// 5~6. AI 동기 제출, 받은 배열 그대로 응답 (구간 보정은 AI 몫 — MSG-353)
			return new HighlightPreviewResponseDto(analyzeWithAi(aiClient, source));
		} finally {
			deleteQuietly(workDir);
		}
	}

	/** 임시 디렉터리 생성 실패는 도메인 에러가 아닌 서버 사정 — 공통 500 경로로 보낸다. */
	private Path createWorkDir() {
		try {
			return Files.createTempDirectory("highlight-preview-");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void download(String s3Key, Path target) {
		try {
			s3Client.getObject(
				GetObjectRequest.builder().bucket(awsProperties.s3().bucket()).key(s3Key).build(),
				target);
		} catch (NoSuchKeyException e) {
			throw new ApiException(VideoErrorCode.UPLOAD_NOT_FOUND, e);
		}
	}

	private void validateDuration(Path source) {
		double duration;
		try {
			duration = ffmpegRunner.probeDurationSec(source);
		} catch (IllegalStateException e) {
			// ffprobe 가 못 여는 파일 — 사용자 파일 자체가 불량이라 재시도가 무의미하다 (3502 와 행위자가 다름)
			throw new ApiException(VideoErrorCode.HIGHLIGHT_SOURCE_UNREADABLE, e);
		}
		if (duration > MAX_SOURCE_DURATION_SEC) {
			throw new ApiException(VideoErrorCode.HIGHLIGHT_SOURCE_TOO_LONG);
		}
	}

	private List<List<Double>> analyzeWithAi(AiClient aiClient, Path source) {
		try {
			return aiClient.analyzeHighlights(source);
		} catch (AiClient.HighlightSourceRejectedException e) {
			throw new ApiException(VideoErrorCode.HIGHLIGHT_SOURCE_UNREADABLE, e);
		} catch (AiClient.HighlightUpstreamException e) {
			// 원인(연결·타임아웃·5xx·파싱)은 진단용 로그로만 구분 — FE 대응이 전부 직접 지정 폴백이라 단일 수렴
			log.warn("하이라이트 선분석 업스트림 실패 — 3502 수렴", e);
			throw new ApiException(VideoErrorCode.HIGHLIGHT_UPSTREAM_ERROR, e);
		}
	}

	/** 임시파일이 쌓이면 t3.small 디스크가 먼저 죽는다 — VideoEncodingServiceImpl 과 같은 정리 방식. */
	private void deleteQuietly(Path dir) {
		if (dir == null) {
			return;
		}
		try (Stream<Path> paths = Files.walk(dir)) {
			paths.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException e) {
					log.warn("임시파일 삭제 실패: {}", p, e);
				}
			});
		} catch (IOException e) {
			log.warn("임시 디렉터리 정리 실패: {}", dir, e);
		}
	}
}
