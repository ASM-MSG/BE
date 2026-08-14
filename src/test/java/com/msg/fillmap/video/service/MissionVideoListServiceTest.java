package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import com.msg.fillmap.badge.service.BadgeAwardService;
import com.msg.fillmap.friend.service.FriendshipQueryService;
import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.hotzone.service.HotScoreCommandService;
import com.msg.fillmap.mission.service.MissionAwardService;
import com.msg.fillmap.region.service.RegionStatsCommandService;
import com.msg.fillmap.streak.service.StreakCommandService;
import com.msg.fillmap.video.dto.GridGlobalVideoResponseDto;
import com.msg.fillmap.video.dto.GridVideoPageResponseDto;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.exception.VideoErrorCode;
import com.msg.fillmap.video.repository.AuthorNicknameProjection;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.MissionVideoCursor;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;
import com.msg.fillmap.zone.service.ZoneNameResolver;

/**
 * 미션 영상 목록 페이지 조립 (MSG-390). 후보 술어·정렬은 repository 계약이라 MissionVideoListQueryTest 가
 * 실 DB 로 검증하고, 여기서는 서비스 몫인 size 클램프·lookahead 트림·nextCursor 발급·탈퇴 작성자 숨김·
 * 커서 400 변환만 본다 — 격자 전역 목록(VideoGlobalListServiceTest) 과 같은 전략이다.
 */
@DisplayName("VideoService 미션 영상 목록 조회")
class MissionVideoListServiceTest {

	private static final long MISSION_ID = 12L;
	private static final String GRID_ID = "19422_9582";

	private VideoRepository videoRepository;
	private VideoService videoService;

	@BeforeEach
	void setUp() {
		videoRepository = mock(VideoRepository.class);
		S3Presigner presigner = S3Presigner.builder()
			.region(Region.AP_NORTHEAST_2)
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("ak", "sk")))
			.build();
		AwsProperties properties = new AwsProperties(
			"ap-northeast-2", new AwsProperties.S3("fillmap-video-dev", 104857600L, 2147483648L));

		videoService = new VideoServiceImpl(
			videoRepository, mock(VideoEncodingService.class), mock(VideoStatusWriter.class),
			presigner, mock(S3Client.class), properties,
			mock(RegionStatsCommandService.class), new ThumbnailUrlPresigner(presigner, properties),
			mock(BadgeAwardService.class), mock(StreakCommandService.class), mock(MissionAwardService.class),
			mock(HotScoreCommandService.class), mock(FriendshipQueryService.class),
			() -> new ZoneNameResolver(List.of()), mock(VideoProcessingMetrics.class));

		// 기본값 = 요청한 작성자가 전부 살아 있다. 닉네임이 없으면 항목이 응답에서 빠지므로(MSG-371),
		// 닉네임을 안 보는 테스트도 이 기본 스텁이 있어야 항목을 받는다. 탈퇴 경합 테스트가 덮어쓴다.
		given(videoRepository.findAuthorNicknames(anyCollection())).willAnswer(invocation -> {
			Collection<Long> userIds = invocation.getArgument(0);
			return userIds.stream().map(id -> authorNickname(id, "user" + id)).toList();
		});
	}

	/** 후보 술어를 통과한(게이트는 repository 소관) READY 영상 픽스처 — 촬영 시각이 정렬 키다. */
	private Video readyVideo(long id, LocalDateTime recordedAt) {
		return readyVideo(id, 1L, recordedAt);
	}

	private Video readyVideo(long id, long authorId, LocalDateTime recordedAt) {
		Video video = Video.create(authorId, GRID_ID, "videos/original/" + id + ".mp4", null, (short) 12,
			recordedAt, Visibility.PRIVATE);
		video.markReady("videos/encoded/" + id + ".mp4", "videos/thumb/" + id + ".jpg");
		ReflectionTestUtils.setField(video, "id", id);
		return video;
	}

	private static LocalDateTime at(int day) {
		return LocalDateTime.of(2026, 7, day, 18, 3, 11);
	}

	private static String base64Url(String raw) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	/** Spring Data 인터페이스 프로젝션 stub (VideoGlobalListServiceTest 선례). */
	private static AuthorNicknameProjection authorNickname(Long userId, String nickname) {
		return new AuthorNicknameProjection() {
			@Override
			public Long getUserId() {
				return userId;
			}

			@Override
			public String getNickname() {
				return nickname;
			}
		};
	}

	/**
	 * 후보 집합 하나를 repository 두 메서드(첫 페이지·커서 이후)에 물려, 실제 keyset 페이징처럼 응답하게 한다.
	 * 정렬·경계 판정을 쿼리와 같은 규칙(recorded_at DESC, id DESC 전순서)으로 흉내 내 서비스의 순회를 본다.
	 */
	private void givenCandidates(List<Video> candidates) {
		List<Video> sorted = new ArrayList<>(candidates);
		sorted.sort(Comparator.comparing(Video::getRecordedAt).thenComparing(Video::getId).reversed());
		given(videoRepository.findMissionVideos(anyLong(), anyInt())).willAnswer(invocation ->
			sorted.stream().limit((int) (Integer) invocation.getArgument(1)).toList());
		given(videoRepository.findMissionVideosAfter(anyLong(), any(), anyLong(), anyInt()))
			.willAnswer(invocation -> {
				LocalDateTime boundaryRecordedAt = invocation.getArgument(1);
				long boundaryId = invocation.getArgument(2);
				int limit = invocation.getArgument(3);
				return sorted.stream()
					.filter(video -> video.getRecordedAt().isBefore(boundaryRecordedAt)
						|| (video.getRecordedAt().equals(boundaryRecordedAt) && video.getId() < boundaryId))
					.limit(limit)
					.toList();
			});
	}

	@Test
	@DisplayName("size 가 범위 밖이면 클램프된다 (0 은 20 으로, 100 은 50 으로)")
	void size가_범위_밖이면_클램프된다() {
		given(videoRepository.findMissionVideos(anyLong(), anyInt())).willReturn(List.of());

		videoService.getMissionVideos(MISSION_ID, null, 0);
		videoService.getMissionVideos(MISSION_ID, null, 100);

		then(videoRepository).should().findMissionVideos(MISSION_ID, 21);   // 기본 20 + lookahead 1
		then(videoRepository).should().findMissionVideos(MISSION_ID, 51);   // 상한 50 + lookahead 1
	}

	@Test
	@DisplayName("마지막 페이지는 hasNext 가 false 이고 nextCursor 가 null 이다")
	void 마지막_페이지는_hasNext가_false이고_nextCursor가_null이다() {
		givenCandidates(List.of(readyVideo(2L, at(20)), readyVideo(1L, at(19))));

		GridVideoPageResponseDto result = videoService.getMissionVideos(MISSION_ID, null, 20);

		assertThat(result.videos()).extracting(GridGlobalVideoResponseDto::videoId).containsExactly(2L, 1L);
		assertThat(result.hasNext()).isFalse();
		assertThat(result.nextCursor()).isNull();
	}

	// 검증: FR-MISSION-17
	@Test
	@DisplayName("커서로 이어받은 페이지에 중복도 누락도 없다")
	void 커서로_이어받은_페이지에_중복도_누락도_없다() {
		// 촬영 시각 동률(21일 2건)이 페이지 경계에 걸리도록 배치해 id 타이브레이크까지 함께 본다.
		givenCandidates(List.of(
			readyVideo(5L, at(22)),
			readyVideo(4L, at(21)),
			readyVideo(3L, at(21)),
			readyVideo(2L, at(20)),
			readyVideo(1L, at(19))));

		List<Long> traversed = new ArrayList<>();
		String cursor = null;
		while (true) {
			GridVideoPageResponseDto page = videoService.getMissionVideos(MISSION_ID, cursor, 2);
			page.videos().forEach(video -> traversed.add(video.videoId()));
			if (!page.hasNext()) {
				break;
			}
			cursor = page.nextCursor();
		}

		assertThat(traversed).containsExactly(5L, 4L, 3L, 2L, 1L);
	}

	@Test
	@DisplayName("탈퇴한 작성자의 항목은 빠진다 — 두 조회 사이 연쇄 삭제")
	void 탈퇴한_작성자의_항목은_빠진다() {
		givenCandidates(List.of(readyVideo(2L, 7L, at(20)), readyVideo(1L, 9L, at(19))));
		given(videoRepository.findAuthorNicknames(anyCollection()))
			.willReturn(List.of(authorNickname(9L, "seoul.walk")));

		GridVideoPageResponseDto result = videoService.getMissionVideos(MISSION_ID, null, 20);

		assertThat(result.videos())
			.extracting(GridGlobalVideoResponseDto::videoId, GridGlobalVideoResponseDto::nickname)
			.containsExactly(tuple(1L, "seoul.walk"));
	}

	@Test
	@DisplayName("탈퇴 항목이 페이지 끝이어도 커서는 트림 전 마지막 행 기준으로 발급된다 (무한 첫 페이지 방지)")
	void 탈퇴_항목이_페이지_끝이어도_커서는_트림_전_마지막_행_기준으로_발급된다() {
		givenCandidates(List.of(
			readyVideo(3L, 9L, at(22)),
			readyVideo(2L, 7L, at(21)),    // 작성자 탈퇴 — 응답에서 숨겨지는 페이지 끝 행
			readyVideo(1L, 9L, at(20))));
		given(videoRepository.findAuthorNicknames(anyCollection()))
			.willReturn(List.of(authorNickname(9L, "seoul.walk")));

		GridVideoPageResponseDto result = videoService.getMissionVideos(MISSION_ID, null, 2);

		assertThat(result.videos()).extracting(GridGlobalVideoResponseDto::videoId).containsExactly(3L);
		assertThat(result.hasNext()).isTrue();
		// 숨긴 행(id=2) 기준이라야 다음 페이지가 그 자리에서 이어진다 — 보이는 마지막(id=3) 기준이면 제자리다.
		assertThat(MissionVideoCursor.decode(result.nextCursor()).id()).isEqualTo(2L);
	}

	@Test
	@DisplayName("닉네임 조회는 페이지 크기와 무관하게 한 번이다 (N+1 금지)")
	void 닉네임_조회는_페이지_크기와_무관하게_한_번이다() {
		givenCandidates(List.of(
			readyVideo(3L, 7L, at(22)),
			readyVideo(2L, 9L, at(21)),
			readyVideo(1L, 99L, at(20))));   // lookahead 초과분 — 트림돼 배치에 들어가지 않는다

		videoService.getMissionVideos(MISSION_ID, null, 2);

		ArgumentCaptor<Collection<Long>> userIds = ArgumentCaptor.forClass(Collection.class);
		then(videoRepository).should(times(1)).findAuthorNicknames(userIds.capture());
		assertThat(userIds.getValue()).containsExactlyInAnyOrder(7L, 9L);
	}

	@Test
	@DisplayName("무효 커서는 INVALID_CURSOR 로 변환된다 (400, developCode 3423)")
	void 무효_커서는_INVALID_CURSOR로_변환된다() {
		List<String> badCursors = List.of(
			"!!!not-base64!!!",
			base64Url("12:1784455800000000"),                   // 성분 수 위반 (2성분)
			base64Url("19422_9582:5:1784455800000000:1039"),    // 격자 목록의 4성분 커서
			base64Url("12:a:1039"));                            // 정수 아님

		for (String bad : badCursors) {
			assertThatThrownBy(() -> videoService.getMissionVideos(MISSION_ID, bad, 20))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.INVALID_CURSOR);
		}
		assertThat(VideoErrorCode.INVALID_CURSOR.getErrorCode()).isEqualTo(3423);
		assertThat(VideoErrorCode.INVALID_CURSOR.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("다른 미션에서 발급된 커서도 INVALID_CURSOR 다 (발급 미션 바인딩)")
	void 다른_미션에서_발급된_커서도_INVALID_CURSOR다() {
		// 다른 미션의 경계값이 이 미션의 keyset 에 오적용되면 결과가 조용히 잘린다 — 형식 위반과 같은 400.
		String foreignCursor = MissionVideoCursor.encode(MISSION_ID + 1, at(20), 1039L);

		assertThatThrownBy(() -> videoService.getMissionVideos(MISSION_ID, foreignCursor, 20))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.INVALID_CURSOR);
	}

	@Test
	@DisplayName("시각 성분이 저장 가능 범위 밖인 커서는 INVALID_CURSOR 다 (500 이 아니라 400)")
	void 시각_성분이_저장_가능_범위_밖인_커서는_INVALID_CURSOR다() {
		// Long.MIN_VALUE µs = ISO 연도 -290308. LocalDateTime 은 받아들이지만 PostgreSQL 은 못 담아,
		// 막지 않으면 쿼리 바인딩 단계에서 깨져 디코드 try-catch 밖의 공통 500 이 된다.
		String outOfRange = base64Url(MISSION_ID + ":" + Long.MIN_VALUE + ":1039");

		assertThatThrownBy(() -> videoService.getMissionVideos(MISSION_ID, outOfRange, 20))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.INVALID_CURSOR);
		// DB 까지 흘러가지 않는다 — 커서 조회 자체가 시도되지 않는다(바인딩 실패 500 의 원천 차단).
		then(videoRepository).should(never()).findMissionVideosAfter(anyLong(), any(), anyLong(), anyInt());
	}

	@Test
	@DisplayName("Long.MAX_VALUE 마이크로초를 실은 커서는 거부되지 않는다 (상한을 좁히는 회귀 차단)")
	void Long_MAX_VALUE_마이크로초를_실은_커서는_거부되지_않는다() {
		// 서기 294247년이라 PostgreSQL 저장 가능 범위 안이다 — 수용 범위는 발급 가능 범위 이상이어야 한다.
		givenCandidates(List.of(readyVideo(1L, at(19))));
		String upperEdge = base64Url(MISSION_ID + ":" + Long.MAX_VALUE + ":1039");

		assertThatCode(() -> videoService.getMissionVideos(MISSION_ID, upperEdge, 20)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("발급된 커서는 언제나 다시 받아들여진다 (아주 오래된 촬영 시각 포함)")
	void 발급된_커서는_언제나_다시_받아들여진다() {
		// 업로드 확정은 미래만 막고 과거 하한이 없어 아주 오래된 recorded_at 도 저장될 수 있다.
		// 그런 행이 페이지 경계가 되면 서버가 그 값으로 커서를 발급하므로, 다시 받지 못하면 2페이지가 막힌다.
		LocalDateTime ancient = LocalDateTime.of(-4712, 1, 1, 0, 0);   // PostgreSQL 저장 하한 그 값
		givenCandidates(List.of(
			readyVideo(3L, at(22)),
			readyVideo(2L, ancient),
			readyVideo(1L, ancient)));

		GridVideoPageResponseDto firstPage = videoService.getMissionVideos(MISSION_ID, null, 2);

		assertThat(firstPage.hasNext()).isTrue();
		assertThat(MissionVideoCursor.decode(firstPage.nextCursor()).recordedAt()).isEqualTo(ancient);
		assertThatCode(() -> videoService.getMissionVideos(MISSION_ID, firstPage.nextCursor(), 2))
			.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("각 항목의 썸네일은 presigned GET URL 로 발급된다")
	void 각_항목의_썸네일은_presigned_GET_URL로_발급된다() {
		givenCandidates(List.of(readyVideo(1042L, at(20))));

		GridGlobalVideoResponseDto item = videoService.getMissionVideos(MISSION_ID, null, 20).videos().get(0);

		assertThat(item.videoId()).isEqualTo(1042L);
		assertThat(item.recordedAt()).isEqualTo(at(20));
		assertThat(item.thumbnailUrl())
			.startsWith("https://")
			.contains("videos/thumb/1042.jpg")
			.contains("X-Amz-Signature");
	}

	@Test
	@DisplayName("조건에 맞는 영상이 없으면 빈 페이지다 (예외 아님)")
	void 조건에_맞는_영상이_없으면_빈_페이지다() {
		given(videoRepository.findMissionVideos(anyLong(), anyInt())).willReturn(List.of());

		GridVideoPageResponseDto result = videoService.getMissionVideos(MISSION_ID, null, 20);

		assertThat(result.videos()).isEmpty();
		assertThat(result.hasNext()).isFalse();
		assertThat(result.nextCursor()).isNull();
	}
}
