package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;

import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.video.config.AiProperties;
import com.msg.fillmap.video.entity.ProcessingStatus;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.VideoStatus;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.service.AiClient.AiJobResult;
import com.msg.fillmap.video.service.AiClient.AiJobStatus;
import com.msg.fillmap.video.support.FfmpegRunner;
import com.msg.fillmap.video.support.GeoSupport;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * MSG-149/150 폴러: BLURRING 잡별 reconcile(제출/폴링/완료/썸네일 재추출/실패/타임아웃/연결실패) 분기를 목으로 검증한다.
 * DB·S3·AI·ffmpeg 는 목이라 실 인프라 없이 돈다.
 */
@DisplayName("AiBlurPoller — BLURRING 조정")
class AiBlurPollerTest {

	private VideoRepository videoRepository;
	private VideoStatusWriter statusWriter;
	private AiClient aiClient;
	private S3Client s3Client;
	private FfmpegRunner ffmpegRunner;
	private ThreadPoolTaskExecutor encodingExecutor;
	private AiBlurPoller poller;

	@BeforeEach
	void setUp() {
		videoRepository = mock(VideoRepository.class);
		statusWriter = mock(VideoStatusWriter.class);
		aiClient = mock(AiClient.class);
		s3Client = mock(S3Client.class);
		ffmpegRunner = mock(FfmpegRunner.class);
		encodingExecutor = new ThreadPoolTaskExecutor();   // 실제 풀 — submit/get 직렬화 경로를 그대로 탄다
		encodingExecutor.initialize();
		AwsProperties awsProperties = new AwsProperties(
			"ap-northeast-2", new AwsProperties.S3("fillmap-video-dev", 104857600L));
		AiProperties aiProperties = new AiProperties(true, "http://ai.test", Duration.ofMinutes(30), 30000L);

		poller = new AiBlurPoller(videoRepository, statusWriter, aiClient, s3Client, awsProperties, aiProperties,
			ffmpegRunner, encodingExecutor);

		// 목 ffmpeg 가 썸네일 산출물을 만든 것처럼 흉내낸다 (VideoEncodingServiceTest 관례).
		willAnswer(invocation -> {
			Files.write(invocation.getArgument(1), new byte[] {1});
			return null;
		}).given(ffmpegRunner).extractThumbnail(any(Path.class), any(Path.class), anyDouble());
	}

	@AfterEach
	void tearDown() {
		encodingExecutor.shutdown();
	}

	/**
	 * BLURRING 상태의 영상 하나를 만든다. encoded 키가 채워지고, createdAt·blurringStartedAt 모두 인자 시각으로
	 * 맞춘다 — 타임아웃 기준이 blurringStartedAt 이라 둘을 정렬해야 "n분 전 시작" 시나리오가 그대로 성립한다.
	 */
	private Video blurring(long id, String jobId, LocalDateTime startedAt) {
		Video video = Video.create(1L, "41716_110483", "videos/original/1/x.mp4",
			GeoSupport.toPoint(37.5445, 127.0560), (short) 10, LocalDateTime.now());
		video.markEncoding();
		video.markEncoded("videos/encoded/1/" + id + ".mp4");
		if (jobId != null) {
			video.recordAiJob(jobId);
		}
		ReflectionTestUtils.setField(video, "id", id);
		ReflectionTestUtils.setField(video, "createdAt", startedAt);
		ReflectionTestUtils.setField(video, "blurringStartedAt", startedAt);
		return video;
	}

	private void givenBlurring(Video... videos) {
		given(videoRepository.findByStatusAndProcessingStatus(VideoStatus.ACTIVE, ProcessingStatus.BLURRING))
			.willReturn(List.of(videos));
	}

	private void givenS3Download(byte[] bytes) {
		given(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
			.willReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), bytes));
	}

	private void givenDone(String jobId, List<List<Double>> highlights) {
		given(aiClient.poll(jobId)).willReturn(new AiJobResult(AiJobStatus.DONE, highlights, false));
		given(aiClient.downloadBlurred(jobId)).willReturn("blurred".getBytes());
	}

	@Test
	void BLURRING이고_job_id가_null이면_encoded를_재다운로드해_제출하고_job_id를_기록한다() {
		Video video = blurring(7L, null, LocalDateTime.now());
		givenBlurring(video);
		givenS3Download("encoded".getBytes());
		given(aiClient.submit(any())).willReturn("job-1");

		poller.reconcile();

		ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.forClass(GetObjectRequest.class);
		verify(s3Client).getObjectAsBytes(captor.capture());
		assertThat(captor.getValue().key()).isEqualTo("videos/encoded/1/7.mp4");
		verify(aiClient).submit(any());
		verify(statusWriter).recordAiJob(7L, "job-1", video.getBlurringStartedAt());
	}

	@Test
	void DONE이면_블러본을_S3에_올리고_blurred_key와_highlights를_채운_뒤_READY로_전이한다() {
		givenBlurring(blurring(7L, "job-1", LocalDateTime.now()));
		List<List<Double>> highlights = List.of(List.of(0.0, 3.33));
		givenDone("job-1", highlights);
		given(statusWriter.markBlurReady(anyLong(), anyString(), anyString(), anyString(), any())).willReturn(true);

		poller.reconcile();

		ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
		verify(s3Client, times(2)).putObject(captor.capture(), any(RequestBody.class));   // 블러본 + 썸네일
		assertThat(captor.getAllValues()).extracting(PutObjectRequest::key)
			.containsExactlyInAnyOrder("videos/blurred/1/7.mp4", "videos/thumb/1/7.jpg");
		verify(statusWriter).markBlurReady(7L, "job-1", "videos/blurred/1/7.mp4", "videos/thumb/1/7.jpg", highlights);
		verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));   // 적용됐으니 정리 안 함
	}

	@Test
	void 완료되면_블러본에서_썸네일을_재추출해_같은_키에_올린다() {
		givenBlurring(blurring(7L, "job-1", LocalDateTime.now()));
		givenDone("job-1", List.of());
		given(statusWriter.markBlurReady(anyLong(), anyString(), anyString(), anyString(), any())).willReturn(true);

		poller.reconcile();

		verify(ffmpegRunner).probeDurationSec(any(Path.class));   // 클라 신고값 대신 실측 probe (P2-b)
		verify(ffmpegRunner).extractThumbnail(any(Path.class), any(Path.class), anyDouble());   // 블러본에서 프레임 추출
		ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
		verify(s3Client, times(2)).putObject(captor.capture(), any(RequestBody.class));
		PutObjectRequest thumbPut = captor.getAllValues().stream()
			.filter(r -> r.key().equals("videos/thumb/1/7.jpg"))   // 인코딩 때와 같은 썸네일 키 덮어쓰기
			.findFirst().orElseThrow();
		assertThat(thumbPut.contentType()).isEqualTo("image/jpeg");
	}

	@Test
	void 가드가_거부하면_방금_올린_블러본과_재추출_썸네일을_지운다() {
		givenBlurring(blurring(7L, "job-1", LocalDateTime.now()));
		givenDone("job-1", List.of());
		given(statusWriter.markBlurReady(anyLong(), anyString(), anyString(), anyString(), any())).willReturn(false);   // 가드 거부

		poller.reconcile();

		ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
		verify(s3Client, times(2)).deleteObject(captor.capture());   // 블러본 + 재추출 썸네일 둘 다 고아
		assertThat(captor.getAllValues()).extracting(DeleteObjectRequest::key)
			.containsExactlyInAnyOrder("videos/blurred/1/7.mp4", "videos/thumb/1/7.jpg");
	}

	@Test
	void status가_해석불가면_타임아웃_경로로_수렴한다() {
		Video video = blurring(7L, "job-1", LocalDateTime.now().minusMinutes(40));   // 상한 초과
		givenBlurring(video);
		given(aiClient.poll("job-1")).willReturn(new AiJobResult(AiJobStatus.UNKNOWN, null, false));

		poller.reconcile();

		// UNKNOWN 은 살아있지 않음 → 타임아웃 경로 → 상한 초과라 FAILED (즉시 FAILED 가 아니라 타임아웃 경유)
		verify(statusWriter).markBlurFailed(7L, "job-1", video.getBlurringStartedAt());
	}

	@Test
	void DONE인데_다운로드나_추출이_실패하면_BLURRING을_유지하고_READY로_전이하지_않는다() {
		givenBlurring(blurring(7L, "job-1", LocalDateTime.now()));
		givenDone("job-1", List.of());
		willThrow(new IllegalStateException("ffmpeg fail"))   // 썸네일 재추출 실패
			.given(ffmpegRunner).extractThumbnail(any(Path.class), any(Path.class), anyDouble());

		assertThatCode(() -> poller.reconcile()).doesNotThrowAnyException();

		verify(statusWriter, never()).markBlurReady(anyLong(), anyString(), anyString(), anyString(), any());   // READY 전이 안 함
		verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));   // 업로드 전에 중단
		verify(statusWriter, never()).markBlurFailed(anyLong(), any(), any());   // 타임아웃 전이라 FAILED 도 아님
	}

	@Test
	void DONE_소비가_계속_실패하고_타임아웃을_넘으면_FAILED로_수렴한다() {
		Video video = blurring(7L, "job-1", LocalDateTime.now().minusMinutes(40));   // 시도 시작 40분 전(상한 초과)
		givenBlurring(video);
		given(aiClient.poll("job-1")).willReturn(new AiJobResult(AiJobStatus.DONE, List.of(), false));
		given(aiClient.downloadBlurred("job-1")).willThrow(new RuntimeException("download fail"));   // 소비 실패 반복

		poller.reconcile();

		// DONE 소비 실패가 외곽 로그가 아니라 타임아웃 경로로 수렴 → 상한 초과라 FAILED (성공 기준 3, P1)
		verify(statusWriter).markBlurFailed(7L, "job-1", video.getBlurringStartedAt());
		verify(statusWriter, never()).markBlurReady(anyLong(), anyString(), anyString(), anyString(), any());
	}

	@Test
	void poll_응답_파싱이_실패하고_타임아웃을_넘으면_FAILED로_수렴한다() {
		Video video = blurring(7L, "job-1", LocalDateTime.now().minusMinutes(40));   // 상한 초과
		givenBlurring(video);
		// 200 인데 malformed body → AiClient 파싱이 역참조 RuntimeException (RestClientException 아님)
		given(aiClient.poll("job-1")).willThrow(new RuntimeException("malformed body"));

		poller.reconcile();

		// 넓힌 catch 가 파싱 실패까지 타임아웃 경로로 라우팅 → 상한 초과라 FAILED (P2-a)
		verify(statusWriter).markBlurFailed(7L, "job-1", video.getBlurringStartedAt());
	}

	@Test
	void DONE인데_영상이_계속_미가용이고_타임아웃을_넘으면_FAILED로_수렴한다() {
		Video video = blurring(7L, "job-1", LocalDateTime.now().minusMinutes(40));   // 상한 초과
		givenBlurring(video);
		given(aiClient.poll("job-1")).willReturn(new AiJobResult(AiJobStatus.DONE, List.of(), false));
		given(aiClient.downloadBlurred("job-1")).willReturn(null);   // 409 지속·빈 body

		poller.reconcile();

		// downloadBlurred null 이 조기 return 이 아니라 타임아웃 경로로 → 상한 초과라 FAILED (P2-b)
		verify(statusWriter).markBlurFailed(7L, "job-1", video.getBlurringStartedAt());
		verify(statusWriter, never()).markBlurReady(anyLong(), anyString(), anyString(), anyString(), any());
	}

	@Test
	void 블러본_업로드_후_persist가_실패하면_방금_올린_객체를_정리한다() {
		givenBlurring(blurring(7L, "job-1", LocalDateTime.now()));
		givenDone("job-1", List.of());
		given(statusWriter.markBlurReady(anyLong(), anyString(), anyString(), anyString(), any()))
			.willThrow(new RuntimeException("persist fail"));   // 두 업로드 성공 후 persist 실패

		poller.reconcile();

		ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
		verify(s3Client, times(2)).deleteObject(captor.capture());   // 부분 업로드된 블러본·썸네일 정리 (P2-a)
		assertThat(captor.getAllValues()).extracting(DeleteObjectRequest::key)
			.containsExactlyInAnyOrder("videos/blurred/1/7.mp4", "videos/thumb/1/7.jpg");
	}

	@Test
	void 교체된_옛_영상의_재시도는_행_생성시각이_아니라_시도_시작시각으로_타임아웃을_잰다() {
		Video video = blurring(7L, "job-1", LocalDateTime.now().minusMinutes(31));   // 행 생성은 31분 전
		ReflectionTestUtils.setField(video, "blurringStartedAt", LocalDateTime.now());   // 이번 시도는 방금 시작
		givenBlurring(video);
		given(aiClient.poll("job-1")).willReturn(new AiJobResult(AiJobStatus.PROCESSING, null, false));

		poller.reconcile();

		verify(statusWriter, never()).markBlurFailed(anyLong(), any(), any());   // 타임아웃으로 오탐 안 됨
		verify(aiClient).poll("job-1");
	}

	@Test
	void AI가_QUEUED로_응답하면_타임아웃_상한을_넘어도_FAILED하지_않는다() {
		givenBlurring(blurring(7L, "job-1", LocalDateTime.now().minusMinutes(40)));   // 시도 시작 40분 전(상한 초과)
		given(aiClient.poll("job-1")).willReturn(new AiJobResult(AiJobStatus.QUEUED, null, false));

		poller.reconcile();

		verify(aiClient).poll("job-1");   // 제출된 잡은 먼저 poll
		verify(statusWriter, never()).markBlurFailed(anyLong(), any(), any());   // 살아있어 타임아웃 검사 skip
	}

	@Test
	void AI가_FAILED거나_잡이_404면_markBlurFailed로_전이한다() {
		Video failVideo = blurring(7L, "job-fail", LocalDateTime.now());
		Video lostVideo = blurring(8L, "job-lost", LocalDateTime.now());
		givenBlurring(failVideo, lostVideo);
		given(aiClient.poll("job-fail")).willReturn(new AiJobResult(AiJobStatus.FAILED, null, false));
		given(aiClient.poll("job-lost")).willReturn(new AiJobResult(null, null, true));

		poller.reconcile();

		verify(statusWriter).markBlurFailed(7L, "job-fail", failVideo.getBlurringStartedAt());
		verify(statusWriter).markBlurFailed(8L, "job-lost", lostVideo.getBlurringStartedAt());
		verify(statusWriter, never()).markBlurReady(anyLong(), anyString(), anyString(), anyString(), any());
	}

	@Test
	void 미제출_BLURRING의_경과가_타임아웃_상한을_넘으면_markBlurFailed로_전이한다() {
		Video video = blurring(7L, null, LocalDateTime.now().minusMinutes(40));
		givenBlurring(video);

		poller.reconcile();

		verify(statusWriter).markBlurFailed(7L, null, video.getBlurringStartedAt());
		verify(aiClient, never()).submit(any());   // 타임아웃이라 제출도 안 함
		verify(aiClient, never()).poll(anyString());
	}

	@Test
	void 제출된_잡의_poll이_연결실패하고_타임아웃_상한을_넘으면_markBlurFailed로_전이한다() {
		Video video = blurring(7L, "job-1", LocalDateTime.now().minusMinutes(40));
		givenBlurring(video);
		willThrow(new ResourceAccessException("connection refused")).given(aiClient).poll("job-1");

		poller.reconcile();

		verify(statusWriter).markBlurFailed(7L, "job-1", video.getBlurringStartedAt());
	}

	@Test
	void AI_연결_실패면_BLURRING을_유지하고_다음_주기에_재시도한다() {
		givenBlurring(blurring(7L, null, LocalDateTime.now()));
		givenS3Download("encoded".getBytes());
		willThrow(new ResourceAccessException("connection refused")).given(aiClient).submit(any());

		assertThatCode(() -> poller.reconcile()).doesNotThrowAnyException();

		verify(statusWriter, never()).recordAiJob(anyLong(), anyString(), any());
		verify(statusWriter, never()).markBlurFailed(anyLong(), any(), any());
	}

	@Test
	void 재시작_후에도_BLURRING_영상이_폴러_조회에_잡힌다() {
		givenBlurring(blurring(7L, "job-1", LocalDateTime.now()));
		given(aiClient.poll("job-1")).willReturn(new AiJobResult(AiJobStatus.PROCESSING, null, false));

		poller.reconcile();

		verify(videoRepository).findByStatusAndProcessingStatus(VideoStatus.ACTIVE, ProcessingStatus.BLURRING);
		verify(aiClient).poll("job-1");
		verify(statusWriter, never()).markBlurFailed(anyLong(), any(), any());
		verify(statusWriter, never()).markBlurReady(eq(7L), anyString(), anyString(), anyString(), any());
	}
}
