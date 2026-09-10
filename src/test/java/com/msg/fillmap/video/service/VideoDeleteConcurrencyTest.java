package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;

import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.GeoSupport;

/**
 * 같은 영상 동시 삭제 회귀 (MSG-243, 실 PostGIS). 삭제 전이가 잠금 없는 read-then-check 라면 두 트랜잭션이
 * 모두 멱등 가드를 통과해 video_count 가 이중 감소하고, 격자에 ACTIVE 영상(B)이 남았는데 user_grids 가
 * 삭제(점령 오롤백)된다. findWithLockById 행 잠금이 전이를 직렬화해 승자 1명만 감소를 실행하는지 검증한다.
 *
 * <p>결정적 인터리빙 프로토콜(RegionStatsConcurrencyTest/MSG-165 재사용, 역할은 비대칭): 승자 워커가
 * 트랜잭션 안에서 deleteVideo 를 실행(videos 행 잠금 보유)한 뒤 커밋을 보류하고 플래그를 올린다 → 패자
 * 워커가 플래그를 보고 deleteVideo 에 진입해 승자의 videos 행 잠금에 블록된다(수정 전엔 markDeleted flush
 * 의 UPDATE 에서, 수정 후엔 잠금 로드 SELECT ... FOR UPDATE 에서) → 승자는 제3의 커넥션(JdbcTemplate)으로
 * "내 pid 가 다른 세션을 막고 있는가"(pg_blocking_pids)를 관측한 뒤에야 커밋 → 패자 진행. 고정 sleep 없이
 * 실제 락 경합 관측이라 수정 전 코드에선 이중 감소가, 수정 후엔 패자의 멱등 반환이 결정적으로 재현된다.
 *
 * <p>격리: 서해 공해상 합성 격자(40243 대역 — m165 의 40000 대역과 분리) + m243 접두 유저. 두 워커가 각자
 * 커밋하므로 @Transactional 롤백을 못 쓴다 — @AfterEach 에서 자기 행만 FK 역순 대상 지정 삭제. S3Client 는
 * 목 — 커밋 후 deleteQuietly 가 실 S3 로 나가지 않게 빈 응답을 스텁한다.
 */
@SpringBootTest
@DisplayName("영상 동시 삭제 — 행 잠금이 삭제 전이를 직렬화한다 (실 PostGIS)")
class VideoDeleteConcurrencyTest {

	// 서해 공해상 합성 격자 인덱스 — 실데이터·다른 동시성 트랙(m165: 40000 대역)과 겹치지 않는 대역.
	private static final long GY = 18054L;
	private static final long GX = 7721L;

	private static final long POLL_INTERVAL_MS = 20L;
	private static final long PROTOCOL_TIMEOUT_SEC = 15L;
	private static final long JOIN_TIMEOUT_SEC = 30L;

	@Autowired
	private VideoService videoService;

	@Autowired
	private VideoRepository videoRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager txManager;

	@MockitoBean
	private S3Client s3Client;

	private TransactionTemplate tx;
	private long userId;
	private String gridId;
	private long videoAId;
	private long videoBId;

	@BeforeEach
	void setUp() {
		// 커밋이 실제 발생하므로 afterCommit 의 deleteQuietly 가 목을 호출한다 — 빈 응답(에러 없음)으로 스텁.
		given(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
			.willReturn(DeleteObjectsResponse.builder().build());
		tx = new TransactionTemplate(txManager);
		// user·grids·videos·user_grids 는 먼저 커밋해 둔다 — 두 워커 트랜잭션은 삭제 전이만 다룬다.
		tx.executeWithoutResult(status -> {
			userId = userRepository.save(
				User.createLocalUser("m243-concurrency@example.com", "hash", "m243동시")).getId();
			gridId = GridFixtures.seedGrid(em, GY, GX);
			videoAId = saveActiveVideo("a");
			videoBId = saveActiveVideo("b");
			videoRepository.upsertUserGrid(userId, gridId, videoAId);   // 첫 점령: count=1, cover=A
			videoRepository.upsertUserGrid(userId, gridId, videoAId);   // 재방문: count=2
		});
	}

	@AfterEach
	void tearDown() {
		// 커밋해 둔 합성 행만 FK 역순으로 대상 지정 삭제한다(실데이터 불가침).
		tx.executeWithoutResult(status -> {
			exec("DELETE FROM region_stats WHERE user_id = :u", "u", userId);
			exec("DELETE FROM user_grids WHERE user_id = :u", "u", userId);
			exec("DELETE FROM videos WHERE user_id = :u", "u", userId);
			exec("DELETE FROM grids WHERE grid_id = :g", "g", gridId);
			exec("DELETE FROM users WHERE id = :u", "u", userId);
		});
	}

	// 검증: FR-COLLECT-06
	@Test
	@DisplayName("같은 영상 동시 삭제 2건이면 video_count 는 1만 감소하고 점령이 유지된다")
	void 같은_영상_동시_삭제_2건이면_video_count_는_1만_감소하고_점령이_유지된다() throws Exception {
		List<Throwable> loserErrors = new CopyOnWriteArrayList<>();
		AtomicBoolean winnerLocked = new AtomicBoolean(false);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			// 승자: 삭제 실행(잠금 보유) → 패자가 내 잠금에 블록된 것을 관측 → 커밋.
			Future<?> winner = pool.submit(() -> tx.executeWithoutResult(status -> {
				em.createNativeQuery("SET LOCAL lock_timeout = '20s'").executeUpdate();
				videoService.deleteVideo(userId, videoAId);
				int myPid = ((Number) em.createNativeQuery("SELECT pg_backend_pid()").getSingleResult()).intValue();
				winnerLocked.set(true);
				awaitBlocking(myPid);
			}));
			// 패자: 승자가 잠금을 보유한 뒤에야 진입 — 블록됐다 승자 커밋 후 진행한다.
			Future<?> loser = pool.submit(() -> {
				awaitWinnerLocked(winnerLocked);
				try {
					tx.executeWithoutResult(status -> {
						em.createNativeQuery("SET LOCAL lock_timeout = '20s'").executeUpdate();
						videoService.deleteVideo(userId, videoAId);
					});
				} catch (Throwable t) {
					loserErrors.add(t);
				}
			});
			winner.get(JOIN_TIMEOUT_SEC, TimeUnit.SECONDS);
			loser.get(JOIN_TIMEOUT_SEC, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
			if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("워커 스레드가 종료되지 않음 — teardown 오염 위험");
			}
		}

		// 패자 멱등 성공 (MSG-72 D7 계약 유지) — 예외를 던지면 안 된다.
		assertThat(loserErrors).as("동시 삭제의 패자는 예외 없이 멱등 성공한다").isEmpty();
		assertThat(statusOf(videoAId)).isEqualTo("DELETED");
		assertThat(statusOf(videoBId)).as("삭제하지 않은 B 는 ACTIVE 유지").isEqualTo("ACTIVE");
		// 수정 전 코드면 이중 감소(2→0)로 user_grids 가 삭제돼 여기서 결정적으로 실패한다 — 점령 오롤백.
		assertThat(userGridRows()).as("ACTIVE 영상 B 가 남았으므로 점령 유지").isEqualTo(1);
		assertThat(videoCount()).as("감소는 정확히 1회 (2→1)").isEqualTo(1);
	}

	private long saveActiveVideo(String suffix) {
		GridPoint center = GridFixtures.pointAt(GY + 0.5, GX + 0.5);
		double lat = center.lat();
		double lon = center.lon();
		return videoRepository.save(Video.create(
			userId, gridId, "videos/original/%d/m243-%s.mp4".formatted(userId, suffix),
			GeoSupport.toPoint(lat, lon), (short) 10, LocalDateTime.now(), Visibility.PRIVATE)).getId();
	}

	/**
	 * 커밋해도 되는 신호를 결정적으로 기다린다: 내 백엔드 pid 가 다른 세션(패자)을 막고 있음이 관측될 때.
	 * 고정 지연이 아니라 실제 락 경합 관측이라 스케줄링 지연과 무관하게 성립한다. 타임아웃/인터럽트 시엔
	 * 조용히 커밋하지 않고 예외로 트랜잭션을 중단시킨다(모호 상태 은폐 금지).
	 */
	private void awaitBlocking(int myPid) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROTOCOL_TIMEOUT_SEC);
		while (System.nanoTime() < deadline) {
			if (blocksAnotherSession(myPid)) {
				return;
			}
			if (!sleepQuietly(POLL_INTERVAL_MS)) {
				throw new IllegalStateException("커밋 신호 대기 중 인터럽트 — 트랜잭션 중단");
			}
		}
		throw new IllegalStateException("커밋 신호 대기 타임아웃(" + PROTOCOL_TIMEOUT_SEC + "s): pid=" + myPid
			+ " 가 아무 세션도 막지 못함 — 패자가 videos 행 잠금에 블록되는 경합 구도가 형성되지 않았다");
	}

	/** 내 백엔드 pid 가 (다른) 어떤 세션의 blocker 인지 — pg_blocking_pids 로 내 pid 스코프 정밀 판정. */
	private boolean blocksAnotherSession(int myPid) {
		Long blocked = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM pg_stat_activity WHERE pid <> ? AND ? = ANY(pg_blocking_pids(pid))",
			Long.class, myPid, myPid);
		return blocked != null && blocked > 0;
	}

	private static void awaitWinnerLocked(AtomicBoolean winnerLocked) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROTOCOL_TIMEOUT_SEC);
		while (System.nanoTime() < deadline) {
			if (winnerLocked.get()) {
				return;
			}
			if (!sleepQuietly(POLL_INTERVAL_MS)) {
				throw new IllegalStateException("승자 잠금 대기 중 인터럽트");
			}
		}
		throw new IllegalStateException("승자 잠금 대기 타임아웃(" + PROTOCOL_TIMEOUT_SEC + "s)");
	}

	private static boolean sleepQuietly(long millis) {
		try {
			Thread.sleep(millis);
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private String statusOf(long videoId) {
		return jdbcTemplate.queryForObject("SELECT status FROM videos WHERE id = ?", String.class, videoId);
	}

	private long userGridRows() {
		Long rows = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM user_grids WHERE user_id = ? AND grid_id = ?", Long.class, userId, gridId);
		return rows == null ? 0 : rows;
	}

	private Integer videoCount() {
		return jdbcTemplate.queryForObject(
			"SELECT video_count FROM user_grids WHERE user_id = ? AND grid_id = ?", Integer.class, userId, gridId);
	}

	private void exec(String sql, String param, Object value) {
		em.createNativeQuery(sql).setParameter(param, value).executeUpdate();
	}
}
