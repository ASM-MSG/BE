package com.msg.fillmap.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import com.msg.fillmap.badge.dto.EarnedBadgeResponseDto;
import com.msg.fillmap.badge.service.BadgeAwardService;
import com.msg.fillmap.event.EventTestFixtures;
import com.msg.fillmap.event.dto.EventVideoUploadRequestDto;
import com.msg.fillmap.event.dto.EventVideoUploadResponseDto;
import com.msg.fillmap.event.entity.EventLocation;
import com.msg.fillmap.event.entity.EventOccurrence;
import com.msg.fillmap.event.exception.EventErrorCode;
import com.msg.fillmap.event.repository.EventLocationGridRepository;
import com.msg.fillmap.event.repository.EventLocationRepository;
import com.msg.fillmap.event.repository.EventOccurrenceRepository;
import com.msg.fillmap.event.repository.EventSeriesRepository;
import com.msg.fillmap.event.repository.EventVideoRepository;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.hotzone.service.HotScoreCommandService;
import com.msg.fillmap.mission.service.MissionAwardService;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.exception.VideoErrorCode;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.service.VideoEncodingService;
import com.msg.fillmap.video.service.VideoService;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;
import com.msg.fillmap.zone.service.ZoneNameQueryService;

/**
 * 행사 영상 업로드 확정 (MSG-440 §API 1, 실 PostgreSQL). 이 티켓의 업로드는 새 파이프라인이 아니라 기존
 * 확정 코어를 지정 격자로 태우는 것이라, 검증 대상이 대부분 DB 부수효과(videos·user_grids·event_videos)와
 * 서버 시각 판정이다 — 모킹으로는 잡히지 않아 실 스택으로 돈다. 고정 클럭만 주입한다.
 * <p>
 * 격리(공유 로컬 DB): 서해 먼바다 격자와 합성 자연키(msg440-*)만 쓰고 {@code @Transactional} 롤백으로
 * 정리한다. S3·인코딩은 목이다 — 확정 경로가 실제 버킷과 인코딩 워커로 나가지 않게 한다.
 */
@SpringBootTest
@Transactional
@DisplayName("행사 영상 업로드 확정 (실 PostgreSQL)")
class EventVideoUploadServiceTest {

	/** 고정 서버 시각 — 업로드 창 판정의 기준. UTC 저장 컬럼과 같은 축이라 존 스큐가 없다. */
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 1, 0, 0);

	/** 서해 먼바다 기준 격자 — 육상 실데이터·다른 행사 테스트(125.0·125.2)와 겹치지 않는다. */
	private static final GridIndex 바다 = GridEncoder.decode(GridEncoder.encode(34.0, 125.3));

	private static final EarnedBadgeResponseDto 업로드뱃지 =
		new EarnedBadgeResponseDto(1L, "UPLOAD_1", "첫 기록", "첫 영상을 올렸어요", null);
	private static final EarnedBadgeResponseDto 수집뱃지 =
		new EarnedBadgeResponseDto(2L, "EXPLORER_1", "첫 발자국", "첫 격자를 수집했어요", null);

	@Autowired
	private EventOccurrenceRepository occurrenceRepository;

	@Autowired
	private EventLocationRepository locationRepository;

	@Autowired
	private EventLocationGridRepository locationGridRepository;

	@Autowired
	private EventSeriesRepository seriesRepository;

	@Autowired
	private EventVideoRepository eventVideoRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private VideoService videoService;

	// 아래 셋은 피드·상세 경로의 협력자다 — 업로드는 쓰지 않지만 서비스 생성에 필요해 받아 둔다.
	@Autowired
	private VideoRepository videoRepository;

	@Autowired
	private ThumbnailUrlPresigner thumbnailUrlPresigner;

	@Autowired
	private ZoneNameQueryService zoneNameQueryService;

	/** 업로드는 반응을 읽지 않지만 상세·피드 경로의 협력자라 서비스 생성에 필요하다 (MSG-441). */
	@Autowired
	private EventVideoInteractionService interactionService;

	@Autowired
	private EntityManager em;

	@MockitoBean
	private S3Client s3Client;

	/** 확정 커밋 후 인코딩 제출이 실제 워커로 나가지 않게 막는다 — 이 테스트의 관심사는 확정까지다. */
	@MockitoBean
	private VideoEncodingService videoEncodingService;

	/** 행사 업로드가 미션 판정을 호출하지 않는다는 제외 계약(MSG-438)을 관측하기 위한 목이다. */
	@MockitoBean
	private MissionAwardService missionAwardService;

	/** afterCommit 훅 등록 대상 — 테스트 트랜잭션은 커밋되지 않아 콜백을 직접 실행해 관측한다. */
	@MockitoBean
	private HotScoreCommandService hotScoreCommandService;

	/** 뱃지 규칙 자체는 badge 도메인 테스트 몫이라, 여기서는 응답 동봉 배선만 고정값으로 본다. */
	@MockitoBean
	private BadgeAwardService badgeAwardService;

	private EventTestFixtures fixtures;
	private Long userId;

	@BeforeEach
	void setUp() {
		given(s3Client.headObject(any(HeadObjectRequest.class))).willReturn(HeadObjectResponse.builder().build());
		given(badgeAwardService.awardUploadBadges(anyLong())).willReturn(List.of());
		given(badgeAwardService.awardCollectionBadges(anyLong(), anyString())).willReturn(List.of());
		fixtures = new EventTestFixtures(seriesRepository, occurrenceRepository, locationRepository,
			locationGridRepository);
		userId = 사용자("uploader");
	}

	private Long 사용자(String prefix) {
		return userRepository.save(User.createLocalUser(
			"msg440-" + prefix + "-" + UUID.randomUUID() + "@example.com", "hash", "행사업로더")).getId();
	}

	private EventVideoService service() {
		return service(NOW);
	}

	private EventVideoService service(LocalDateTime now) {
		return new EventVideoServiceImpl(occurrenceRepository, locationRepository, eventVideoRepository,
			videoService, videoRepository, thumbnailUrlPresigner, zoneNameQueryService, em, interactionService,
			Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
	}

	private String 격자(long dy, long dx) {
		return (바다.gridY() + dy) + "_" + (바다.gridX() + dx);
	}

	private String 키(Long owner) {
		return "videos/pending/" + owner + "/" + UUID.randomUUID() + ".mp4";
	}

	private EventVideoUploadRequestDto 요청(String s3Key) {
		return new EventVideoUploadRequestDto(s3Key, (short) 10, NOW);
	}

	/** 진행 중인 회차 하나와 그 위치 하나 — 창 판정이 관심사가 아닌 테스트의 기본 픽스처다. */
	private EventLocation 진행중_위치(long dy, long dx) {
		EventOccurrence occurrence = fixtures.회차(fixtures.시리즈(), NOW.minusDays(1), NOW.plusDays(1), 격자(dy, dx));
		return fixtures.위치(occurrence, "행사 위치", 격자(dy, dx), 격자(dy, dx + 1));
	}

	private EventVideoUploadResponseDto 업로드(EventLocation location, String s3Key) {
		return 업로드(service(), location, s3Key, userId);
	}

	private EventVideoUploadResponseDto 업로드(EventVideoService service, EventLocation location, String s3Key,
		long uploader) {
		return service.upload(uploader, location.getOccurrence().getId(), location.getId(), 요청(s3Key));
	}

	private long 카운트(String sql, String key, Object value) {
		return ((Number) em.createNativeQuery(sql).setParameter("v", value).getSingleResult()).longValue();
	}

	private long 격자영상수(String gridId) {
		em.flush();
		return 카운트("SELECT count(*) FROM videos WHERE grid_id = :v", "v", gridId);
	}

	@Nested
	@DisplayName("대표 격자 저장과 점령")
	class 저장 {

		// 검증: FR-EVENT-08
		@Test
		@DisplayName("행사 업로드는 서버가 지정한 대표 격자 하나에만 영상을 만든다")
		void 행사_업로드는_서버가_지정한_대표_격자_하나에만_영상을_만든다() {
			EventLocation location = 진행중_위치(0, 0);

			EventVideoUploadResponseDto response = 업로드(location, 키(userId));

			assertThat(response.gridId()).isEqualTo(location.getRepresentativeGridId());
			assertThat(response.processingStatus()).isEqualTo("UPLOADED");
			assertThat(격자영상수(location.getRepresentativeGridId())).isEqualTo(1);
			// 영역의 다른 격자에는 복제되지 않는다 — 영상은 대표 격자에만 붙는다.
			assertThat(격자영상수(격자(0, 1))).isZero();
			assertThat(eventVideoRepository.findById(response.videoId())).isPresent()
				.get()
				.satisfies(link -> assertThat(link.getLocation().getId()).isEqualTo(location.getId()));
		}

		// 검증: FR-EVENT-09
		@Test
		@DisplayName("행사 업로드도 일반 업로드처럼 점령을 만든다")
		void 행사_업로드도_일반_업로드처럼_점령을_만든다() {
			EventLocation location = 진행중_위치(1, 0);

			EventVideoUploadResponseDto response = 업로드(location, 키(userId));

			em.flush();
			assertThat(response.occupied()).isTrue();
			assertThat(카운트("SELECT count(*) FROM user_grids WHERE grid_id = :v", "v",
				location.getRepresentativeGridId())).isEqualTo(1);
		}

		@Test
		@DisplayName("이미 점령한 대표 격자에 또 올리면 video_count 만 증가한다")
		void 이미_점령한_대표_격자에_또_올리면_video_count만_증가한다() {
			EventLocation location = 진행중_위치(2, 0);
			업로드(location, 키(userId));

			EventVideoUploadResponseDto second = 업로드(location, 키(userId));

			em.flush();
			assertThat(second.occupied()).isFalse();
			assertThat(카운트("SELECT video_count FROM user_grids WHERE grid_id = :v", "v",
				location.getRepresentativeGridId())).isEqualTo(2);
		}

		@Test
		@DisplayName("행사 업로드의 공개범위는 PUBLIC 으로 고정된다")
		void 행사_업로드의_공개범위는_PUBLIC으로_고정된다() {
			EventLocation location = 진행중_위치(3, 0);

			EventVideoUploadResponseDto response = 업로드(location, 키(userId));

			em.flush();
			assertThat(em.createNativeQuery("SELECT visibility FROM videos WHERE id = :v")
				.setParameter("v", response.videoId())
				.getSingleResult()).isEqualTo("PUBLIC");
		}

		@Test
		@DisplayName("행사 업로드 응답에 획득 뱃지가 동봉된다")
		void 행사_업로드_응답에_획득_뱃지가_동봉된다() {
			given(badgeAwardService.awardUploadBadges(userId)).willReturn(List.of(업로드뱃지));
			given(badgeAwardService.awardCollectionBadges(anyLong(), anyString())).willReturn(List.of(수집뱃지));
			EventLocation location = 진행중_위치(4, 0);

			EventVideoUploadResponseDto response = 업로드(location, 키(userId));

			// 첫 점령이라 업로드 수 뱃지와 수집 뱃지가 함께 실린다.
			assertThat(response.newBadges()).containsExactly(업로드뱃지, 수집뱃지);
		}

		@Test
		@DisplayName("행사 업로드는 미션 판정을 호출하지 않는다")
		void 행사_업로드는_미션_판정을_호출하지_않는다() {
			EventLocation location = 진행중_위치(5, 0);

			업로드(location, 키(userId));

			then(missionAwardService).should(never()).awardOnUpload(anyLong(), anyString());
		}

		@Test
		@DisplayName("행사 업로드는 스트릭과 핫스코어 훅을 그대로 탄다")
		void 행사_업로드는_스트릭과_핫스코어_훅을_그대로_탄다() {
			EventLocation location = 진행중_위치(6, 0);

			업로드(location, 키(userId));

			em.flush();
			assertThat(카운트("SELECT count(*) FROM streaks WHERE user_id = :v", "v", userId)).isEqualTo(1);
			// 핫스코어는 커밋 후 훅이라 테스트 트랜잭션에서는 저절로 돌지 않는다 — 등록된 동기화를 직접
			// 실행해 "대표 격자로 등록됐다"를 관측한다(롤백 시 유령 증분 방지 성질은 그대로다).
			TransactionSynchronizationManager.getSynchronizations()
				.forEach(TransactionSynchronization::afterCommit);
			then(hotScoreCommandService).should().recordUpload(location.getRepresentativeGridId());
		}
	}

	@Nested
	@DisplayName("멱등 재시도")
	class 멱등 {

		@Test
		@DisplayName("같은 s3Key 재시도는 중복 레코드 없이 저장 행 기준 성공을 돌려준다")
		void 같은_s3Key_재시도는_중복_레코드_없이_저장_행_기준_성공을_돌려준다() {
			given(badgeAwardService.awardUploadBadges(userId)).willReturn(List.of(업로드뱃지));
			EventLocation location = 진행중_위치(7, 0);
			String s3Key = 키(userId);
			EventVideoUploadResponseDto first = 업로드(location, s3Key);

			EventVideoUploadResponseDto retry = 업로드(location, s3Key);

			assertThat(retry.videoId()).isEqualTo(first.videoId());
			assertThat(retry.gridId()).isEqualTo(first.gridId());
			assertThat(retry.processingStatus()).isEqualTo("UPLOADED");
			// 첫 응답 전용 필드는 재시도에서 비운다 — 복원할 저장 컬럼이 없다.
			assertThat(retry.occupied()).isFalse();
			assertThat(retry.newBadges()).isEmpty();
			assertThat(격자영상수(location.getRepresentativeGridId())).isEqualTo(1);
		}

		@Test
		@DisplayName("마감 후 도착한 재시도도 커밋된 업로드면 성공을 돌려준다")
		void 마감_후_도착한_재시도도_커밋된_업로드면_성공을_돌려준다() {
			EventLocation location = 진행중_위치(8, 0);
			String s3Key = 키(userId);
			EventVideoUploadResponseDto first = 업로드(location, s3Key);

			// 마감(종료 + 30일) 한참 뒤에 도착한 재시도 — 멱등 판정이 창 판정보다 앞선다(Codex P2).
			EventVideoUploadResponseDto retry =
				업로드(service(NOW.plusDays(60)), location, s3Key, userId);

			assertThat(retry.videoId()).isEqualTo(first.videoId());
			assertThat(격자영상수(location.getRepresentativeGridId())).isEqualTo(1);
		}

		@Test
		@DisplayName("다른 사용자가 같은 s3Key 로 확정하면 3401로 거절된다")
		void 다른_사용자가_같은_s3Key로_확정하면_3401로_거절된다() {
			EventLocation location = 진행중_위치(9, 0);
			String s3Key = 키(userId);
			업로드(location, s3Key);
			long 다른사용자 = 사용자("thief");

			assertThatThrownBy(() -> 업로드(service(), location, s3Key, 다른사용자))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(VideoErrorCode.INVALID_S3_KEY);
		}

		@Test
		@DisplayName("다른 위치로 같은 s3Key 를 재시도하면 3401로 거절된다")
		void 다른_위치로_같은_s3Key를_재시도하면_3401로_거절된다() {
			EventOccurrence occurrence = fixtures.회차(fixtures.시리즈(), NOW.minusDays(1), NOW.plusDays(1),
				격자(10, 0));
			EventLocation first = fixtures.위치(occurrence, "위치 A", 격자(10, 0));
			EventLocation second = fixtures.위치(occurrence, "위치 B", 격자(10, 1));
			String s3Key = 키(userId);
			업로드(first, s3Key);

			assertThatThrownBy(() -> 업로드(second, s3Key))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(VideoErrorCode.INVALID_S3_KEY);
		}
	}

	@Nested
	@DisplayName("업로드 창과 존재 은닉")
	class 창판정 {

		private EventLocation 위치(LocalDateTime startsAt, LocalDateTime endsAt, long dy) {
			EventOccurrence occurrence = fixtures.회차(fixtures.시리즈(), startsAt, endsAt, 격자(dy, 0));
			return fixtures.위치(occurrence, "행사 위치", 격자(dy, 0));
		}

		// 검증: FR-EVENT-10
		@Test
		@DisplayName("노출 중인 예정 회차 업로드는 13410으로 거절된다")
		void 노출_중인_예정_회차_업로드는_13410으로_거절된다() {
			// 시작 10일 뒤 = 노출은 시작됐지만(2주 전부터) 아직 시작 전이다.
			EventLocation location = 위치(NOW.plusDays(10), NOW.plusDays(11), 11);

			assertThatThrownBy(() -> 업로드(location, 키(userId)))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(EventErrorCode.EVENT_UPLOAD_NOT_STARTED);
		}

		@Test
		@DisplayName("시작 정각 업로드는 성공한다")
		void 시작_정각_업로드는_성공한다() {
			EventLocation location = 위치(NOW, NOW.plusDays(1), 12);

			assertThat(업로드(location, 키(userId)).videoId()).isNotNull();
		}

		// 검증: FR-EVENT-10
		@Test
		@DisplayName("마감 정각부터의 신규 업로드는 13409로 거절된다")
		void 마감_정각부터의_신규_업로드는_13409로_거절된다() {
			// 마감 = 종료 + 30일. 종료를 정확히 30일 전으로 두면 지금이 마감 정각이다.
			EventLocation location = 위치(NOW.minusDays(31), NOW.minusDays(30), 13);

			assertThatThrownBy(() -> 업로드(location, 키(userId)))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(EventErrorCode.EVENT_UPLOAD_CLOSED);
		}

		@Test
		@DisplayName("마감 직전 업로드는 성공한다")
		void 마감_직전_업로드는_성공한다() {
			EventLocation location = 위치(NOW.minusDays(31), NOW.minusDays(30).plusMinutes(1), 14);

			assertThat(업로드(location, 키(userId)).videoId()).isNotNull();
		}

		@Test
		@DisplayName("미노출 예정 회차 업로드는 13404로 실패한다 (존재 은닉이 창 판정보다 선행)")
		void 미노출_예정_회차_업로드는_13404로_실패한다() {
			// 시작 20일 뒤 = 노출 시작(시작 2주 전)도 아직이라 회차의 존재 자체를 숨긴다.
			EventLocation location = 위치(NOW.plusDays(20), NOW.plusDays(21), 15);

			assertThatThrownBy(() -> 업로드(location, 키(userId)))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(EventErrorCode.EVENT_NOT_FOUND);
		}

		@Test
		@DisplayName("위치가 경로의 회차에 속하지 않으면 13405로 실패한다")
		void 위치가_경로의_회차에_속하지_않으면_13405로_실패한다() {
			EventLocation location = 진행중_위치(16, 0);
			EventOccurrence 다른회차 = fixtures.회차(fixtures.시리즈(), NOW.minusDays(1), NOW.plusDays(1), 격자(17, 0));

			assertThatThrownBy(() -> service().upload(userId, 다른회차.getId(), location.getId(), 요청(키(userId))))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(EventErrorCode.EVENT_LOCATION_NOT_FOUND);
		}
	}
}
