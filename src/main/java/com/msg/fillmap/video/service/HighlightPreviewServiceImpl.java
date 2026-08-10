package com.msg.fillmap.video.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.video.dto.HighlightPreviewRequestDto;
import com.msg.fillmap.video.dto.HighlightPreviewResponseDto;
import com.msg.fillmap.video.exception.VideoErrorCode;
import com.msg.fillmap.video.support.FfmpegRunner;

/**
 * 선분석 처리 흐름 전체 (MSG-351). 요청 스레드에서 동기 실행한다 (D-2) — AI 계약이 동기고, 결과를 저장할
 * 곳이 없어(PRD 비목표) 폴링형이 성립하지 않는다. 대기 상한은 AiClient 의 교환 전체 시한 120초 (D-5, P1-B).
 * 트랜잭션 없음 — DB 를 한 번도 건드리지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HighlightPreviewServiceImpl implements HighlightPreviewService {

	private static final String PENDING_PREFIX = "videos/pending/";
	// FE 1차 차단과 같은 기준 — 180.00초 정각 허용, 초과 거부 (D-3, FR-8 서버 방어선)
	private static final double MAX_SOURCE_DURATION_SEC = 180.0;
	// 동기 사용자 경로의 probe 상한 (교차 리뷰 P1-2) — FfmpegRunner 기본 10분은 인코딩용이다.
	// 로컬 파일 3분 이하 probe 는 1초 미만이라 30초는 행에 걸린 프로세스만 끊는다.
	private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);
	// 동시 선분석 게이트 (교차 리뷰 P1-A) — 요청당 임시 원본이 최대 2GiB(max-highlight-upload-bytes)인데
	// BE dev 디스크는 20GB 라, 동시 2건(최대 4GiB)까지만 받는다. 초과는 대기열 없이 3429 즉시 거부 —
	// FE 가 직접 지정 폴백(FR-6)으로 빠지는 게 수십 초 줄서기보다 낫다.
	// ponytail: 프로세스 전역 상수 게이트 — 다중 인스턴스·용량 산정이 필요해지면 프로퍼티로 승격한다.
	private static final int MAX_CONCURRENT_ANALYSES = 2;
	// getObject 전체 호출 시한 (교차 리뷰 3R-2) — 소켓 타임아웃 안쪽에서 느리게 흐르는 대형 전송이 permit 을
	// 무기한 점유하는 걸 끊는다. 전역 S3Config 는 그대로 두고 이 요청에만 요청 단위 override 로 건다.
	// 산정은 AiClient.HIGHLIGHT_READ_TIMEOUT(120초)과 같은 결: 최대 원본 2GiB(max-highlight-upload-bytes)
	// ÷ 내부망 실효 전송률 ≈85MB/s(AI leg 산정의 "2GiB 전송 여유"와 동일 가정) ≈ 24초 × 동시 허용 2건
	// 경합 여유 ≈ 60초. 이로써 permit 점유 상한은 60 + probe 30 + AI 120 ≈ 210초로 유계가 된다.
	private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(60);

	private final Semaphore analysisPermits = new Semaphore(MAX_CONCURRENT_ANALYSES);

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

		// 3. headObject 실측 크기 선검증 (교차 리뷰 P1-2) — read timeout 은 AI leg 만 묶는다. 상한 초과 원본을
		// 다운로드부터 하면 S3 leg 가 시한 없이 늘어지므로, 받기 전에 크기로 끊는다.
		requireSizeWithinLimit(request.s3Key());
		// 4. 동시 실행 게이트 (P1-A) — 디스크를 먹는 다운로드~AI 전송(임시 파일 생존 구간) 전체를 permit 이 덮는다.
		if (!analysisPermits.tryAcquire()) {
			throw new ApiException(VideoErrorCode.HIGHLIGHT_BUSY);
		}
		try {
			return analyzeWithPermit(request.s3Key(), aiClient);
		} finally {
			analysisPermits.release();
		}
	}

	private HighlightPreviewResponseDto analyzeWithPermit(String s3Key, AiClient aiClient) {
		Path workDir = null;
		try {
			workDir = createWorkDir();
			Path source = workDir.resolve("source");
			// 5. S3 원본 → 파일 (VideoEncodingServiceImpl.download 선례, 스트리밍이라 힙 무관)
			download(s3Key, source);
			// 6. 실측 길이 방어선 (D-3) — FE 신고값은 위조 가능해서 서버가 직접 잰다.
			validateDuration(source);
			// 7~8. AI 동기 제출, 받은 배열 그대로 응답 (구간 보정은 AI 몫 — MSG-353)
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

	/** 상한(max-highlight-upload-bytes) 초과 원본을 다운로드 없이 즉시 거부한다 — 404 매핑은 confirmUpload 선례. */
	private void requireSizeWithinLimit(String s3Key) {
		HeadObjectResponse head;
		try {
			head = s3Client.headObject(
				HeadObjectRequest.builder().bucket(awsProperties.s3().bucket()).key(s3Key).build());
		} catch (NoSuchKeyException e) {
			throw new ApiException(VideoErrorCode.UPLOAD_NOT_FOUND, e);
		} catch (S3Exception e) {
			// HeadObject 는 본문 없는 404 를 주므로 SDK 가 NoSuchKeyException 으로 못 좁히는 경우가 있다.
			if (e.statusCode() == 404) {
				throw new ApiException(VideoErrorCode.UPLOAD_NOT_FOUND, e);
			}
			throw e;
		}
		Long contentLength = head.contentLength();
		if (contentLength != null && contentLength > awsProperties.s3().maxHighlightUploadBytes()) {
			throw new ApiException(VideoErrorCode.FILE_TOO_LARGE);
		}
	}

	private void download(String s3Key, Path target) {
		try {
			s3Client.getObject(
				GetObjectRequest.builder()
					.bucket(awsProperties.s3().bucket())
					.key(s3Key)
					.overrideConfiguration(o -> o.apiCallTimeout(DOWNLOAD_TIMEOUT))
					.build(),
				target);
		} catch (NoSuchKeyException e) {
			throw new ApiException(VideoErrorCode.UPLOAD_NOT_FOUND, e);
		} catch (SdkClientException e) {
			// ApiCallTimeoutException 포함 클라이언트측 실패 (3R-2) — 사용자 파일 문제(404, 위)와 달리
			// 인프라 사정이라 AI leg 실패와 같은 3502 로 수렴한다. 서비스 에러(S3Exception)는 기존대로 전파.
			log.warn("선분석 원본 다운로드 실패 — 3502 수렴", e);
			throw new ApiException(VideoErrorCode.HIGHLIGHT_UPSTREAM_ERROR, e);
		}
	}

	private void validateDuration(Path source) {
		double duration;
		try {
			duration = ffmpegRunner.probeDurationSec(source, PROBE_TIMEOUT);
		} catch (FfmpegRunner.InvalidMediaException e) {
			// ffprobe 가 못 여는 파일 — 사용자 파일 자체가 불량이라 재시도가 무의미하다 (3502 와 행위자가 다름).
			// 파일 불량 타입만 잡는다 (교차 리뷰 P2-2): 바이너리 부재·타임아웃 같은 인프라 실패는 사용자 탓이
			// 아니므로 그대로 전파해 공통 INTERNAL_SERVER_ERROR 경로로 보낸다.
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
