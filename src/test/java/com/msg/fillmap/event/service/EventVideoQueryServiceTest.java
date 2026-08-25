package com.msg.fillmap.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.event.EventTestFixtures;
import com.msg.fillmap.event.dto.EventLocationVideoPageResponseDto;
import com.msg.fillmap.event.dto.EventLocationVideoResponseDto;
import com.msg.fillmap.event.dto.EventVideoCommentResponseDto;
import com.msg.fillmap.event.dto.EventVideoDetailResponseDto;
import com.msg.fillmap.event.entity.EventLocation;
import com.msg.fillmap.event.entity.EventOccurrence;
import com.msg.fillmap.event.entity.EventVideo;
import com.msg.fillmap.event.exception.EventErrorCode;
import com.msg.fillmap.event.repository.EventLocationGridRepository;
import com.msg.fillmap.event.repository.EventLocationRepository;
import com.msg.fillmap.event.repository.EventLocationVideoCount;
import com.msg.fillmap.event.repository.EventOccurrenceRepository;
import com.msg.fillmap.event.repository.EventSeriesRepository;
import com.msg.fillmap.event.repository.EventVideoCommentRepository;
import com.msg.fillmap.event.repository.EventVideoHelpfulRepository;
import com.msg.fillmap.event.repository.EventVideoRepository;
import com.msg.fillmap.event.support.EventVideoCursor;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.service.VideoService;
import com.msg.fillmap.video.support.GeoSupport;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;
import com.msg.fillmap.zone.entity.Zone;
import com.msg.fillmap.zone.repository.ZoneRepository;
import com.msg.fillmap.zone.service.ZoneNameQueryService;

/**
 * 위치별 영상 피드와 행사 영상 상세 (MSG-440 §API 2·3, 실 PostgreSQL). 노출 술어·정렬·keyset 이 전부 DB
 * 판정이라 모킹으로는 검증되지 않는다 — presign 만 목이다(자격증명 없는 환경에서도 돌게).
 * <p>
 * 격리(공유 로컬 DB): 서해 먼바다 격자와 합성 자연키(msg440-*)만 쓰고 {@code @Transactional} 롤백으로
 * 정리한다. 단언은 이 테스트가 만든 위치·영상 id 로 좁혀 주변 데이터에 흔들리지 않는다.
 */
@SpringBootTest
@Transactional
@DisplayName("행사 영상 피드·상세 (실 PostgreSQL)")
class EventVideoQueryServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 1, 0, 0);

	/** 서해 먼바다 기준 격자 — 다른 행사 테스트(125.0·125.2·125.3·125.4)와 겹치지 않는 대역. */
	private static final GridIndex 바다 = GridEncoder.decode(GridEncoder.encode(34.0, 125.5));

	private static final String PRESIGNED = "https://example.test/presigned";

	@Autowired
	private EventSeriesRepository seriesRepository;

	@Autowired
	private EventOccurrenceRepository occurrenceRepository;

	@Autowired
	private EventLocationRepository locationRepository;

	@Autowired
	private EventLocationGridRepository locationGridRepository;

	@Autowired
	private EventVideoRepository eventVideoRepository;

	@Autowired
	private EventVideoCommentRepository commentRepository;

	@Autowired
	private EventVideoHelpfulRepository helpfulRepository;

	@Autowired
	private VideoRepository videoRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ZoneRepository zoneRepository;

	@Autowired
	private ZoneNameQueryService zoneNameQueryService;

	@Autowired
	private VideoService videoService;

	@Autowired
	private EntityManager em;

	/** presign 은 자격증명이 필요한 로컬 서명이라 목으로 고정한다 — 검증 대상은 "키가 실렸는가"다. */
	@MockitoBean
	private ThumbnailUrlPresigner thumbnailUrlPresigner;

	private EventTestFixtures fixtures;
	private Long userId;

	@BeforeEach
	void setUp() {
		given(thumbnailUrlPresigner.presign(anyString())).willReturn(PRESIGNED);
		fixtures = new EventTestFixtures(seriesRepository, occurrenceRepository, locationRepository,
			locationGridRepository);
		userId = 사용자();
	}

	private Long 사용자() {
		return userRepository.save(User.createLocalUser(
			"msg440-feed-" + UUID.randomUUID() + "@example.com", "hash", "행사업로더")).getId();
	}

	private EventVideoService service() {
		return service(NOW);
	}

	private EventVideoService service(LocalDateTime now) {
		return new EventVideoServiceImpl(occurrenceRepository, locationRepository, eventVideoRepository,
			videoService, videoRepository, thumbnailUrlPresigner, zoneNameQueryService, em, 반응서비스(now),
			Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
	}

	/** 상세·피드에 실릴 반응을 만들고 읽는 쪽 (MSG-441) — 상세 서비스가 읽기 목적으로 주입받는 것과 같은 빈이다. */
	private EventVideoInteractionService 반응서비스() {
		return 반응서비스(NOW);
	}

	private EventVideoInteractionService 반응서비스(LocalDateTime now) {
		return new EventVideoInteractionServiceImpl(eventVideoRepository, commentRepository, helpfulRepository,
			videoRepository, Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
	}

	private String 격자(long dy) {
		return (바다.gridY() + dy) + "_" + 바다.gridX();
	}

	private EventLocation 위치(long dy) {
		return 위치(NOW.minusDays(1), NOW.plusDays(1), dy);
	}

	private EventLocation 위치(LocalDateTime startsAt, LocalDateTime endsAt, long dy) {
		EventOccurrence occurrence = fixtures.회차(fixtures.시리즈(), startsAt, endsAt, 격자(dy));
		return fixtures.위치(occurrence, "행사 위치", 격자(dy));
	}

	/** 전역 노출 게이트(ACTIVE·PUBLIC·READY)를 통과하는 행사 영상 하나. */
	private Video 노출영상(EventLocation location) {
		Video video = 영상(location, Visibility.PUBLIC, userId);
		video.markReady("videos/encoded/" + UUID.randomUUID() + ".mp4", "thumb/" + UUID.randomUUID() + ".jpg",
			video.getDurationSec());
		return video;
	}

	private Video 영상(EventLocation location, Visibility visibility, Long owner) {
		String gridId = location.getRepresentativeGridId();
		GridIndex index = GridEncoder.decode(gridId);
		GridPoint center = GridEncoder.center(gridId);
		videoRepository.upsertGrid(gridId, index.gridY(), index.gridX(), center.lat(), center.lon(),
			GeoSupport.bboxWkt(gridId));
		Video video = videoRepository.save(Video.create(owner, gridId,
			"videos/original/" + UUID.randomUUID() + ".mp4", GeoSupport.toPoint(center.lat(), center.lon()),
			(short) 10, NOW, visibility));
		eventVideoRepository.save(new EventVideo(video, location, location.getOccurrence().getId()));
		return video;
	}

	/** 업로드 시각을 직접 못 박는다 — created_at 은 저장 시각 자동 값이라 정렬·동률 검증에는 고정이 필요하다. */
	private void 업로드시각(Long videoId, LocalDateTime createdAt) {
		em.flush();
		em.createNativeQuery("UPDATE videos SET created_at = :t WHERE id = :i")
			.setParameter("t", createdAt)
			.setParameter("i", videoId)
			.executeUpdate();
	}

	private List<Long> 영상ids(EventLocationVideoPageResponseDto page) {
		return page.videos().stream().map(EventLocationVideoResponseDto::videoId).toList();
	}

	private EventLocationVideoPageResponseDto 피드(EventLocation location, String cursor, int size) {
		return service().getLocationVideos(location.getOccurrence().getId(), location.getId(), cursor, size);
	}

	@Nested
	@DisplayName("위치별 피드 (API 2)")
	class 피드조회 {

		// 검증: FR-EVENT-09
		@Test
		@DisplayName("위치별 피드는 최신 업로드 순이다")
		void 위치별_피드는_최신_업로드_순이다() {
			EventLocation location = 위치(0);
			Video 오래된 = 노출영상(location);
			Video 최신 = 노출영상(location);
			업로드시각(오래된.getId(), NOW.minusHours(2));
			업로드시각(최신.getId(), NOW.minusHours(1));

			assertThat(영상ids(피드(location, null, 0)))
				.containsExactly(최신.getId(), 오래된.getId());
		}

		@Test
		@DisplayName("커서로 다음 페이지가 끊김 없이 이어진다 (경계 동시각 동률 포함)")
		void 커서로_다음_페이지가_끊김_없이_이어진다() {
			EventLocation location = 위치(1);
			Video 첫째 = 노출영상(location);
			Video 둘째 = 노출영상(location);
			Video 셋째 = 노출영상(location);
			// 앞 둘의 업로드 시각을 같게 둬 페이지 경계가 동률 위에 놓이게 한다 — id 타이브레이커가 없으면
			// 여기서 항목이 중복되거나 빠진다.
			업로드시각(첫째.getId(), NOW.minusHours(1));
			업로드시각(둘째.getId(), NOW.minusHours(1));
			업로드시각(셋째.getId(), NOW.minusHours(2));

			EventLocationVideoPageResponseDto first = 피드(location, null, 2);
			EventLocationVideoPageResponseDto second = 피드(location, first.nextCursor(), 2);

			assertThat(first.hasNext()).isTrue();
			assertThat(영상ids(first)).containsExactly(둘째.getId(), 첫째.getId());
			assertThat(second.hasNext()).isFalse();
			assertThat(second.nextCursor()).isNull();
			assertThat(영상ids(second)).containsExactly(셋째.getId());
		}

		@Test
		@DisplayName("노출 게이트 밖 영상은 피드에 잡히지 않는다")
		void 노출_게이트_밖_영상은_피드에_잡히지_않는다() {
			EventLocation location = 위치(2);
			Video 정상 = 노출영상(location);
			영상(location, Visibility.PUBLIC, userId);   // 인코딩 미완(READY 아님)
			Video 비공개 = 영상(location, Visibility.PRIVATE, userId);
			비공개.markReady("videos/encoded/priv.mp4", "thumb/priv.jpg", 비공개.getDurationSec());
			Video 삭제 = 노출영상(location);
			삭제.markDeleted();
			Video 블라인드 = 노출영상(location);
			블라인드.markBlinded();

			assertThat(영상ids(피드(location, null, 0))).containsExactly(정상.getId());
		}

		@Test
		@DisplayName("피드 노출 집합과 위치 영상 수 집계가 같다")
		void 피드_노출_집합과_위치_영상_수_집계가_같다() {
			EventLocation location = 위치(3);
			노출영상(location);
			노출영상(location);
			영상(location, Visibility.PRIVATE, userId);   // 두 술어 모두에서 빠져야 한다
			em.flush();

			long 집계 = eventVideoRepository.countVisibleByLocationIds(List.of(location.getId())).stream()
				.mapToLong(EventLocationVideoCount::videoCount)
				.sum();

			assertThat(피드(location, null, 0).videos()).hasSize((int) 집계);
		}

		// 검증: FR-EVENT-07
		@Test
		@DisplayName("다른 위치와 다른 회차의 영상이 피드에 섞이지 않는다")
		void 다른_위치와_다른_회차의_영상이_피드에_섞이지_않는다() {
			EventOccurrence occurrence = fixtures.회차(fixtures.시리즈(), NOW.minusDays(1), NOW.plusDays(1), 격자(4));
			EventLocation 대상 = fixtures.위치(occurrence, "위치 A", 격자(4));
			EventLocation 같은회차_다른위치 = fixtures.위치(occurrence, "위치 B", 격자(5));
			EventLocation 다른회차 = 위치(6);
			Video 대상영상 = 노출영상(대상);
			노출영상(같은회차_다른위치);
			노출영상(다른회차);

			assertThat(영상ids(피드(대상, null, 0))).containsExactly(대상영상.getId());
		}

		@Test
		@DisplayName("영상이 없는 위치의 피드는 빈 페이지다")
		void 영상이_없는_위치의_피드는_빈_페이지다() {
			EventLocationVideoPageResponseDto page = 피드(위치(7), null, 0);

			assertThat(page.videos()).isEmpty();
			assertThat(page.hasNext()).isFalse();
			assertThat(page.nextCursor()).isNull();
		}

		@Test
		@DisplayName("무효 커서는 13402로 거절된다")
		void 무효_커서는_13402로_거절된다() {
			EventLocation location = 위치(8);

			assertThatThrownBy(() -> 피드(location, "!!!not-a-cursor!!!", 0))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(EventErrorCode.INVALID_CURSOR);
		}

		@Test
		@DisplayName("다른 위치에서 발급된 커서는 13402로 거절된다")
		void 다른_위치에서_발급된_커서는_13402로_거절된다() {
			EventLocation location = 위치(9);
			String 남의커서 = EventVideoCursor.encode(location.getId() + 1000, NOW, 1L);

			assertThatThrownBy(() -> 피드(location, 남의커서, 0))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(EventErrorCode.INVALID_CURSOR);
		}

		@Test
		@DisplayName("size 는 1과 50 사이로 클램프된다")
		void size는_1과_50_사이로_클램프된다() {
			EventLocation location = 위치(10);
			for (int i = 0; i < 51; i++) {
				노출영상(location);
			}

			// 0 이하·미지정은 기본 20, 상한 초과는 50 으로 자른다 — 범위 밖은 에러가 아니다.
			assertThat(피드(location, null, 0).videos()).hasSize(20);
			assertThat(피드(location, null, 1).videos()).hasSize(1);
			assertThat(피드(location, null, 100).videos()).hasSize(50);
		}

		@Test
		@DisplayName("행사 영상 삭제는 점령 롤백과 함께 피드와 카운트에서 사라진다")
		void 행사_영상_삭제는_점령_롤백과_함께_피드와_카운트에서_사라진다() {
			EventLocation location = 위치(18);
			Video video = 노출영상(location);
			String gridId = location.getRepresentativeGridId();
			videoRepository.upsertUserGrid(userId, gridId, video.getId());
			em.flush();
			assertThat(피드(location, null, 0).videos()).hasSize(1);

			// 삭제는 차단하지 않는다 — soft delete 라 event_videos 행은 남고 노출 술어가 걷어낸다.
			videoService.deleteVideo(userId, video.getId());

			em.flush();
			assertThat(피드(location, null, 0).videos()).isEmpty();
			assertThat(eventVideoRepository.countVisibleByLocationIds(List.of(location.getId()))).isEmpty();
			assertThat(((Number) em.createNativeQuery(
				"SELECT count(*) FROM user_grids WHERE user_id = :u AND grid_id = :g")
				.setParameter("u", userId)
				.setParameter("g", gridId)
				.getSingleResult()).longValue()).isZero();
		}

		@Test
		@DisplayName("미노출 예정 회차의 피드는 13404, 회차 밖 위치는 13405다")
		void 미노출_예정_회차의_피드는_13404_회차_밖_위치는_13405다() {
			EventLocation 미노출 = 위치(NOW.plusDays(20), NOW.plusDays(21), 11);
			EventLocation 정상 = 위치(12);

			assertThatThrownBy(() -> 피드(미노출, null, 0))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(EventErrorCode.EVENT_NOT_FOUND);
			assertThatThrownBy(() -> service()
				.getLocationVideos(정상.getOccurrence().getId(), 정상.getId() + 10_000, null, 0))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(EventErrorCode.EVENT_LOCATION_NOT_FOUND);
		}

		@Test
		@DisplayName("피드 한 페이지의 집계는 항목 수와 무관하게 쿼리 왕복이 상수다")
		void 피드_한_페이지의_집계는_항목_수와_무관하게_쿼리_왕복이_상수다() {
			EventLocation 한개 = 위치(19);
			노출영상(한개);
			EventLocation 여러개 = 위치(20);
			for (int i = 0; i < 10; i++) {
				반응서비스().addHelpful(userId, 노출영상(여러개).getId());
			}
			em.flush();

			// 반응 집계는 페이지의 영상 id 집합으로 도는 group by 두 번이라 항목 수와 무관하게 상수다 —
			// 항목마다 세면 여기서 왕복이 9 늘어난다 (N+1 회귀 가드).
			long 적은쪽 = 문장수(() -> 피드(한개, null, 0));
			long 많은쪽 = 문장수(() -> 피드(여러개, null, 0));

			assertThat(많은쪽).isEqualTo(적은쪽);
		}

		/** 한 호출이 실제로 보낸 JDBC 문장 수. 통계는 이 단언에서만 켜고 바로 되돌린다. */
		private long 문장수(Runnable 호출) {
			Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
			boolean 원래값 = stats.isStatisticsEnabled();
			stats.setStatisticsEnabled(true);
			try {
				em.flush();
				stats.clear();
				호출.run();
				return stats.getPrepareStatementCount();
			} finally {
				stats.setStatisticsEnabled(원래값);
			}
		}
	}

	@Nested
	@DisplayName("영상 상세 (API 3)")
	class 상세조회 {

		private long 조회수(Long videoId) {
			em.flush();
			return ((Number) em.createNativeQuery("SELECT view_count FROM videos WHERE id = :i")
				.setParameter("i", videoId)
				.getSingleResult()).longValue();
		}

		/** 격자 한 칸을 덮는 구역 — zones 는 로컬에서 비어 있어 표시명 단언에는 자체 시드가 필요하다. */
		private void 구역(String gridId) {
			GridIndex index = GridEncoder.decode(gridId);
			zoneRepository.save(Zone.builder()
				.zoneKey(fixtures.키("zone"))
				.name("테스트구역")
				.minGridY((int) index.gridY())
				.maxGridY((int) index.gridY())
				.minGridX((int) index.gridX())
				.maxGridX((int) index.gridX())
				.priority(0)
				.build());
		}

		// 검증: FR-EVENT-08
		@Test
		@DisplayName("상세 응답에 대표 격자 표시명 재료와 작성자 닉네임이 담긴다")
		void 상세_응답에_대표_격자_표시명_재료와_작성자_닉네임이_담긴다() {
			EventLocation location = 위치(13);
			구역(location.getRepresentativeGridId());
			Video video = 노출영상(location);
			em.flush();

			EventVideoDetailResponseDto detail = service().getVideoDetail(video.getId(), userId);

			assertThat(detail.occurrenceId()).isEqualTo(location.getOccurrence().getId());
			assertThat(detail.occurrenceStatus()).isEqualTo("LIVE");
			assertThat(detail.locationId()).isEqualTo(location.getId());
			assertThat(detail.locationName()).isEqualTo("행사 위치");
			assertThat(detail.representativeGridId()).isEqualTo(location.getRepresentativeGridId());
			assertThat(detail.zoneName()).isEqualTo("테스트구역");
			assertThat(detail.zoneCell()).isEqualTo("A-1");
			assertThat(detail.playbackUrl()).isEqualTo(PRESIGNED);
			assertThat(detail.durationSec()).isEqualTo((short) 10);
			assertThat(detail.uploaderNickname()).isEqualTo("행사업로더");
			assertThat(detail.interactionLocked()).isFalse();
		}

		// 검증: FR-EVENT-10
		@Test
		@DisplayName("아카이브 전환 시각부터 interactionLocked 가 참이다")
		void 아카이브_전환_시각부터_interactionLocked가_참이다() {
			// 마감(종료 + 30일) 정각이 지금이 되도록 종료를 30일 앞에 둔다.
			LocalDateTime 종료 = NOW.minusDays(EventOccurrence.UPLOAD_GRACE_DAYS);
			EventLocation location = 위치(종료.minusDays(2), 종료, 14);
			Video video = 노출영상(location);
			em.flush();

			// 표시(이 필드)와 집행(MSG-441 변경 API 의 가드)이 같은 축이어야 한다 — 판정은 회차 상태
			// ARCHIVED 하나이고, 경계 정각의 해석은 statusAt 의 반개구간 규칙에 위임된다.
			EventVideoDetailResponseDto 마감정각 = service().getVideoDetail(video.getId(), userId);
			assertThat(마감정각.occurrenceStatus()).isEqualTo("ARCHIVED");
			assertThat(마감정각.interactionLocked()).isTrue();

			// 유예 기간은 열려 있다 (2026-08-21 번복 — 종료 정각 기준이었다면 여기서 true 였다).
			EventVideoDetailResponseDto 마감직전 = service(NOW.minusSeconds(1))
				.getVideoDetail(video.getId(), userId);
			assertThat(마감직전.occurrenceStatus()).isEqualTo("UPLOAD_GRACE");
			assertThat(마감직전.interactionLocked()).isFalse();
		}

		@Test
		@DisplayName("행사 영상이 아닌 videoId 상세는 13406으로 실패한다")
		void 행사_영상이_아닌_videoId_상세는_13406으로_실패한다() {
			EventLocation location = 위치(15);
			String gridId = location.getRepresentativeGridId();
			GridIndex index = GridEncoder.decode(gridId);
			GridPoint center = GridEncoder.center(gridId);
			videoRepository.upsertGrid(gridId, index.gridY(), index.gridX(), center.lat(), center.lon(),
				GeoSupport.bboxWkt(gridId));
			Video 일반영상 = videoRepository.save(Video.create(userId, gridId,
				"videos/original/" + UUID.randomUUID() + ".mp4", GeoSupport.toPoint(center.lat(), center.lon()),
				(short) 10, NOW, Visibility.PUBLIC));
			일반영상.markReady("videos/encoded/plain.mp4", "thumb/plain.jpg", 일반영상.getDurationSec());
			em.flush();

			assertThatThrownBy(() -> service().getVideoDetail(일반영상.getId(), userId))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(EventErrorCode.EVENT_VIDEO_NOT_FOUND);
		}

		@Test
		@DisplayName("노출 게이트 밖 영상의 상세는 13406으로 실패한다 (소유자 본인 포함)")
		void 노출_게이트_밖_영상의_상세는_13406으로_실패한다() {
			EventLocation location = 위치(16);
			Video 인코딩중 = 영상(location, Visibility.PUBLIC, userId);
			em.flush();

			assertThatThrownBy(() -> service().getVideoDetail(인코딩중.getId(), userId))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(EventErrorCode.EVENT_VIDEO_NOT_FOUND);
		}

		@Test
		@DisplayName("비소유자 상세 재생은 view_count 를 증가시킨다 (비로그인 포함, 소유자는 미증가)")
		void 비소유자_상세_재생은_view_count를_증가시킨다() {
			EventLocation location = 위치(17);
			Video video = 노출영상(location);
			em.flush();

			service().getVideoDetail(video.getId(), userId);   // 소유자 본인 — 증가하지 않는다
			assertThat(조회수(video.getId())).isZero();

			service().getVideoDetail(video.getId(), 사용자());   // 타인
			service().getVideoDetail(video.getId(), null);   // 비로그인
			assertThat(조회수(video.getId())).isEqualTo(2);
		}

		// 검증: FR-EVENT-09
		@Test
		@DisplayName("상세의 도움돼요 수와 피드 카드의 하트 수가 같다")
		void 상세의_도움돼요_수와_피드_카드의_하트_수가_같다() {
			EventLocation location = 위치(21);
			Video video = 노출영상(location);
			em.flush();
			반응서비스().addHelpful(userId, video.getId());
			반응서비스().addHelpful(사용자(), video.getId());
			em.flush();

			// 원천 테이블 하나를 아무 부가 술어 없이 세므로 두 화면의 수가 갈릴 수 없다 (US-006).
			assertThat(service().getVideoDetail(video.getId(), userId).helpfulCount()).isEqualTo(2);
			assertThat(피드(location, null, 0).videos())
				.singleElement()
				.extracting(EventLocationVideoResponseDto::helpfulCount)
				.isEqualTo(2L);
		}

		@Test
		@DisplayName("상세의 댓글 수와 피드 카드의 댓글 수가 같다")
		void 상세의_댓글_수와_피드_카드의_댓글_수가_같다() {
			EventLocation location = 위치(22);
			Video video = 노출영상(location);
			em.flush();
			반응서비스().createComment(userId, video.getId(), "첫 댓글");
			반응서비스().createComment(userId, video.getId(), "둘째 댓글");
			em.flush();

			EventVideoDetailResponseDto detail = service().getVideoDetail(video.getId(), userId);
			assertThat(detail.commentCount()).isEqualTo(2);
			// 상세는 첫 페이지를 이미 품는다 — 화면이 목록 API 를 한 번 더 부르지 않는다.
			assertThat(detail.comments().comments()).hasSize(2);
			assertThat(detail.comments().hasNext()).isFalse();
			assertThat(피드(location, null, 0).videos())
				.singleElement()
				.extracting(EventLocationVideoResponseDto::commentCount)
				.isEqualTo(2L);
		}

		@Test
		@DisplayName("상세의 helpfulByMe 는 내가 누른 경우에만 true 다")
		void 상세의_helpfulByMe는_내가_누른_경우에만_true다() {
			Video video = 노출영상(위치(23));
			em.flush();
			Long 남 = 사용자();
			반응서비스().addHelpful(userId, video.getId());
			em.flush();

			assertThat(service().getVideoDetail(video.getId(), userId).helpfulByMe()).isTrue();
			assertThat(service().getVideoDetail(video.getId(), 남).helpfulByMe()).isFalse();
		}

		@Test
		@DisplayName("비로그인 상세 조회의 helpfulByMe 는 false 다")
		void 비로그인_상세_조회의_helpfulByMe는_false다() {
			Video video = 노출영상(위치(24));
			em.flush();
			반응서비스().addHelpful(userId, video.getId());
			em.flush();

			EventVideoDetailResponseDto detail = service().getVideoDetail(video.getId(), null);
			assertThat(detail.helpfulByMe()).isFalse();
			assertThat(detail.helpfulCount()).isEqualTo(1);   // 수는 그대로 보인다
		}

		@Test
		@DisplayName("반응이 없는 영상의 두 수는 0이다")
		void 반응이_없는_영상의_두_수는_0이다() {
			EventLocation location = 위치(25);
			Video video = 노출영상(location);
			em.flush();

			EventVideoDetailResponseDto detail = service().getVideoDetail(video.getId(), userId);
			assertThat(detail.helpfulCount()).isZero();
			assertThat(detail.commentCount()).isZero();
			assertThat(detail.comments().comments()).isEmpty();
			assertThat(피드(location, null, 0).videos()).singleElement().satisfies(card -> {
				assertThat(card.helpfulCount()).isZero();
				assertThat(card.commentCount()).isZero();
			});
		}

		// 검증: FR-EVENT-07
		@Test
		@DisplayName("다른 회차 영상의 댓글이 섞이지 않는다")
		void 다른_회차_영상의_댓글이_섞이지_않는다() {
			Video 대상 = 노출영상(위치(26));
			Video 다른회차 = 노출영상(위치(27));
			em.flush();
			반응서비스().createComment(userId, 대상.getId(), "대상 영상 댓글");
			반응서비스().createComment(userId, 다른회차.getId(), "다른 회차 댓글");
			em.flush();

			assertThat(service().getVideoDetail(대상.getId(), userId).comments().comments())
				.singleElement()
				.extracting(EventVideoCommentResponseDto::content)
				.isEqualTo("대상 영상 댓글");
		}
	}
}
