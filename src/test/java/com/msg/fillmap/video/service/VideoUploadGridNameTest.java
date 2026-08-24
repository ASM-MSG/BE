package com.msg.fillmap.video.service;

import static com.msg.fillmap.video.support.S3VideoObjectStub.givenUploadedVideoObject;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import com.msg.fillmap.badge.service.BadgeAwardService;
import com.msg.fillmap.event.repository.EventVideoRepository;
import com.msg.fillmap.friend.service.FriendshipQueryService;
import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.hotzone.service.HotScoreCommandService;
import com.msg.fillmap.mission.dto.MissionAwardResult;
import com.msg.fillmap.mission.service.MissionAwardService;
import com.msg.fillmap.region.service.RegionStatsCommandService;
import com.msg.fillmap.streak.service.StreakCommandService;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.dto.VideoUploadResponseDto;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;
import com.msg.fillmap.zone.entity.Zone;
import com.msg.fillmap.zone.service.ZoneNameResolver;

/**
 * 업로드 확정 응답의 격자 표시명 배선 (MSG-341). 업로드 직후 화면이 "어디를 채웠는지"를 이름으로 보여주려면
 * 확정 응답이 zoneName·zoneCell·regionName 을 실어야 한다. 명명 산술 자체의 정본은 픽스처 계약 테스트라
 * 여기서는 배선만 본다 — 구역은 gridId 산술로 오고, 행정동은 좌표 재판정이 아니라 upsertGrid 가 방금 저장한
 * 라벨을 읽는 조회(D-6)로 온다. 리졸버는 목이 아니라 실물이라 배선이 어긋나면 값이 그대로 틀린다.
 * 트랜잭션 없이 돌리므로 afterCommit 폴백이 즉시 실행된다 (VideoHotScoreTest 방식).
 */
@DisplayName("업로드 확정 응답 — 격자 표시명")
class VideoUploadGridNameTest {

	private static final long USER_ID = 1L;
	private static final long VIDEO_ID = 7L;
	// 37.5445, 127.0560 의 격자 — VideoHotScoreTest 와 동일 좌표.
	private static final String GRID_ID = "19495_9607";
	/** GRID_ID 를 덮는 구역 — 행 = 'A' + (19499 − 19495) = 'E', 열 = 9607 − 9604 + 1 = 4. */
	private static final Zone SEOMYEON = Zone.builder()
		.zoneKey("seomyeon").name("서면")
		.minGridY(19489).maxGridY(19499).minGridX(9604).maxGridX(9614)
		.priority(0)
		.build();
	/** GRID_ID 를 벗어난 구역 — 어느 사각형에도 안 들면 두 필드가 null 이어야 한다. */
	private static final Zone ELSEWHERE = Zone.builder()
		.zoneKey("elsewhere").name("딴구역")
		.minGridY(16667).maxGridY(16681).minGridX(11591).maxGridX(11606)
		.priority(0)
		.build();

	private VideoRepository repository;

	@BeforeEach
	void setUp() {
		repository = mock(VideoRepository.class);
		Video saved = Video.create(USER_ID, GRID_ID, "videos/original/1/x.mp4", null, (short) 10,
			LocalDateTime.now(ZoneOffset.UTC), Visibility.PRIVATE);
		ReflectionTestUtils.setField(saved, "id", VIDEO_ID);
		given(repository.saveAndFlush(any(Video.class))).willReturn(saved);
	}

	private VideoService serviceWithZones(Zone... zones) {
		MissionAwardService missionAwardService = mock(MissionAwardService.class);
		given(missionAwardService.awardOnUpload(anyLong(), anyString())).willReturn(MissionAwardResult.EMPTY);
		S3Client s3Client = mock(S3Client.class);
		// 확정의 실측 크기 검증(MSG-351 P1-1)이 headObject 응답을 읽는다 — 스텁이 없으면 null 로 NPE.
		givenUploadedVideoObject(s3Client);
		return new VideoServiceImpl(repository, mock(VideoEncodingService.class), mock(VideoStatusWriter.class),
			mock(S3Presigner.class), s3Client,
			new AwsProperties("ap-northeast-2", new AwsProperties.S3("fillmap-video-dev", 104857600L, 2147483648L)),
			mock(RegionStatsCommandService.class), mock(ThumbnailUrlPresigner.class), mock(BadgeAwardService.class),
			mock(StreakCommandService.class), missionAwardService, mock(HotScoreCommandService.class),
			mock(FriendshipQueryService.class), () -> new ZoneNameResolver(List.of(zones)),
			mock(VideoProcessingMetrics.class), mock(EventVideoRepository.class));
	}

	private VideoUploadRequestDto uploadRequest() {
		return new VideoUploadRequestDto("videos/pending/1/x.mp4", 37.5445, 127.0560, (short) 10,
			LocalDateTime.now(ZoneOffset.UTC), "PRIVATE");
	}

	@Test
	@DisplayName("업로드 확정 응답에 구역 이름과 행정동 이름이 붙는다")
	void 업로드_확정_응답에_구역_이름과_행정동_이름이_붙는다() {
		// 신규 격자 첫 업로드(existsUserGrid=false 기본값)에서도 실린다 — upsertGrid 의 INSERT 가 라벨을 이미
		// 저장했으므로 같은 트랜잭션에서 바로 읽힌다 (D-6).
		given(repository.findRegionNameByGridId(GRID_ID)).willReturn(Optional.of("서울특별시 강남구 역삼1동"));

		VideoUploadResponseDto response = serviceWithZones(SEOMYEON).saveVideo(USER_ID, uploadRequest());

		assertThat(response.occupied()).isTrue();
		assertThat(response.zoneName()).isEqualTo("서면");
		assertThat(response.zoneCell()).isEqualTo("E-4");
		assertThat(response.regionName()).isEqualTo("서울특별시 강남구 역삼1동");
	}

	@Test
	@DisplayName("구역 밖 격자는 두 필드가 모두 null이다")
	void 구역_밖_격자는_두_필드가_모두_null이다() {
		given(repository.findRegionNameByGridId(GRID_ID)).willReturn(Optional.of("서울특별시 강남구 역삼1동"));

		VideoUploadResponseDto response = serviceWithZones(ELSEWHERE).saveVideo(USER_ID, uploadRequest());

		// 쌍이라 하나만 null 인 상태가 없다 — FE 는 zoneName 유무만 보고 regionName 폴백으로 분기한다.
		assertThat(response.zoneName()).isNull();
		assertThat(response.zoneCell()).isNull();
		assertThat(response.regionName()).isEqualTo("서울특별시 강남구 역삼1동");
	}

	@Test
	@DisplayName("무귀속 격자의 업로드 확정 응답은 regionName이 null이다")
	void 무귀속_격자의_업로드_확정_응답은_regionName이_null이다() {
		// 해상 등 저장 라벨이 없는 격자 — 조회 empty 는 예외가 아니라 null 필드다 (D-6).
		given(repository.findRegionNameByGridId(GRID_ID)).willReturn(Optional.empty());

		VideoUploadResponseDto response = serviceWithZones(SEOMYEON).saveVideo(USER_ID, uploadRequest());

		assertThat(response.regionName()).isNull();
		assertThat(response.zoneName()).isEqualTo("서면");   // 구역은 산술이라 행정동 라벨 유무와 무관하다
	}
}
