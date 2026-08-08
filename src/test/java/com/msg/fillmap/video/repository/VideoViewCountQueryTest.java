package com.msg.fillmap.video.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Point;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.support.GeoSupport;

/**
 * 재생 조회수 원자적 증가(MSG-206 D5) 를 실 PostGIS 로 검증한다. 원자성 검증(동시 호출)은 실제 커밋 경계가
 * 필요하므로 @Transactional 롤백 격리를 쓰지 않고, 커밋한 합성 행(서해 공해상 격자·이 유저)을 @AfterEach 에서
 * 대상 지정 삭제로 걷어낸다(truncate 아님 — 실데이터 불가침). VideoRegionStatsAtomicityTest 와 같은 방식.
 */
@SpringBootTest
@DisplayName("VideoRepository 재생 조회수 증가 (실 PostGIS)")
class VideoViewCountQueryTest {

	// VideoRegionStatsAtomicityTest(17810_7751) 와 겹치지 않는 별도 서해 공해상 격자.
	private static final long GY = 17810L;
	private static final long GX = 7772L;

	@Autowired
	private VideoRepository videoRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@Autowired
	private PlatformTransactionManager txManager;

	private TransactionTemplate tx;
	private long userId;
	private String gridId;
	private long videoId;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(txManager);
		tx.executeWithoutResult(status -> {
			userId = userRepository.save(User.createLocalUser("view-count@example.com", "hash", "조회수")).getId();
			gridId = GridFixtures.seedGrid(em, GY, GX);
			videoId = insertVideo();
		});
	}

	@AfterEach
	void tearDown() {
		tx.executeWithoutResult(status -> {
			exec("DELETE FROM videos WHERE user_id = :u", "u", userId);
			exec("DELETE FROM grids WHERE grid_id = :v", "v", gridId);
			exec("DELETE FROM users WHERE id = :u", "u", userId);
		});
	}

	/** view_count 기본 0(Video 생성자)으로 저장한 영상 하나. */
	private long insertVideo() {
		GridPoint center = GridFixtures.pointAt(GY + 0.5, GX + 0.5);
		Point geom = GeoSupport.toPoint(center.lat(), center.lon());
		String key = "videos/original/" + UUID.randomUUID() + ".mp4";   // uq_videos_original_s3_key
		return videoRepository.save(
			Video.create(userId, gridId, key, geom, (short) 10, LocalDateTime.now(), Visibility.PRIVATE)).getId();
	}

	private long viewCount() {
		return tx.execute(status -> ((Number) em.createNativeQuery("SELECT view_count FROM videos WHERE id = :id")
			.setParameter("id", videoId).getSingleResult()).longValue());
	}

	private void exec(String sql, String param, Object value) {
		em.createNativeQuery(sql).setParameter(param, value).executeUpdate();
	}

	@Test
	@DisplayName("incrementViewCount 는 view_count 를 1 증가시킨다")
	void incrementViewCount는_view_count를_1_증가시킨다() {
		tx.executeWithoutResult(status -> videoRepository.incrementViewCount(videoId));

		assertThat(viewCount()).isEqualTo(1L);
	}

	@Test
	@DisplayName("incrementViewCount 동시 호출도 원자적으로 누락없이 증가한다")
	void incrementViewCount_동시_호출도_원자적으로_누락없이_증가한다() throws Exception {
		int threads = 10;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch go = new CountDownLatch(1);
		List<Throwable> errors = new CopyOnWriteArrayList<>();
		List<Future<?>> futures = new ArrayList<>();
		try {
			for (int i = 0; i < threads; i++) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					try {
						go.await();
						// 각 증가를 독립 트랜잭션으로 커밋해 실제 동시 UPDATE 를 만든다.
						tx.executeWithoutResult(status -> videoRepository.incrementViewCount(videoId));
					} catch (Throwable t) {
						errors.add(t);
					}
				}));
			}
			ready.await(5, TimeUnit.SECONDS);
			go.countDown();   // 전 스레드 동시 출발
			for (Future<?> f : futures) {
				f.get(30, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdownNow();
			pool.awaitTermination(10, TimeUnit.SECONDS);
		}

		assertThat(errors).isEmpty();
		// read-modify-write 라면 lost update 로 threads 미만이 된다 — 원자 UPDATE 라 정확히 threads 다.
		assertThat(viewCount()).isEqualTo((long) threads);
	}
}
