package com.msg.fillmap.event.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.event.EventTestFixtures;
import com.msg.fillmap.event.entity.EventLocation;
import com.msg.fillmap.event.entity.EventVideo;
import com.msg.fillmap.event.entity.EventVideoComment;
import com.msg.fillmap.event.entity.EventVideoHelpfulId;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.GeoSupport;

/**
 * 행사 영상 반응 저장소와 V41 DDL (실 PostgreSQL, MSG-441). 노출 술어 로딩, 도움돼요 쓰기 두 native
 * 문장의 1문장 멱등, 집계 세 형태(단건 두 개 · 배치 두 개)가 검증 대상이다 — 전부 DB 판정이라
 * 모킹으로는 잡히지 않는다. 파생 쿼리 이름(countByVideo_VideoId · countById_VideoId)의 경로 표기는
 * 컨텍스트 로딩 자체가 가드다(틀리면 리포지토리 초기화가 실패한다).
 * <p>
 * 격리(공유 로컬 DB): 서해 먼바다 격자(125.6)와 합성 자연키만 쓰고 {@code @Transactional} 롤백으로 정리한다.
 */
@SpringBootTest
@Transactional
@DisplayName("행사 영상 반응 저장소 · V41 DDL (실 PostgreSQL)")
class EventVideoInteractionRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 1, 0, 0);

	/** 서해 먼바다 기준 격자 — 다른 행사 테스트(125.0~125.5)와 겹치지 않는 대역. */
	private static final GridIndex 바다 = GridEncoder.decode(GridEncoder.encode(34.0, 125.6));

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
	private EntityManager em;

	private EventTestFixtures fixtures;
	private Long userId;

	@BeforeEach
	void setUp() {
		fixtures = new EventTestFixtures(seriesRepository, occurrenceRepository, locationRepository,
			locationGridRepository);
		userId = 사용자();
	}

	private Long 사용자() {
		return userRepository.save(User.createLocalUser(
			"msg441-repo-" + UUID.randomUUID() + "@example.com", "hash", "반응테스터")).getId();
	}

	private EventLocation 위치(long dy) {
		String gridId = (바다.gridY() + dy) + "_" + 바다.gridX();
		return fixtures.위치(fixtures.회차(fixtures.시리즈(), NOW.minusDays(1), NOW.plusDays(1), gridId), "행사 위치",
			gridId);
	}

	private Video 영상(EventLocation location, Visibility visibility) {
		String gridId = location.getRepresentativeGridId();
		GridIndex index = GridEncoder.decode(gridId);
		GridPoint center = GridEncoder.center(gridId);
		videoRepository.upsertGrid(gridId, index.gridY(), index.gridX(), center.lat(), center.lon(),
			GeoSupport.bboxWkt(gridId));
		Video video = videoRepository.save(Video.create(userId, gridId,
			"videos/original/" + UUID.randomUUID() + ".mp4", GeoSupport.toPoint(center.lat(), center.lon()),
			(short) 10, NOW, visibility));
		eventVideoRepository.save(new EventVideo(video, location, location.getOccurrence().getId()));
		return video;
	}

	/** 전역 노출 게이트(ACTIVE·PUBLIC·READY)를 통과하는 행사 영상 하나. */
	private Video 노출영상(EventLocation location) {
		Video video = 영상(location, Visibility.PUBLIC);
		video.markReady("videos/encoded/" + UUID.randomUUID() + ".mp4", "thumb/" + UUID.randomUUID() + ".jpg",
			video.getDurationSec());
		return video;
	}

	@Nested
	@DisplayName("노출 술어 로딩")
	class 노출술어 {

		@Test
		@DisplayName("노출 영상은 위치·회차와 함께 한 번에 읽힌다")
		void 노출_영상은_위치와_회차와_함께_한_번에_읽힌다() {
			EventLocation location = 위치(0);
			Video video = 노출영상(location);
			em.flush();
			em.clear();

			EventVideo link = eventVideoRepository.findVisibleWithOccurrence(video.getId()).orElseThrow();

			// JOIN FETCH 결과라 지연 로딩 왕복 없이 회차가 손에 들려 있어야 한다 — 가드에 바로 넘길 재료다.
			assertThat(link.getLocation().getOccurrence().getId())
				.isEqualTo(location.getOccurrence().getId());
		}

		@Test
		@DisplayName("삭제·비공개·인코딩 미완료 영상과 행사 영상이 아닌 영상은 읽히지 않는다")
		void 노출_게이트_밖_영상은_읽히지_않는다() {
			EventLocation location = 위치(1);
			Video 인코딩중 = 영상(location, Visibility.PUBLIC);
			Video 비공개 = 영상(location, Visibility.PRIVATE);
			비공개.markReady("videos/encoded/priv.mp4", "thumb/priv.jpg", 비공개.getDurationSec());
			Video 삭제 = 노출영상(location);
			삭제.markDeleted();
			Video 블라인드 = 노출영상(location);
			블라인드.markBlinded();
			// 행사에 연결되지 않은 일반 영상
			String gridId = location.getRepresentativeGridId();
			GridPoint center = GridEncoder.center(gridId);
			Video 일반 = videoRepository.save(Video.create(userId, gridId,
				"videos/original/" + UUID.randomUUID() + ".mp4",
				GeoSupport.toPoint(center.lat(), center.lon()), (short) 10, NOW, Visibility.PUBLIC));
			일반.markReady("videos/encoded/plain.mp4", "thumb/plain.jpg", 일반.getDurationSec());
			em.flush();
			em.clear();

			assertThat(eventVideoRepository.findVisibleWithOccurrence(인코딩중.getId())).isEmpty();
			assertThat(eventVideoRepository.findVisibleWithOccurrence(비공개.getId())).isEmpty();
			assertThat(eventVideoRepository.findVisibleWithOccurrence(삭제.getId())).isEmpty();
			assertThat(eventVideoRepository.findVisibleWithOccurrence(블라인드.getId())).isEmpty();
			assertThat(eventVideoRepository.findVisibleWithOccurrence(일반.getId())).isEmpty();
		}
	}

	@Nested
	@DisplayName("도움돼요 쓰기 (native 1문장)")
	class 도움돼요쓰기 {

		@Test
		@DisplayName("추가는 같은 사용자가 두 번 보내도 행이 하나다")
		void 추가는_같은_사용자가_두_번_보내도_행이_하나다() {
			Video video = 노출영상(위치(2));
			em.flush();

			assertThat(helpfulRepository.insertHelpful(video.getId(), userId)).isEqualTo(1);
			assertThat(helpfulRepository.insertHelpful(video.getId(), userId)).isZero();
			assertThat(helpfulRepository.countById_VideoId(video.getId())).isEqualTo(1);
			assertThat(helpfulRepository.existsById(new EventVideoHelpfulId(video.getId(), userId))).isTrue();
		}

		@Test
		@DisplayName("취소는 누른 적 없어도 0행으로 성공한다")
		void 취소는_누른_적_없어도_0행으로_성공한다() {
			Video video = 노출영상(위치(3));
			em.flush();

			assertThat(helpfulRepository.deleteHelpful(video.getId(), userId)).isZero();
			helpfulRepository.insertHelpful(video.getId(), userId);
			assertThat(helpfulRepository.deleteHelpful(video.getId(), userId)).isEqualTo(1);
			assertThat(helpfulRepository.countById_VideoId(video.getId())).isZero();
		}

		@Test
		@DisplayName("추가는 created_at 을 DB DEFAULT 로 채운다")
		void 추가는_created_at을_DB_DEFAULT로_채운다() {
			Video video = 노출영상(위치(4));
			em.flush();
			helpfulRepository.insertHelpful(video.getId(), userId);

			// native INSERT 는 컬럼을 생략하므로 DEFAULT 가 실제 저장값이다 (댓글은 팩터리가 채운다).
			assertThat(helpfulRepository.findById(new EventVideoHelpfulId(video.getId(), userId)))
				.get()
				.extracting(h -> h.getCreatedAt() != null)
				.isEqualTo(true);
		}
	}

	@Nested
	@DisplayName("집계")
	class 집계 {

		@Test
		@DisplayName("영상 집합 집계는 반응이 있는 영상만 행으로 준다")
		void 영상_집합_집계는_반응이_있는_영상만_행으로_준다() {
			EventLocation location = 위치(5);
			Video 반응있음 = 노출영상(location);
			Video 반응없음 = 노출영상(location);
			Long 다른사용자 = 사용자();
			em.flush();
			commentRepository.save(EventVideoComment.create(
				eventVideoRepository.findById(반응있음.getId()).orElseThrow(), userId, "잘 봤어요", NOW));
			helpfulRepository.insertHelpful(반응있음.getId(), userId);
			helpfulRepository.insertHelpful(반응있음.getId(), 다른사용자);
			em.flush();

			List<Long> ids = List.of(반응있음.getId(), 반응없음.getId());
			assertThat(맵(commentRepository.countCommentsByVideoIds(ids)))
				.containsExactly(Map.entry(반응있음.getId(), 1L));
			assertThat(맵(helpfulRepository.countHelpfulsByVideoIds(ids)))
				.containsExactly(Map.entry(반응있음.getId(), 2L));
			assertThat(commentRepository.countByVideo_VideoId(반응있음.getId())).isEqualTo(1);
			assertThat(commentRepository.countByVideo_VideoId(반응없음.getId())).isZero();
		}

		private Map<Long, Long> 맵(List<EventVideoReactionCount> rows) {
			return rows.stream()
				.collect(Collectors.toMap(EventVideoReactionCount::videoId, EventVideoReactionCount::count));
		}
	}
}
