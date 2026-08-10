package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.video.dto.HighlightPreviewRequestDto;
import com.msg.fillmap.video.dto.HighlightPreviewResponseDto;
import com.msg.fillmap.video.exception.VideoErrorCode;
import com.msg.fillmap.video.support.FfmpegRunner;

/**
 * 선분석 처리 흐름 검증 (MSG-351). AiClient·S3Client·FfmpegRunner 는 mock — 스펙 테스트 시나리오 그대로.
 * 저장 의존(repository)이 아예 주입되지 않는 구조라 "어떤 저장 호출도 없음"은 구조로 보장된다.
 */
@DisplayName("HighlightPreviewService — 하이라이트 선분석")
class HighlightPreviewServiceTest {

	private static final long USER_ID = 42L;
	private static final String MY_PENDING_KEY = "videos/pending/42/550e8400-e29b-41d4-a716-446655440000.mp4";

	private final AiClient aiClient = mock(AiClient.class);
	@SuppressWarnings("unchecked")
	private final ObjectProvider<AiClient> aiClientProvider = mock(ObjectProvider.class);
	private final S3Client s3Client = mock(S3Client.class);
	private final FfmpegRunner ffmpegRunner = mock(FfmpegRunner.class);

	private HighlightPreviewService highlightPreviewService;

	@BeforeEach
	void setUp() {
		given(aiClientProvider.getIfAvailable()).willReturn(aiClient);
		// 기본값: S3 에 상한 안쪽 크기의 객체가 존재한다 (headObject 크기 선검증 통과 — 교차 리뷰 P1-2)
		given(s3Client.headObject(any(HeadObjectRequest.class)))
			.willReturn(HeadObjectResponse.builder().contentLength(1048576L).build());
		AwsProperties properties = new AwsProperties(
			"ap-northeast-2", new AwsProperties.S3("fillmap-video-dev", 104857600L, 2147483648L));
		highlightPreviewService = new HighlightPreviewServiceImpl(aiClientProvider, s3Client, properties, ffmpegRunner);
	}

	@Test
	void 선분석은_AI가_반환한_구간_배열을_그대로_반환한다() {
		given(ffmpegRunner.probeDurationSec(any(Path.class), any(Duration.class))).willReturn(30.0);
		given(aiClient.analyzeHighlights(any(Path.class)))
			.willReturn(List.of(List.of(0.0, 5.12), List.of(10.0, 16.4)));

		HighlightPreviewResponseDto response = highlightPreviewService.analyze(
			USER_ID, new HighlightPreviewRequestDto(MY_PENDING_KEY));

		// BE 는 구간을 가공하지 않는다 — 순서 포함 그대로 (개수·길이·간격 보정은 AI 몫, MSG-353)
		assertThat(response.highlights()).containsExactly(List.of(0.0, 5.12), List.of(10.0, 16.4));
	}

	@Test
	void 다른_사용자의_pending_키는_거부된다() {
		assertThatThrownBy(() -> highlightPreviewService.analyze(
			USER_ID, new HighlightPreviewRequestDto("videos/pending/7/other.mp4")))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.INVALID_S3_KEY);

		verifyNoInteractions(s3Client);
	}

	@Test
	void S3에_없는_키는_업로드_미발견으로_거부된다() {
		given(s3Client.getObject(any(GetObjectRequest.class), any(Path.class)))
			.willThrow(NoSuchKeyException.builder().build());

		assertThatThrownBy(() -> highlightPreviewService.analyze(
			USER_ID, new HighlightPreviewRequestDto(MY_PENDING_KEY)))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.UPLOAD_NOT_FOUND);
	}

	@Test
	void 선분석_getObject에는_요청_단위_전체_호출_시한이_걸린다() {
		// 소켓 타임아웃 안쪽에서 느리게 흐르는 대형 전송이 permit 을 무기한 점유하지 않도록 (교차 리뷰 3R-2).
		// 전역 S3Config 는 그대로 두고 선분석 요청에만 override — 값 산정 근거는 구현 상수 주석
		given(ffmpegRunner.probeDurationSec(any(Path.class), any(Duration.class))).willReturn(30.0);
		given(aiClient.analyzeHighlights(any(Path.class))).willReturn(List.of());

		highlightPreviewService.analyze(USER_ID, new HighlightPreviewRequestDto(MY_PENDING_KEY));

		ArgumentCaptor<GetObjectRequest> requests = ArgumentCaptor.forClass(GetObjectRequest.class);
		verify(s3Client).getObject(requests.capture(), any(Path.class));
		assertThat(requests.getValue().overrideConfiguration()
			.flatMap(AwsRequestOverrideConfiguration::apiCallTimeout))
			.contains(Duration.ofSeconds(60));
	}

	@Test
	void 원본_다운로드_시한_초과는_업스트림_에러로_수렴한다() {
		// 시한 초과는 사용자 파일 문제가 아니라 인프라 사정 — AI leg 실패와 같은 3502 수렴 (교차 리뷰 3R-2)
		given(s3Client.getObject(any(GetObjectRequest.class), any(Path.class)))
			.willThrow(ApiCallTimeoutException.create(60000L));

		assertThatThrownBy(() -> highlightPreviewService.analyze(
			USER_ID, new HighlightPreviewRequestDto(MY_PENDING_KEY)))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.HIGHLIGHT_UPSTREAM_ERROR);
	}

	@Test
	void 전용_상한을_넘는_원본은_다운로드_없이_즉시_거부된다() {
		// read timeout 은 AI leg 만 묶는다 — 다운로드 전에 headObject 실측 크기로 끊어야 S3 leg 시한도 잡힌다 (P1-2)
		given(s3Client.headObject(any(HeadObjectRequest.class)))
			.willReturn(HeadObjectResponse.builder().contentLength(2147483649L).build());

		assertThatThrownBy(() -> highlightPreviewService.analyze(
			USER_ID, new HighlightPreviewRequestDto(MY_PENDING_KEY)))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.FILE_TOO_LARGE);

		verify(s3Client, never()).getObject(any(GetObjectRequest.class), any(Path.class));
	}

	@Test
	void 실측_3분_초과_원본은_거부된다() {
		given(ffmpegRunner.probeDurationSec(any(Path.class), any(Duration.class))).willReturn(180.01);

		assertThatThrownBy(() -> highlightPreviewService.analyze(
			USER_ID, new HighlightPreviewRequestDto(MY_PENDING_KEY)))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.HIGHLIGHT_SOURCE_TOO_LONG);

		verify(aiClient, never()).analyzeHighlights(any(Path.class));
	}

	@Test
	void 실측_3분_정각_원본은_허용된다() {
		// 경계 — FE 1차 차단과 같은 기준(3분 초과 거부, 정각 허용, D-3)
		given(ffmpegRunner.probeDurationSec(any(Path.class), any(Duration.class))).willReturn(180.0);
		given(aiClient.analyzeHighlights(any(Path.class))).willReturn(List.of(List.of(0.0, 8.0)));

		HighlightPreviewResponseDto response = highlightPreviewService.analyze(
			USER_ID, new HighlightPreviewRequestDto(MY_PENDING_KEY));

		assertThat(response.highlights()).containsExactly(List.of(0.0, 8.0));
		verify(aiClient).analyzeHighlights(any(Path.class));
	}

	@Test
	void AI가_빈_배열을_반환하면_빈_배열이_내려간다() {
		// 5초 미만 원본 — AI 가 조건 채우는 구간 없음으로 [] 반환 (FR-4, FE 는 추천 단계 스킵)
		given(ffmpegRunner.probeDurationSec(any(Path.class), any(Duration.class))).willReturn(3.2);
		given(aiClient.analyzeHighlights(any(Path.class))).willReturn(List.of());

		HighlightPreviewResponseDto response = highlightPreviewService.analyze(
			USER_ID, new HighlightPreviewRequestDto(MY_PENDING_KEY));

		assertThat(response.highlights()).isNotNull().isEmpty();
	}

	@Test
	void 열_수_없는_원본은_불량으로_거부된다() {
		// 케이스 1: ffprobe 실패 (손상 파일) — 파일 불량 타입만 3426 이다 (인프라 실패는 아래 별도 테스트)
		given(ffmpegRunner.probeDurationSec(any(Path.class), any(Duration.class)))
			.willThrow(new FfmpegRunner.InvalidMediaException("ffprobe duration 파싱 실패"));

		assertThatThrownBy(() -> highlightPreviewService.analyze(
			USER_ID, new HighlightPreviewRequestDto(MY_PENDING_KEY)))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.HIGHLIGHT_SOURCE_UNREADABLE);
	}

	@Test
	void ffprobe_인프라_실패는_파일_불량이_아니라_공통_500_경로로_전파된다() {
		// 바이너리 부재·프로세스 타임아웃은 사용자 파일 문제가 아니다 — 3426(400)으로 오분류하지 않고
		// 그대로 전파해 공통 INTERNAL_SERVER_ERROR 로 간다 (교차 리뷰 P2-2).
		given(ffmpegRunner.probeDurationSec(any(Path.class), any(Duration.class)))
			.willThrow(new IllegalStateException("ffmpeg 타임아웃(PT30S)"));

		assertThatThrownBy(() -> highlightPreviewService.analyze(
			USER_ID, new HighlightPreviewRequestDto(MY_PENDING_KEY)))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void AI가_422를_반환한_원본도_불량으로_거부된다() {
		// 케이스 2: ffprobe 는 통과했지만 AI 가 422 로 거부
		given(ffmpegRunner.probeDurationSec(any(Path.class), any(Duration.class))).willReturn(30.0);
		given(aiClient.analyzeHighlights(any(Path.class)))
			.willThrow(new AiClient.HighlightSourceRejectedException(new RuntimeException("422")));

		assertThatThrownBy(() -> highlightPreviewService.analyze(
			USER_ID, new HighlightPreviewRequestDto(MY_PENDING_KEY)))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.HIGHLIGHT_SOURCE_UNREADABLE);
	}

	@Test
	void AI_실패는_업스트림_에러_하나로_수렴한다() {
		given(ffmpegRunner.probeDurationSec(any(Path.class), any(Duration.class))).willReturn(30.0);
		List<Throwable> causes = List.of(
			new ConnectException("연결 실패"),
			new SocketTimeoutException("read timeout"),
			new RuntimeException("AI 5xx"));

		for (Throwable cause : causes) {
			given(aiClient.analyzeHighlights(any(Path.class)))
				.willThrow(new AiClient.HighlightUpstreamException(cause));

			assertThatThrownBy(() -> highlightPreviewService.analyze(
				USER_ID, new HighlightPreviewRequestDto(MY_PENDING_KEY)))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.HIGHLIGHT_UPSTREAM_ERROR);
		}
	}

	@Test
	void AI_비활성_환경에서는_업스트림_에러로_끝나고_S3를_호출하지_않는다() {
		given(aiClientProvider.getIfAvailable()).willReturn(null);

		assertThatThrownBy(() -> highlightPreviewService.analyze(
			USER_ID, new HighlightPreviewRequestDto(MY_PENDING_KEY)))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.HIGHLIGHT_UPSTREAM_ERROR);

		verifyNoInteractions(s3Client);   // fail fast — S3 호출 낭비 없음
	}

	@Test
	void 선분석_probe는_전용_짧은_타임아웃으로_돈다() {
		given(ffmpegRunner.probeDurationSec(any(Path.class), any(Duration.class))).willReturn(30.0);
		given(aiClient.analyzeHighlights(any(Path.class))).willReturn(List.of());

		highlightPreviewService.analyze(USER_ID, new HighlightPreviewRequestDto(MY_PENDING_KEY));

		// 기본 10분(인코딩용)이 아니라 동기 사용자 경로 전용 30초 상한 (교차 리뷰 P1-2)
		verify(ffmpegRunner).probeDurationSec(any(Path.class), eq(Duration.ofSeconds(30)));
	}

	@Test
	void 동시_선분석이_허용치를_넘으면_즉시_거부된다() throws Exception {
		// 요청당 임시 원본 최대 2GiB, BE dev 디스크 20GB — 허용치(2) 초과는 대기 없이 3429 즉시 거부해
		// FE 직접 지정 폴백(FR-6)으로 보낸다 (교차 리뷰 P1-A)
		given(ffmpegRunner.probeDurationSec(any(Path.class), any(Duration.class))).willReturn(30.0);
		CountDownLatch occupied = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);
		given(aiClient.analyzeHighlights(any(Path.class))).willAnswer(invocation -> {
			occupied.countDown();
			release.await(5, TimeUnit.SECONDS);
			return List.of();
		});

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Future<?>> inFlight = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				inFlight.add(pool.submit(() -> highlightPreviewService.analyze(
					USER_ID, new HighlightPreviewRequestDto(MY_PENDING_KEY))));
			}
			assertThat(occupied.await(5, TimeUnit.SECONDS)).isTrue();   // permit 2개 전부 점유 상태

			assertThatThrownBy(() -> highlightPreviewService.analyze(
				USER_ID, new HighlightPreviewRequestDto(MY_PENDING_KEY)))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.HIGHLIGHT_BUSY);

			release.countDown();
			for (Future<?> future : inFlight) {
				future.get(5, TimeUnit.SECONDS);   // 선점유 2건은 정상 완료
			}
			// 완료로 permit 이 풀리면 다음 요청은 다시 허용된다 (성공 경로 finally 해제 검증)
			assertThat(highlightPreviewService.analyze(
				USER_ID, new HighlightPreviewRequestDto(MY_PENDING_KEY)).highlights()).isEmpty();
		} finally {
			release.countDown();
			pool.shutdownNow();
		}
	}

	@Test
	void 실패한_선분석도_permit을_반환한다() {
		// permit 누수면 허용치(2) 초과인 3번째부터 3429 — 전부 3425 여야 실패 경로 finally 해제가 증명된다
		given(ffmpegRunner.probeDurationSec(any(Path.class), any(Duration.class))).willReturn(180.01);

		for (int i = 0; i < 3; i++) {
			assertThatThrownBy(() -> highlightPreviewService.analyze(
				USER_ID, new HighlightPreviewRequestDto(MY_PENDING_KEY)))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.HIGHLIGHT_SOURCE_TOO_LONG);
		}
	}

	@Test
	void 임시_파일은_성공과_실패_모두에서_정리된다() {
		// 성공 경로
		given(ffmpegRunner.probeDurationSec(any(Path.class), any(Duration.class))).willReturn(30.0);
		given(aiClient.analyzeHighlights(any(Path.class))).willReturn(List.of());
		highlightPreviewService.analyze(USER_ID, new HighlightPreviewRequestDto(MY_PENDING_KEY));

		// 실패 경로 (3425)
		given(ffmpegRunner.probeDurationSec(any(Path.class), any(Duration.class))).willReturn(180.01);
		assertThatThrownBy(() -> highlightPreviewService.analyze(
			USER_ID, new HighlightPreviewRequestDto(MY_PENDING_KEY)))
			.isInstanceOf(ApiException.class);

		ArgumentCaptor<Path> sources = ArgumentCaptor.forClass(Path.class);
		verify(ffmpegRunner, times(2)).probeDurationSec(sources.capture(), any(Duration.class));
		for (Path source : sources.getAllValues()) {
			assertThat(source.getParent()).doesNotExist();   // 임시 디렉터리째 정리됨
		}
	}
}
