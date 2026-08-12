package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Collection;
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
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;
import com.msg.fillmap.video.support.VideoCursor;
import com.msg.fillmap.zone.service.ZoneNameResolver;

/**
 * 전역 목록 조회 + 커서/클램프/presign (MSG-237). 필터·정렬은 repository 계약이라 VideoGlobalListQueryTest 가
 * 실 DB 로 검증하고, 여기서는 서비스의 size 클램프·lookahead 트림·nextCursor 발급·무효 커서 400·썸네일
 * presign 매핑만 본다. presign 은 네트워크 없는 로컬 서명이라 더미 자격증명으로 실제 S3Presigner 를 쓴다
 * (VideoGlobalCoverServiceTest 전략).
 */
@DisplayName("VideoService 격자 전역 영상 목록 조회")
class VideoGlobalListServiceTest {

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
			mock(HotScoreCommandService.class), mock(FriendshipQueryService.class), () -> new ZoneNameResolver(List.of()));

		// 기본값 = 요청한 작성자가 전부 살아 있다(탈퇴 CASCADE 로 정상 경로엔 빈손이 없다). 닉네임이 없으면
		// 항목이 응답에서 빠지므로(MSG-371), 닉네임을 안 보는 테스트도 이 기본 스텁이 있어야 항목을 받는다.
		// 탈퇴 경합을 보는 테스트는 각자 given 으로 덮어쓴다.
		given(videoRepository.findAuthorNicknames(anyCollection())).willAnswer(invocation -> {
			Collection<Long> userIds = invocation.getArgument(0);
			return userIds.stream().map(id -> authorNickname(id, "user" + id)).toList();
		});
	}

	/** 목록 후보 조건(PUBLIC 는 repository 필터 소관)·READY·썸네일 key 를 갖춘 픽스처 — 정상 경로 기준. */
	private Video readyVideo(long id, long viewCount, LocalDateTime createdAt) {
		return readyVideo(id, 1L, viewCount, createdAt);
	}

	/** 작성자를 지정하는 오버로드 — 한 페이지에 작성자가 섞인 상황(닉네임 교차 매핑)을 만든다. */
	private Video readyVideo(long id, long authorId, long viewCount, LocalDateTime createdAt) {
		Video video = Video.create(authorId, GRID_ID, "videos/original/" + id + ".mp4", null, (short) 12,
			LocalDateTime.of(2026, 7, 20, 18, 3, 11), Visibility.PRIVATE);
		video.markReady("videos/encoded/" + id + ".mp4", "videos/thumb/" + id + ".jpg");
		ReflectionTestUtils.setField(video, "id", id);
		ReflectionTestUtils.setField(video, "viewCount", viewCount);
		ReflectionTestUtils.setField(video, "createdAt", createdAt);   // 생성자 시각을 검증용 고정값으로 교체
		return video;
	}

	private static LocalDateTime at(int hour) {
		return LocalDateTime.of(2026, 7, 20, hour, 0, 0);
	}

	private static String base64Url(String raw) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("각 항목의 썸네일은 presigned GET URL 로 발급된다")
	void 각_항목의_썸네일은_presigned_GET_URL로_발급된다() {
		given(videoRepository.findGlobalVideos(GRID_ID, 21))
			.willReturn(List.of(readyVideo(1042L, 37L, at(10))));

		GridVideoPageResponseDto result = videoService.getGridGlobalVideos(GRID_ID, null, 20);

		assertThat(result.videos()).hasSize(1);
		GridGlobalVideoResponseDto item = result.videos().get(0);
		assertThat(item.videoId()).isEqualTo(1042L);
		assertThat(item.viewCount()).isEqualTo(37L);
		assertThat(item.recordedAt()).isEqualTo(LocalDateTime.of(2026, 7, 20, 18, 3, 11));
		assertThat(item.thumbnailUrl())
			.startsWith("https://")
			.contains("fillmap-video-dev")
			.contains("videos/thumb/1042.jpg");
	}

	@Test
	@DisplayName("발급된 썸네일 URL 은 서명파라미터를 포함한다")
	void 발급된_썸네일_URL은_서명파라미터를_포함한다() {
		given(videoRepository.findGlobalVideos(GRID_ID, 21))
			.willReturn(List.of(readyVideo(1042L, 37L, at(10))));

		String thumbnailUrl = videoService.getGridGlobalVideos(GRID_ID, null, 20).videos().get(0).thumbnailUrl();

		assertThat(thumbnailUrl)
			.contains("X-Amz-Algorithm")
			.contains("X-Amz-Signature")
			.contains("X-Amz-Expires");
	}

	@Test
	@DisplayName("다음 페이지가 있으면 hasNext 는 true 이고 nextCursor 가 발급된다")
	void 다음_페이지가_있으면_hasNext는_true이고_nextCursor가_발급된다() {
		LocalDateTime boundary = at(9);
		given(videoRepository.findGlobalVideos(GRID_ID, 3)).willReturn(List.of(
			readyVideo(3L, 30L, at(12)),
			readyVideo(2L, 20L, boundary),
			readyVideo(1L, 10L, at(8))));   // lookahead 초과분 — 응답에선 잘린다

		GridVideoPageResponseDto result = videoService.getGridGlobalVideos(GRID_ID, null, 2);

		assertThat(result.hasNext()).isTrue();
		assertThat(result.videos()).extracting(GridGlobalVideoResponseDto::videoId).containsExactly(3L, 2L);
		// nextCursor 는 트림된 마지막 항목(id=2)의 경계값 — 라운드트립으로 §D2 와이어 포맷(4성분) 대칭을 확인한다.
		assertThat(VideoCursor.decode(result.nextCursor())).isEqualTo(new VideoCursor(GRID_ID, 20L, boundary, 2L));

		// 발급된 커서를 그대로 되돌려주면 keyset After 조회로 이어진다.
		given(videoRepository.findGlobalVideosAfter(GRID_ID, 20L, boundary, 2L, 3))
			.willReturn(List.of(readyVideo(1L, 10L, at(8))));
		GridVideoPageResponseDto nextPage = videoService.getGridGlobalVideos(GRID_ID, result.nextCursor(), 2);
		assertThat(nextPage.videos()).extracting(GridGlobalVideoResponseDto::videoId).containsExactly(1L);
		assertThat(nextPage.hasNext()).isFalse();
	}

	@Test
	@DisplayName("마지막 페이지면 hasNext 는 false 이고 nextCursor 는 null 이다")
	void 마지막_페이지면_hasNext는_false이고_nextCursor는_null이다() {
		given(videoRepository.findGlobalVideos(GRID_ID, 21))
			.willReturn(List.of(readyVideo(1042L, 37L, at(10))));

		GridVideoPageResponseDto result = videoService.getGridGlobalVideos(GRID_ID, null, 20);

		assertThat(result.videos()).hasSize(1);
		assertThat(result.hasNext()).isFalse();
		assertThat(result.nextCursor()).isNull();
	}

	@Test
	@DisplayName("size 가 상한을 초과하면 상한으로 클램프된다 (§D5)")
	void size가_상한을_초과하면_상한으로_클램프된다() {
		given(videoRepository.findGlobalVideos(GRID_ID, 51)).willReturn(List.of());

		videoService.getGridGlobalVideos(GRID_ID, null, 999);

		then(videoRepository).should().findGlobalVideos(GRID_ID, 51);   // 상한 50 + lookahead 1
	}

	@Test
	@DisplayName("size 가 0 이하면 기본값으로 보정된다 (§D5)")
	void size가_0이하면_기본값으로_보정된다() {
		given(videoRepository.findGlobalVideos(GRID_ID, 21)).willReturn(List.of());

		videoService.getGridGlobalVideos(GRID_ID, null, 0);

		then(videoRepository).should().findGlobalVideos(GRID_ID, 21);   // 기본 20 + lookahead 1
	}

	@Test
	@DisplayName("무효 커서는 400 INVALID_CURSOR 다 (developCode 3423)")
	void 무효_커서는_400_INVALID_CURSOR다() {
		List<String> badCursors = List.of(
			"!!!not-base64!!!",
			base64Url("1:2"),                            // 필드 수 위반 (2필드)
			base64Url("5:1784455800000000:1039"),        // 필드 수 위반 — gridId 없는 구 3필드 포맷도 무효다
			base64Url(GRID_ID + ":a:b:c"));              // 타입 위반 (정수 아님)

		for (String bad : badCursors) {
			assertThatThrownBy(() -> videoService.getGridGlobalVideos(GRID_ID, bad, 20))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.INVALID_CURSOR);
		}
		assertThat(VideoErrorCode.INVALID_CURSOR.getErrorCode()).isEqualTo(3423);
		assertThat(VideoErrorCode.INVALID_CURSOR.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("다른 격자에서 발급된 커서는 400 INVALID_CURSOR 다 (커서 gridId 바인딩)")
	void 다른_격자에서_발급된_커서는_400_INVALID_CURSOR다() {
		// 격자 A 의 커서를 격자 B 요청에 재사용하면 경계값이 B 의 keyset 으로 오적용돼 결과가 조용히
		// 잘린다 — 커서의 gridId 성분을 요청 격자와 대조해 거부한다 (2026-07-28 Codex 교차 리뷰 P2).
		String foreignCursor = VideoCursor.encode("18784_9109", 20L, at(9), 2L);

		assertThatThrownBy(() -> videoService.getGridGlobalVideos(GRID_ID, foreignCursor, 20))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.INVALID_CURSOR);
		assertThat(VideoErrorCode.INVALID_CURSOR.getErrorCode()).isEqualTo(3423);
	}

	@Test
	@DisplayName("결과가 size 로 나누어떨어지면 마지막 페이지에서 hasNext false 다 (정확 경계 lookahead 회귀)")
	void 결과가_size로_나누어떨어지면_마지막_페이지에서_hasNext_false다() {
		// lookahead(size+1) 요청에 정확히 size 행만 오는 경계 — MSG-90 이 잡았던 회귀 지점이다.
		given(videoRepository.findGlobalVideos(GRID_ID, 3)).willReturn(List.of(
			readyVideo(2L, 20L, at(10)),
			readyVideo(1L, 10L, at(9))));

		GridVideoPageResponseDto result = videoService.getGridGlobalVideos(GRID_ID, null, 2);

		assertThat(result.videos()).hasSize(2);
		assertThat(result.hasNext()).isFalse();
		assertThat(result.nextCursor()).isNull();
	}

	@Test
	@DisplayName("공개 READY 가 없는 격자를 조회하면 빈 페이지를 반환한다 (예외 아님)")
	void 공개_READY가_없는_격자를_조회하면_빈_페이지를_반환한다() {
		given(videoRepository.findGlobalVideos(GRID_ID, 21)).willReturn(List.of());

		GridVideoPageResponseDto result = videoService.getGridGlobalVideos(GRID_ID, null, 20);

		assertThat(result.videos()).isEmpty();
		assertThat(result.hasNext()).isFalse();
		assertThat(result.nextCursor()).isNull();
		// 빈 페이지는 닉네임 배치 조회 자체를 건너뛴다 — 조회할 작성자가 없다.
		then(videoRepository).should(never()).findAuthorNicknames(anyCollection());
	}

	// --- 작성자 닉네임 (MSG-371). 조달은 native 목록 쿼리 무변경 + 배치 IN 1회다.

	/** Spring Data 인터페이스 프로젝션 stub (RegionExploreServiceTest 선례). */
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

	@Test
	@DisplayName("전역 목록 각 항목에 작성자 자신의 닉네임이 매핑된다")
	void 전역_목록_각_항목에_작성자_자신의_닉네임이_매핑된다() {
		// 작성자 2명을 섞어 교차 매핑을 본다 — 배치 결과가 항목 순서가 아니라 userId 로 되짚어지는지가 요점.
		given(videoRepository.findGlobalVideos(GRID_ID, 21)).willReturn(List.of(
			readyVideo(3L, 7L, 30L, at(12)),
			readyVideo(2L, 9L, 20L, at(11)),
			readyVideo(1L, 7L, 10L, at(10))));
		given(videoRepository.findAuthorNicknames(anyCollection()))
			.willReturn(List.of(authorNickname(9L, "seoul.walk"), authorNickname(7L, "busan.vlog")));

		GridVideoPageResponseDto result = videoService.getGridGlobalVideos(GRID_ID, null, 20);

		assertThat(result.videos())
			.extracting(GridGlobalVideoResponseDto::videoId, GridGlobalVideoResponseDto::nickname)
			.containsExactly(
				tuple(3L, "busan.vlog"),
				tuple(2L, "seoul.walk"),
				tuple(1L, "busan.vlog"));
	}

	@Test
	@DisplayName("닉네임 조회는 페이지당 배치 1회다 (트림된 lookahead 행 작성자는 빠진다)")
	void 닉네임_조회는_페이지당_배치_1회다() {
		given(videoRepository.findGlobalVideos(GRID_ID, 3)).willReturn(List.of(
			readyVideo(3L, 7L, 30L, at(12)),
			readyVideo(2L, 9L, 20L, at(11)),
			readyVideo(1L, 99L, 10L, at(10))));   // lookahead 초과분 — 응답에서 잘리는 행
		given(videoRepository.findAuthorNicknames(anyCollection()))
			.willReturn(List.of(authorNickname(7L, "busan.vlog"), authorNickname(9L, "seoul.walk")));

		videoService.getGridGlobalVideos(GRID_ID, null, 2);

		ArgumentCaptor<Collection<Long>> userIds = ArgumentCaptor.forClass(Collection.class);
		// 항목 수만큼이 아니라 정확히 1회 — 페이지가 커져도 왕복이 늘지 않는다(N+1 금지).
		then(videoRepository).should(times(1)).findAuthorNicknames(userIds.capture());
		// 트림돼 버려지는 행의 작성자(99)는 배치에 들어가지 않는다.
		assertThat(userIds.getValue()).containsExactlyInAnyOrder(7L, 9L);
	}

	@Test
	@DisplayName("작성자가 배치 결과에 없는 항목은 응답에서 빠진다 — 방금 연쇄 삭제된 영상")
	void 작성자가_배치_결과에_없는_항목은_응답에서_빠진다() {
		// 목록 조회와 닉네임 조회 사이(READ COMMITTED, ms 창)에 탈퇴 커밋이 끼는 이론상 케이스.
		// 닉네임이 빈손 = 그 영상이 방금 CASCADE 로 사라졌다는 뜻이라 숨긴다 — null 을 싣지 않는다.
		given(videoRepository.findGlobalVideos(GRID_ID, 21)).willReturn(List.of(
			readyVideo(3L, 7L, 30L, at(12)),
			readyVideo(2L, 9L, 20L, at(11))));
		given(videoRepository.findAuthorNicknames(anyCollection()))
			.willReturn(List.of(authorNickname(9L, "seoul.walk")));

		GridVideoPageResponseDto result = videoService.getGridGlobalVideos(GRID_ID, null, 20);

		// 살아있는 작성자의 항목만 남고, 사라진 작성자(7)의 항목은 통째로 빠진다.
		assertThat(result.videos())
			.extracting(GridGlobalVideoResponseDto::videoId, GridGlobalVideoResponseDto::nickname)
			.containsExactly(tuple(2L, "seoul.walk"));
	}

	@Test
	@DisplayName("숨긴 항목이 페이지 끝이어도 커서는 그 행 기준 그대로다 — 같은 페이지 무한 재조회 방지")
	void 숨긴_항목이_페이지_끝이어도_커서는_그_행_기준_그대로다() {
		// 커서를 "보이는 마지막 항목"에서 뽑으면 숨긴 행 앞에 멈춰 다음 페이지가 같은 자리를 다시 읽는다.
		// 걸러내기 전 pageRows 의 마지막 행 기준이라는 기존 규칙이 유지되는지 고정한다.
		given(videoRepository.findGlobalVideos(GRID_ID, 3)).willReturn(List.of(
			readyVideo(3L, 9L, 30L, at(12)),
			readyVideo(2L, 7L, 20L, at(11)),     // 작성자 탈퇴 — 응답에서 숨겨지는 페이지 끝 행
			readyVideo(1L, 9L, 10L, at(10))));   // lookahead 초과분
		given(videoRepository.findAuthorNicknames(anyCollection()))
			.willReturn(List.of(authorNickname(9L, "seoul.walk")));

		GridVideoPageResponseDto result = videoService.getGridGlobalVideos(GRID_ID, null, 2);

		assertThat(result.videos()).extracting(GridGlobalVideoResponseDto::videoId).containsExactly(3L);
		assertThat(result.hasNext()).isTrue();
		assertThat(VideoCursor.decode(result.nextCursor()).id()).isEqualTo(2L);
	}
}
