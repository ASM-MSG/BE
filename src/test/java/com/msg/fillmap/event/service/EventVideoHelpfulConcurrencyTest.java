package com.msg.fillmap.event.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.event.EventTestFixtures;
import com.msg.fillmap.event.entity.EventLocation;
import com.msg.fillmap.event.entity.EventOccurrence;
import com.msg.fillmap.event.entity.EventSeries;
import com.msg.fillmap.event.entity.EventVideo;
import com.msg.fillmap.event.repository.EventLocationGridRepository;
import com.msg.fillmap.event.repository.EventLocationRepository;
import com.msg.fillmap.event.repository.EventOccurrenceRepository;
import com.msg.fillmap.event.repository.EventSeriesRepository;
import com.msg.fillmap.event.repository.EventVideoCommentRepository;
import com.msg.fillmap.event.repository.EventVideoHelpfulRepository;
import com.msg.fillmap.event.repository.EventVideoRepository;
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
 * 도움돼요 쓰기 두 종의 동시 요청 (MSG-441 §도메인 로직 "사용자당 1회와 멱등"). 같은 사용자의 추가 두 건과
 * 취소 두 건이 각자의 트랜잭션으로 겹쳐 들어와도 결과가 같아야 한다 — 관측 대상이 "다른 커넥션이 커밋한 행"
 * 이라 실제 커밋과 두 스레드가 필요하다(MSG-440 이 업로드 경쟁을 별도 클래스로 나눈 것과 같은 구조).
 * <p>
 * 취소가 이 테스트의 핵심이다. {@code deleteById} 는 SELECT 후 DELETE 두 문장이라 진 쪽의 DELETE 가 0행이
 * 되어 StaleStateException(공통 500)이 되는데, native 1문장 DELETE 는 0행도 그냥 성공이다.
 * <p>
 * 격리(공유 로컬 DB): 실제 커밋을 하므로 {@code @AfterEach} 에서 이 테스트가 만든 id 만 FK 역순으로 지운다.
 */
@SpringBootTest
@DisplayName("행사 영상 도움돼요 동시성 (실 PostgreSQL)")
class EventVideoHelpfulConcurrencyTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 1, 0, 0);

	/** 서해 먼바다 기준 격자 — 다른 행사 테스트(125.0~125.8)와 겹치지 않는 대역. */
	private static final GridIndex 바다 = GridEncoder.decode(GridEncoder.encode(34.0, 125.9));

	private static final long JOIN_TIMEOUT_MS = 15_000L;

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

	@Autowired
	private PlatformTransactionManager transactionManager;

	private TransactionTemplate tx;
	private EventTestFixtures fixtures;
	private Long userId;
	private Long seriesId;
	private Long occurrenceId;
	private Long locationId;
	private Long videoId;
	private String gridId;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(transactionManager);
		fixtures = new EventTestFixtures(seriesRepository, occurrenceRepository, locationRepository,
			locationGridRepository);
		gridId = 바다.gridY() + "_" + 바다.gridX();
		tx.executeWithoutResult(status -> {
			userId = userRepository.save(User.createLocalUser(
				"msg441-race-" + UUID.randomUUID() + "@example.com", "hash", "동시반응자")).getId();
			EventSeries series = fixtures.시리즈();
			seriesId = series.getId();
			EventOccurrence occurrence = fixtures.회차(series, NOW.minusDays(1), NOW.plusDays(1), gridId);
			occurrenceId = occurrence.getId();
			EventLocation location = fixtures.위치(occurrence, "행사 위치", gridId);
			locationId = location.getId();
			videoId = 노출영상(location).getId();
		});
	}

	/** 실제로 커밋하므로 이 테스트가 만든 행만 id 로 지목해 FK 역순 삭제한다 (실데이터 불가침). */
	@AfterEach
	void 정리() {
		tx.executeWithoutResult(status -> List.of(
			"DELETE FROM event_video_helpfuls WHERE video_id = " + videoId,
			"DELETE FROM event_video_comments WHERE video_id = " + videoId,
			"DELETE FROM event_videos WHERE video_id = " + videoId,
			"DELETE FROM videos WHERE id = " + videoId,
			"DELETE FROM event_location_grids WHERE event_location_id = " + locationId,
			"DELETE FROM event_locations WHERE id = " + locationId,
			"DELETE FROM event_occurrences WHERE id = " + occurrenceId,
			"DELETE FROM event_series WHERE id = " + seriesId,
			"DELETE FROM grids WHERE grid_id = '" + gridId + "'",
			"DELETE FROM users WHERE id = " + userId
		).forEach(sql -> em.createNativeQuery(sql).executeUpdate()));
	}

	private Video 노출영상(EventLocation location) {
		GridPoint center = GridEncoder.center(gridId);
		videoRepository.upsertGrid(gridId, 바다.gridY(), 바다.gridX(), center.lat(), center.lon(),
			GeoSupport.bboxWkt(gridId));
		Video video = videoRepository.save(Video.create(userId, gridId,
			"videos/original/" + UUID.randomUUID() + ".mp4", GeoSupport.toPoint(center.lat(), center.lon()),
			(short) 10, NOW, Visibility.PUBLIC));
		video.markReady("videos/encoded/" + UUID.randomUUID() + ".mp4", "thumb/" + UUID.randomUUID() + ".jpg",
			video.getDurationSec());
		eventVideoRepository.save(new EventVideo(video, location, location.getOccurrence().getId()));
		return video;
	}

	private EventVideoInteractionService service() {
		return new EventVideoInteractionServiceImpl(eventVideoRepository, commentRepository, helpfulRepository,
			videoRepository, Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
	}

	/** 같은 호출을 두 스레드가 동시에 시작하도록 래치로 맞춘 뒤 각자의 트랜잭션에서 돌린다. */
	private List<Exception> 동시_두_건(Runnable 호출) throws InterruptedException {
		CountDownLatch 출발 = new CountDownLatch(1);
		AtomicReference<Exception> 왼쪽 = new AtomicReference<>();
		AtomicReference<Exception> 오른쪽 = new AtomicReference<>();
		Thread a = 스레드(호출, 출발, 왼쪽, "msg441-race-a");
		Thread b = 스레드(호출, 출발, 오른쪽, "msg441-race-b");
		a.start();
		b.start();
		출발.countDown();
		a.join(JOIN_TIMEOUT_MS);
		b.join(JOIN_TIMEOUT_MS);
		return Stream.of(왼쪽.get(), 오른쪽.get()).filter(Objects::nonNull).toList();
	}

	private Thread 스레드(Runnable 호출, CountDownLatch 출발, AtomicReference<Exception> 예외, String 이름) {
		return new Thread(() -> {
			try {
				출발.await();
				new TransactionTemplate(transactionManager).executeWithoutResult(status -> 호출.run());
			} catch (Exception e) {
				예외.set(e);
			}
		}, 이름);
	}

	@Test
	@DisplayName("동시에 같은 사용자의 추가 두 건이 들어와도 행은 하나다")
	void 동시에_같은_사용자의_추가_두_건이_들어와도_행은_하나다() throws InterruptedException {
		List<Exception> 예외 = 동시_두_건(() -> service().addHelpful(userId, videoId));

		// 복합 PK 가 유일성을, ON CONFLICT DO NOTHING 이 중복 키 예외를 각각 막는다.
		assertThat(예외).isEmpty();
		assertThat(helpfulRepository.countById_VideoId(videoId)).isEqualTo(1);
	}

	@Test
	@DisplayName("동시에 같은 사용자의 취소 두 건이 들어와도 양쪽 다 성공한다")
	void 동시에_같은_사용자의_취소_두_건이_들어와도_양쪽_다_성공한다() throws InterruptedException {
		tx.executeWithoutResult(status -> service().addHelpful(userId, videoId));

		List<Exception> 예외 = 동시_두_건(() -> service().removeHelpful(userId, videoId));

		// native 1문장 DELETE 라 진 쪽은 0행 성공이다 — deleteById(SELECT 후 DELETE)면 여기서 500 이 난다.
		assertThat(예외).isEmpty();
		assertThat(helpfulRepository.countById_VideoId(videoId)).isZero();
	}
}
