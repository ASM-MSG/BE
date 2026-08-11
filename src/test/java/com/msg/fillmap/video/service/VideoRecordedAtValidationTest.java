package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.msg.fillmap.badge.service.BadgeAwardService;
import com.msg.fillmap.friend.service.FriendshipQueryService;
import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.hotzone.service.HotScoreCommandService;
import com.msg.fillmap.mission.dto.MissionAwardResult;
import com.msg.fillmap.mission.service.MissionAwardService;
import com.msg.fillmap.region.service.RegionStatsCommandService;
import com.msg.fillmap.streak.service.StreakCommandService;
import com.msg.fillmap.video.dto.VideoReplaceRequestDto;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.exception.VideoErrorCode;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;
import com.msg.fillmap.zone.service.ZoneNameResolver;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * recordedAt 미래 시각 검증 (MSG-278). 클라 신고값 recordedAt 이 서버 UTC now + 5분을 초과하면
 * 3424 로 거부한다 — 미션 판정(findCompleted)이 이 값을 기간과 비교하므로 트리비얼 위조를 입력
 * 경계에서 막는다. 비교 기준은 UTC 고정 클럭 — KST 기본존이면 9시간 스큐로 판정이 갈린다(MSG-222).
 */
// 검증: FR-VIDEO-07
@DisplayName("recordedAt 미래 시각 검증 — 업로드·교체")
class VideoRecordedAtValidationTest {

	private static final long USER_ID = 1L;
	private static final Instant FIXED_NOW = Instant.parse("2026-08-03T12:00:00Z");
	private static final LocalDateTime UTC_NOW = LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);

	private VideoRepository repository;
	private S3Client s3Client;
	private VideoService service;

	@BeforeEach
	void setUp() {
		repository = mock(VideoRepository.class);
		s3Client = mock(S3Client.class);
		// 확정의 실측 크기 검증(MSG-351 P1-1)이 headObject 응답을 읽는다 — 스텁이 없으면 null 로 NPE.
		given(s3Client.headObject(any(HeadObjectRequest.class))).willReturn(HeadObjectResponse.builder().build());
		// 미션 판정 목 — record 반환 타입은 Mockito 기본값이 null 이라 EMPTY 를 명시한다 (MSG-223).
		MissionAwardService missionAwardService = mock(MissionAwardService.class);
		given(missionAwardService.awardOnUpload(anyLong(), anyString())).willReturn(MissionAwardResult.EMPTY);

		// 13번째 인자가 고정 클럭 — 프로덕션은 12-인자 @Autowired 생성자가 Clock.systemUTC() 로 위임한다.
		service = new VideoServiceImpl(repository, mock(VideoEncodingService.class), mock(VideoStatusWriter.class),
			mock(S3Presigner.class), s3Client,
			new AwsProperties("ap-northeast-2", new AwsProperties.S3("fillmap-video-dev", 104857600L, 2147483648L)),
			mock(RegionStatsCommandService.class), mock(ThumbnailUrlPresigner.class), mock(BadgeAwardService.class),
			mock(StreakCommandService.class), missionAwardService, mock(HotScoreCommandService.class),
			mock(FriendshipQueryService.class), () -> new ZoneNameResolver(List.of()),
			Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
	}

	@Test
	void 서버_시각_5분_초과_미래_recordedAt_업로드는_거부된다() {
		assertThatThrownBy(() -> service.saveVideo(USER_ID, uploadRequest(UTC_NOW.plusMinutes(6))))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.RECORDED_AT_IN_FUTURE);

		assertThat(VideoErrorCode.RECORDED_AT_IN_FUTURE.getErrorCode()).isEqualTo(3424);
		assertThat(VideoErrorCode.RECORDED_AT_IN_FUTURE.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void 교체_요청의_미래_recordedAt도_동일하게_거부된다() {
		Video owned = Video.create(USER_ID, "19495_9607", "videos/original/1/old.mp4", null, (short) 10,
			UTC_NOW.minusDays(1), Visibility.PRIVATE);
		given(repository.findById(7L)).willReturn(Optional.of(owned));

		VideoReplaceRequestDto request = new VideoReplaceRequestDto(
			"videos/pending/1/new.mp4", null, null, (short) 7, UTC_NOW.plusMinutes(6));

		assertThatThrownBy(() -> service.replaceVideo(USER_ID, 7L, request))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.RECORDED_AT_IN_FUTURE);
		// confirmUpload 전 거부 — 교체 경로도 S3 부수효과(advisory lock·headObject·복사)가 없다.
		verifyNoInteractions(s3Client);
	}

	@Test
	void 정확히_5분_미래는_통과한다() {
		givenSavedVideo();

		assertThatCode(() -> service.saveVideo(USER_ID, uploadRequest(UTC_NOW.plusMinutes(5))))
			.doesNotThrowAnyException();
	}

	@Test
	void 오분_일초_미래는_거부된다() {
		assertThatThrownBy(() -> service.saveVideo(USER_ID, uploadRequest(UTC_NOW.plusMinutes(5).plusSeconds(1))))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.RECORDED_AT_IN_FUTURE);
	}

	@Test
	void 과거_recordedAt은_통과한다() {
		givenSavedVideo();

		// 갤러리 방문(과거 촬영)은 얼마나 오래됐든 통과 — 도감 수집 불변 (FR-5).
		assertThatCode(() -> service.saveVideo(USER_ID, uploadRequest(UTC_NOW.minusYears(1))))
			.doesNotThrowAnyException();
	}

	@Test
	void 거부되면_S3_확정과_저장이_실행되지_않는다() {
		assertThatThrownBy(() -> service.saveVideo(USER_ID, uploadRequest(UTC_NOW.plusMinutes(6))))
			.isInstanceOf(ApiException.class);

		verifyNoInteractions(s3Client, repository);
	}

	private VideoUploadRequestDto uploadRequest(LocalDateTime recordedAt) {
		return new VideoUploadRequestDto("videos/pending/1/x.mp4", 37.5445, 127.0560, (short) 10, recordedAt,
			"PRIVATE");
	}

	/** 통과 케이스만 저장 이후 흐름을 지난다 — saveAndFlush 스텁이 필요한 쪽에서만 부른다. */
	private void givenSavedVideo() {
		Video saved = Video.create(USER_ID, "19495_9607", "videos/original/1/x.mp4", null, (short) 10,
			UTC_NOW.minusDays(1), Visibility.PRIVATE);
		ReflectionTestUtils.setField(saved, "id", 7L);
		given(repository.saveAndFlush(any(Video.class))).willReturn(saved);
	}
}
