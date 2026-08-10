package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

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
		AwsProperties properties = new AwsProperties(
			"ap-northeast-2", new AwsProperties.S3("fillmap-video-dev", 104857600L, 2147483648L));
		highlightPreviewService = new HighlightPreviewServiceImpl(aiClientProvider, s3Client, properties, ffmpegRunner);
	}

	@Test
	void 선분석은_AI가_반환한_구간_배열을_그대로_반환한다() {
		given(ffmpegRunner.probeDurationSec(any(Path.class))).willReturn(30.0);
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
	void 실측_3분_초과_원본은_거부된다() {
		given(ffmpegRunner.probeDurationSec(any(Path.class))).willReturn(180.01);

		assertThatThrownBy(() -> highlightPreviewService.analyze(
			USER_ID, new HighlightPreviewRequestDto(MY_PENDING_KEY)))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.HIGHLIGHT_SOURCE_TOO_LONG);

		verify(aiClient, never()).analyzeHighlights(any(Path.class));
	}

	@Test
	void 실측_3분_정각_원본은_허용된다() {
		// 경계 — FE 1차 차단과 같은 기준(3분 초과 거부, 정각 허용, D-3)
		given(ffmpegRunner.probeDurationSec(any(Path.class))).willReturn(180.0);
		given(aiClient.analyzeHighlights(any(Path.class))).willReturn(List.of(List.of(0.0, 8.0)));

		HighlightPreviewResponseDto response = highlightPreviewService.analyze(
			USER_ID, new HighlightPreviewRequestDto(MY_PENDING_KEY));

		assertThat(response.highlights()).containsExactly(List.of(0.0, 8.0));
		verify(aiClient).analyzeHighlights(any(Path.class));
	}

	@Test
	void AI가_빈_배열을_반환하면_빈_배열이_내려간다() {
		// 5초 미만 원본 — AI 가 조건 채우는 구간 없음으로 [] 반환 (FR-4, FE 는 추천 단계 스킵)
		given(ffmpegRunner.probeDurationSec(any(Path.class))).willReturn(3.2);
		given(aiClient.analyzeHighlights(any(Path.class))).willReturn(List.of());

		HighlightPreviewResponseDto response = highlightPreviewService.analyze(
			USER_ID, new HighlightPreviewRequestDto(MY_PENDING_KEY));

		assertThat(response.highlights()).isNotNull().isEmpty();
	}

	@Test
	void 열_수_없는_원본은_불량으로_거부된다() {
		// 케이스 1: ffprobe 실패 (손상 파일)
		given(ffmpegRunner.probeDurationSec(any(Path.class)))
			.willThrow(new IllegalStateException("ffprobe duration 파싱 실패"));

		assertThatThrownBy(() -> highlightPreviewService.analyze(
			USER_ID, new HighlightPreviewRequestDto(MY_PENDING_KEY)))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.HIGHLIGHT_SOURCE_UNREADABLE);
	}

	@Test
	void AI가_422를_반환한_원본도_불량으로_거부된다() {
		// 케이스 2: ffprobe 는 통과했지만 AI 가 422 로 거부
		given(ffmpegRunner.probeDurationSec(any(Path.class))).willReturn(30.0);
		given(aiClient.analyzeHighlights(any(Path.class)))
			.willThrow(new AiClient.HighlightSourceRejectedException(new RuntimeException("422")));

		assertThatThrownBy(() -> highlightPreviewService.analyze(
			USER_ID, new HighlightPreviewRequestDto(MY_PENDING_KEY)))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.HIGHLIGHT_SOURCE_UNREADABLE);
	}

	@Test
	void AI_실패는_업스트림_에러_하나로_수렴한다() {
		given(ffmpegRunner.probeDurationSec(any(Path.class))).willReturn(30.0);
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
	void 임시_파일은_성공과_실패_모두에서_정리된다() {
		// 성공 경로
		given(ffmpegRunner.probeDurationSec(any(Path.class))).willReturn(30.0);
		given(aiClient.analyzeHighlights(any(Path.class))).willReturn(List.of());
		highlightPreviewService.analyze(USER_ID, new HighlightPreviewRequestDto(MY_PENDING_KEY));

		// 실패 경로 (3425)
		given(ffmpegRunner.probeDurationSec(any(Path.class))).willReturn(180.01);
		assertThatThrownBy(() -> highlightPreviewService.analyze(
			USER_ID, new HighlightPreviewRequestDto(MY_PENDING_KEY)))
			.isInstanceOf(ApiException.class);

		ArgumentCaptor<Path> sources = ArgumentCaptor.forClass(Path.class);
		verify(ffmpegRunner, times(2)).probeDurationSec(sources.capture());
		for (Path source : sources.getAllValues()) {
			assertThat(source.getParent()).doesNotExist();   // 임시 디렉터리째 정리됨
		}
	}
}
