package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.video.config.AiProperties;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.FfmpegRunner;
import com.msg.fillmap.video.support.GeoSupport;
import com.msg.fillmap.video.support.VideoAssetKeys;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * ffmpeg·S3 를 목으로 대체해 파이프라인 분기만 검증한다 (CI 에 ffmpeg 가 없어도 돈다).
 * 실제 인코딩 산출물 검증은 FfmpegRunnerTest 가 담당한다.
 */
@DisplayName("VideoEncodingService 파이프라인 분기")
class VideoEncodingServiceTest {

	private static final long VIDEO_ID = 7L;
	private static final String ORIGINAL_KEY = "videos/original/1/x.mp4";
	private static final VideoAssetKeys ASSET_KEYS = VideoAssetKeys.from(1L, VIDEO_ID, ORIGINAL_KEY);
	/** 대부분의 케이스가 스텁하는 실측 10.0 초의 저장값 (MSG-470) — 신고값 10 과 우연히 같다. */
	private static final short MEASURED = 10;

	private VideoRepository videoRepository;
	private VideoStatusWriter statusWriter;
	private FfmpegRunner ffmpegRunner;
	private S3Client s3Client;
	private SimpleMeterRegistry meterRegistry;
	private ObjectProvider<AiClient> aiClientProvider;
	private ThreadPoolTaskExecutor highlightExecutor;
	private VideoEncodingService encodingService;
	private Video video;
	private EncodingJobClaim claim;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		videoRepository = mock(VideoRepository.class);
		statusWriter = mock(VideoStatusWriter.class);
		ffmpegRunner = mock(FfmpegRunner.class);
		s3Client = mock(S3Client.class);
		meterRegistry = new SimpleMeterRegistry();
		// getIfAvailable() 기본 null — 비 AI 환경 그대로라 하이라이트 워커 제출이 조기 반환한다 (FR-5)
		aiClientProvider = mock(ObjectProvider.class);
		highlightExecutor = mock(ThreadPoolTaskExecutor.class);

		encodingService = service(false, false);

		video = Video.create(1L, "19495_9607", ORIGINAL_KEY,
			GeoSupport.toPoint(37.5445, 127.0560), (short) 10, LocalDateTime.now(), Visibility.PRIVATE);
		claim = new EncodingJobClaim(1L, VIDEO_ID, ORIGINAL_KEY, UUID.randomUUID(),
			(short) 1, LocalDateTime.of(2026, 8, 27, 0, 0));
		given(videoRepository.findById(VIDEO_ID)).willReturn(Optional.of(video));
		// 실제 라이터처럼 엔티티 상태도 전이시킨다 (MSG-382 Codex 리뷰) — 업로드 직전 fresh 재확인(쌍둥이 술어)이
		// 정상 흐름에서 ENCODING 을 실제로 지나게 해서, 술어에서 ENCODING 허용이 빠지면 아래 정상 테스트가 깨진다.
		given(statusWriter.markEncoding(claim)).willAnswer(invocation -> {
			video.markEncoding();
			return true;
		});
	}

	/**
	 * 플래그 조합별 서비스 조립 (MSG-456) — @Value 필드가 AiProperties 생성자 주입으로 바뀌어
	 * ReflectionTestUtils 대신 record 직접 생성으로 분기를 정한다. meterRegistry 는 필드를 재사용하므로
	 * 재조립해도 계측 카운터는 같은 레지스트리에 쌓인다.
	 */
	private VideoEncodingService service(boolean aiEnabled, boolean blurEnabled) {
		AwsProperties properties = new AwsProperties(
			"ap-northeast-2", new AwsProperties.S3("fillmap-video-dev", 104857600L, 2147483648L));
		return new VideoEncodingServiceImpl(
			videoRepository, statusWriter, ffmpegRunner, s3Client, properties,
			new VideoProcessingMetrics(meterRegistry),
			new AiProperties(aiEnabled, blurEnabled, "http://ai.test", Duration.ofMinutes(30), 30000L),
			aiClientProvider, highlightExecutor);
	}

	// 검증: FR-MEDIA-01, FR-MEDIA-02
	@Test
	void 정상_영상이면_ENCODING_거쳐_READY_로_전이한다() {
		given(ffmpegRunner.probeDurationSec(any())).willReturn(10.0);
		// 업로드는 실제 파일을 읽으므로, 목 ffmpeg 가 산출물을 만든 것처럼 흉내낸다.
		createFileOn(ffmpegRunner).encode720p(any(), any());
		createFileOn(ffmpegRunner).extractThumbnail(any(), any(), anyDouble());

		encodingService.encode(claim);

		verify(statusWriter).markEncoding(claim);
		verify(statusWriter).markReady(claim, ASSET_KEYS.encoded(), ASSET_KEYS.thumbnail(),
			MEASURED);
		verify(statusWriter, never()).markFailed(claim);
	}

	/** 목 호출 시 출력 경로(마지막에서 두 번째 인자가 아닌 Path 인자)에 빈 파일을 만들어 준다. */
	private FfmpegRunner createFileOn(FfmpegRunner mock) {
		return org.mockito.BDDMockito.willAnswer(invocation -> {
			Path output = invocation.getArgument(1);
			Files.createDirectories(output.getParent());
			Files.write(output, new byte[] {1});
			return null;
		}).given(mock);
	}

	// 검증: FR-MEDIA-03
	@Test
	void 길이가_판정_여유_31초를_넘으면_인코딩하지_않고_FAILED_다() {
		given(ffmpegRunner.probeDurationSec(any())).willReturn(31.5);

		encodingService.encode(claim);

		verify(statusWriter).markFailed(claim);
		verify(ffmpegRunner, never()).encode720p(any(), any());
		verify(statusWriter, never()).markReady(eq(claim), any(), any(), anyShort());
	}

	// 검증: FR-MEDIA-03
	@Test
	void 실측이_30초를_살짝_넘어도_여유_구간이면_인코딩한다() {
		// 업로드가 정수 30초로 통과시킨 영상의 실측이 메타데이터 반올림으로 30.0x 초가 나오는 케이스 (MSG-370).
		given(ffmpegRunner.probeDurationSec(any())).willReturn(30.5);
		createFileOn(ffmpegRunner).encode720p(any(), any());
		createFileOn(ffmpegRunner).extractThumbnail(any(), any(), anyDouble());

		encodingService.encode(claim);

		verify(statusWriter).markReady(claim, ASSET_KEYS.encoded(), ASSET_KEYS.thumbnail(),
			(short) 30);   // 반올림 31 이 스키마 상한 30 으로 눌린다 (MSG-470)
		verify(statusWriter, never()).markFailed(claim);
	}

	// 검증: FR-MEDIA-02
	@Test
	void 손상_영상이라_ffprobe_가_실패하면_FAILED_다() {
		willThrow(new FfmpegRunner.InvalidMediaException("ffprobe 실패"))
			.given(ffmpegRunner).probeDurationSec(any());

		encodingService.encode(claim);

		verify(statusWriter).markFailed(claim);
		verify(statusWriter, never()).markReady(eq(claim), any(), any(), anyShort());
	}

	// 검증: FR-MEDIA-02
	@Test
	void ffmpeg_환경_실패는_재시도를_위해_호출자에게_전파한다() {
		given(ffmpegRunner.probeDurationSec(any())).willReturn(10.0);
		willThrow(new IllegalStateException("ffmpeg 실패"))
			.given(ffmpegRunner).encode720p(any(Path.class), any(Path.class));

		assertThatThrownBy(() -> encodingService.encode(claim)).hasMessage("ffmpeg 실패");
		verify(statusWriter, never()).markFailed(claim);
	}

	@Test
	void 시작_직후_영상이_사라지면_작업만_종결한다() {
		EncodingJobClaim missingClaim = new EncodingJobClaim(2L, 999L, ORIGINAL_KEY, UUID.randomUUID(),
			(short) 1, LocalDateTime.of(2026, 8, 27, 0, 0));
		given(statusWriter.markEncoding(missingClaim)).willReturn(true);
		given(videoRepository.findById(999L)).willReturn(Optional.empty());

		encodingService.encode(missingClaim);

		verify(statusWriter).complete(missingClaim);
		verify(statusWriter, never()).markFailed(missingClaim);
	}

	@Test
	void 썸네일_추출은_실제_길이를_받아_seek_지점을_고른다() {
		given(ffmpegRunner.probeDurationSec(any())).willReturn(0.5);
		createFileOn(ffmpegRunner).encode720p(any(), any());
		createFileOn(ffmpegRunner).extractThumbnail(any(), any(), anyDouble());

		encodingService.encode(claim);

		verify(ffmpegRunner).extractThumbnail(any(), any(), eq(0.5));
	}

	// 검증: FR-MEDIA-02
	@Test
	void S3_다운로드가_실패하면_재시도를_위해_호출자에게_전파한다() {
		willThrow(new RuntimeException("S3 다운로드 실패"))
			.given(s3Client).getObject(any(GetObjectRequest.class), any(Path.class));

		assertThatThrownBy(() -> encodingService.encode(claim)).hasMessage("S3 다운로드 실패");

		verify(statusWriter, never()).markFailed(claim);
		verify(ffmpegRunner, never()).probeDurationSec(any());
	}

	// 검증: FR-MEDIA-04
	@Test
	void AI_활성이면_인코딩_단계에서_미블러_썸네일을_올리지_않는다() {
		encodingService = service(true, true);   // 실효 블러 활성 (MSG-456)
		given(ffmpegRunner.probeDurationSec(any())).willReturn(10.0);
		createFileOn(ffmpegRunner).encode720p(any(), any());

		encodingService.encode(claim);

		// 인코딩본 하나만 올린다 — 미블러 썸네일은 추출도 업로드도 하지 않는다 (P1).
		ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
		verify(s3Client, times(1)).putObject(captor.capture(), any(RequestBody.class));
		assertThat(captor.getValue().key()).isEqualTo(ASSET_KEYS.encoded());
		verify(ffmpegRunner, never()).extractThumbnail(any(), any(), anyDouble());
		// thumbnail 키는 폴러가 완료 시 기록(R5)
		verify(statusWriter).markEncoded(claim, ASSET_KEYS.encoded(), MEASURED);
	}

	// ── 실측 길이 저장 (MSG-470) ──
	// 저장되는 길이는 클라 신고값(픽스처 10 초)이 아니라 ffprobe 실측의 반올림·1~30 클램프 값이다.

	// 검증: FR-MEDIA-19
	@Test
	void 실측_길이가_반올림되어_성공_전이에_실린다() {
		given(ffmpegRunner.probeDurationSec(any())).willReturn(12.7);
		createFileOn(ffmpegRunner).encode720p(any(), any());
		createFileOn(ffmpegRunner).extractThumbnail(any(), any(), anyDouble());

		encodingService.encode(claim);

		verify(statusWriter).markReady(claim, ASSET_KEYS.encoded(), ASSET_KEYS.thumbnail(),
			(short) 13);
	}

	// 검증: FR-MEDIA-19
	@Test
	void 여유_구간_실측은_30으로_클램프된다() {
		// 반올림하면 31 이라 스키마 CHECK(duration_sec <= 30)를 위반한다 — FAILED 가 아니라 30 으로 저장한다.
		given(ffmpegRunner.probeDurationSec(any())).willReturn(30.6);
		createFileOn(ffmpegRunner).encode720p(any(), any());
		createFileOn(ffmpegRunner).extractThumbnail(any(), any(), anyDouble());

		encodingService.encode(claim);

		verify(statusWriter).markReady(claim, ASSET_KEYS.encoded(), ASSET_KEYS.thumbnail(),
			(short) 30);
		verify(statusWriter, never()).markFailed(claim);
	}

	// 검증: FR-MEDIA-19
	@Test
	void 실측이_1초_미만이면_1로_클램프된다() {
		// 반올림하면 0 이라 같은 CHECK 의 하한(duration_sec > 0)을 위반한다.
		given(ffmpegRunner.probeDurationSec(any())).willReturn(0.3);
		createFileOn(ffmpegRunner).encode720p(any(), any());
		createFileOn(ffmpegRunner).extractThumbnail(any(), any(), anyDouble());

		encodingService.encode(claim);

		verify(statusWriter).markReady(claim, ASSET_KEYS.encoded(), ASSET_KEYS.thumbnail(),
			(short) 1);
	}

	// 검증: FR-MEDIA-19
	@Test
	void 블러_활성이면_markEncoded에_실측_길이가_실린다() {
		encodingService = service(true, true);
		given(ffmpegRunner.probeDurationSec(any())).willReturn(12.7);
		createFileOn(ffmpegRunner).encode720p(any(), any());

		encodingService.encode(claim);

		verify(statusWriter).markEncoded(claim, ASSET_KEYS.encoded(), (short) 13);
	}

	// 검증: FR-MEDIA-03, FR-MEDIA-19
	@Test
	void 길이_초과_판정은_클램프_전_원값으로_한다() {   // 자바 식별자는 숫자로 시작할 수 없어 "31초" 를 풀어 썼다
		// 클램프가 판정보다 앞서면 31.4 가 30 으로 뭉개져 초과 영상이 통과한다 — 판정은 double 원값으로 끝낸다.
		given(ffmpegRunner.probeDurationSec(any())).willReturn(31.4);

		encodingService.encode(claim);

		verify(statusWriter).markFailed(claim);
		verify(statusWriter, never()).markReady(eq(claim), any(), any(), anyShort());
	}

	// ── 인코딩 태스크 계측 (MSG-343 모듈 2) ──

	private double taskCount(String result) {
		return meterRegistry.get("video.encoding.task").tag("result", result).counter().count();
	}

	@Test
	void 인코딩_성공은_completed를_한_번_증가시킨다() {
		given(ffmpegRunner.probeDurationSec(any())).willReturn(10.0);
		createFileOn(ffmpegRunner).encode720p(any(), any());
		createFileOn(ffmpegRunner).extractThumbnail(any(), any(), anyDouble());

		encodingService.encode(claim);   // 블러 비활성 → markReady 경로
		assertThat(taskCount("completed")).isEqualTo(1.0);

		encodingService = service(true, true);
		encodingService.encode(claim);   // 실효 블러 활성 → markEncoded 경로도 completed (스펙 표)
		assertThat(taskCount("completed")).isEqualTo(2.0);
		assertThat(taskCount("failed_over_duration")).isZero();
		assertThat(taskCount("failed_error")).isZero();
	}

	@Test
	void 실측_길이_초과는_failed_over_duration으로_기록된다() {
		given(ffmpegRunner.probeDurationSec(any())).willReturn(31.5);

		encodingService.encode(claim);

		assertThat(taskCount("failed_over_duration")).isEqualTo(1.0);
		assertThat(taskCount("completed")).isZero();
		assertThat(taskCount("failed_error")).isZero();
	}

	@Test
	void 길이_초과에서_markFailed가_던져도_failed_over_duration_한_번만_계상된다() {
		// 분류 보존 (Codex 2R) — 전이 기록 실패가 outer catch 로 떨어져도 result 카운트는 태스크당 정확히 1회.
		given(ffmpegRunner.probeDurationSec(any())).willReturn(31.5);
		willThrow(new RuntimeException("전이 기록 실패")).given(statusWriter).markFailed(claim);

		assertThatThrownBy(() -> encodingService.encode(claim)).hasMessage("전이 기록 실패");

		assertThat(taskCount("failed_over_duration")).isEqualTo(1.0);
		assertThat(taskCount("failed_error")).isZero();
		assertThat(taskCount("completed")).isZero();
	}

	@Test
	void ffmpeg_예외는_failed_error로_기록된다() {
		given(ffmpegRunner.probeDurationSec(any())).willReturn(10.0);
		willThrow(new IllegalStateException("ffmpeg 실패"))
			.given(ffmpegRunner).encode720p(any(Path.class), any(Path.class));

		assertThatThrownBy(() -> encodingService.encode(claim)).hasMessage("ffmpeg 실패");

		assertThat(taskCount("failed_error")).isEqualTo(1.0);
		assertThat(taskCount("completed")).isZero();
		assertThat(taskCount("failed_over_duration")).isZero();
	}

	@Test
	void 교체로_원본이_바뀌면_인코딩_결과를_업로드하지_않는다() {
		given(ffmpegRunner.probeDurationSec(any())).willReturn(10.0);
		createFileOn(ffmpegRunner).encode720p(any(), any());
		createFileOn(ffmpegRunner).extractThumbnail(any(), any(), anyDouble());
		// ffmpeg 가 도는 사이 사용자가 교체 — 업로드 직전 fresh 로드가 다른 원본 키를 돌려준다 (MSG-241).
		video.replaceFile("videos/original/1/y.mp4", (short) 8, LocalDateTime.now());

		encodingService.encode(claim);

		verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
		verify(statusWriter).complete(claim);
		verify(statusWriter, never()).markReady(eq(claim), any(), any(), anyShort());
	}
}
