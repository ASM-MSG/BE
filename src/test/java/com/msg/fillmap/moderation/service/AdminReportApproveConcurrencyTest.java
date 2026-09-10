package com.msg.fillmap.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.moderation.entity.Report;
import com.msg.fillmap.moderation.entity.ReportReason;
import com.msg.fillmap.moderation.exception.ReportErrorCode;
import com.msg.fillmap.moderation.repository.ReportRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.GeoSupport;

/**
 * 같은 신고 동시 승인 경쟁 (MSG-195 FR-10, 실 PostGIS). reports 행 잠금(findWithLockById)이 처리를
 * 직렬화해 승자 1명만 종결하고, 패자는 잠금 대기 후 RESOLVED 를 읽어 11410 을 받는지 검증한다.
 * 잠금이 없다면 두 트랜잭션 모두 PENDING 사전 확인을 통과해 늦은 쪽이 reviewed_by 를 덮어쓴다.
 *
 * <p>결정적 인터리빙 프로토콜(VideoDeleteConcurrencyTest/MSG-243 재사용): 승자 워커가 트랜잭션 안에서
 * approve 를 실행(reports 행 잠금 보유)한 뒤 커밋을 보류하고 플래그를 올린다 → 패자 워커가 플래그를 보고
 * approve 에 진입해 승자의 reports 행 잠금에 블록된다 → 승자는 제3의 커넥션(JdbcTemplate)으로 "내 pid 가
 * 다른 세션을 막고 있는가"(pg_blocking_pids)를 관측한 뒤에야 커밋 → 패자 진행. 고정 sleep 없이 실제 락
 * 경합 관측이라 결정적으로 재현된다.
 *
 * <p>격리: 서해 공해상 합성 격자(40195 대역 — m243 의 40243 대역과 분리) + m195c 접두 유저. 두 워커가
 * 각자 커밋하므로 @Transactional 롤백을 못 쓴다 — @AfterEach 에서 자기 행만 FK 역순 대상 지정 삭제.
 * 승인 경로는 S3 를 건드리지 않아 목이 필요 없다.
 */
@SpringBootTest
@DisplayName("신고 동시 승인 — 행 잠금이 처리를 직렬화한다 (실 PostGIS)")
class AdminReportApproveConcurrencyTest {

	// 서해 공해상 합성 격자 인덱스 — 실데이터·다른 동시성 트랙(m243: 18054 대역)과 겹치지 않는 대역.
	private static final long GY = 18006L;
	private static final long GX = 7720L;

	private static final long POLL_INTERVAL_MS = 20L;
	private static final long PROTOCOL_TIMEOUT_SEC = 15L;
	private static final long JOIN_TIMEOUT_SEC = 30L;

	@Autowired
	private AdminReportService adminReportService;

	@Autowired
	private ReportRepository reportRepository;

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

	private TransactionTemplate tx;
	private long reporterId;
	private long ownerId;
	private long winnerAdminId;
	private long loserAdminId;
	private String gridId;
	private long videoId;
	private long reportId;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(txManager);
		// user·grids·videos·reports 는 먼저 커밋해 둔다 — 두 워커 트랜잭션은 승인 처리만 다룬다.
		tx.executeWithoutResult(status -> {
			reporterId = seedUser("reporter", "m195c신고자");
			ownerId = seedUser("owner", "m195c영상주인");
			winnerAdminId = seedUser("winner", "m195c승자관리자");
			loserAdminId = seedUser("loser", "m195c패자관리자");
			gridId = GridFixtures.seedGrid(em, GY, GX);
			GridPoint center = GridFixtures.pointAt(GY + 0.5, GX + 0.5);
			videoId = videoRepository.save(Video.create(
				ownerId, gridId, "videos/original/%d/m195c.mp4".formatted(ownerId),
				GeoSupport.toPoint(center.lat(), center.lon()), (short) 10, LocalDateTime.now(),
				Visibility.PUBLIC)).getId();
			reportId = reportRepository.save(
				Report.create(reporterId, videoId, ReportReason.INAPPROPRIATE, null)).getId();
		});
	}

	@AfterEach
	void tearDown() {
		// 커밋해 둔 합성 행만 FK 역순으로 대상 지정 삭제한다(실데이터 불가침).
		tx.executeWithoutResult(status -> {
			exec("DELETE FROM reports WHERE id = :r", "r", reportId);
			exec("DELETE FROM videos WHERE id = :v", "v", videoId);
			exec("DELETE FROM grids WHERE grid_id = :g", "g", gridId);
			for (long userId : List.of(reporterId, ownerId, winnerAdminId, loserAdminId)) {
				exec("DELETE FROM users WHERE id = :u", "u", userId);
			}
		});
	}

	// 검증: FR-MOD-12
	@Test
	@DisplayName("같은 신고의 동시 승인은 한 건만 성공하고 늦은 쪽은 409 를 받는다")
	void 같은_신고의_동시_승인은_한_건만_성공한다() throws Exception {
		List<Throwable> loserErrors = new CopyOnWriteArrayList<>();
		AtomicBoolean winnerLocked = new AtomicBoolean(false);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			// 승자: 승인 실행(reports 행 잠금 보유) → 패자가 내 잠금에 블록된 것을 관측 → 커밋.
			Future<?> winner = pool.submit(() -> tx.executeWithoutResult(status -> {
				em.createNativeQuery("SET LOCAL lock_timeout = '20s'").executeUpdate();
				adminReportService.approve(winnerAdminId, reportId);
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
						adminReportService.approve(loserAdminId, reportId);
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

		// 패자는 잠금 대기 후 RESOLVED 를 읽고 11410 으로 거부된다 (FR-7·FR-10).
		assertThat(loserErrors).as("동시 승인의 패자는 정확히 한 번 거부된다").hasSize(1);
		assertThat(loserErrors.getFirst()).isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", ReportErrorCode.ALREADY_PROCESSED_REPORT);
		assertThat(reportRow("status")).isEqualTo("RESOLVED");
		// 잠금이 없다면 패자가 reviewed_by 를 덮어써 여기서 결정적으로 실패한다.
		assertThat(((Number) reportRow("reviewed_by")).longValue())
			.as("처리자 기록은 승자의 것만 남는다").isEqualTo(winnerAdminId);
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM videos WHERE id = ?", String.class, videoId))
			.isEqualTo("BLINDED");
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
			+ " 가 아무 세션도 막지 못함 — 패자가 reports 행 잠금에 블록되는 경합 구도가 형성되지 않았다");
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

	private long seedUser(String key, String nickname) {
		return userRepository.save(
			User.createLocalUser("m195c-" + key + "@example.com", "hash", nickname)).getId();
	}

	private Object reportRow(String column) {
		return jdbcTemplate.queryForObject("SELECT " + column + " FROM reports WHERE id = ?", Object.class, reportId);
	}

	private void exec(String sql, String param, Object value) {
		em.createNativeQuery(sql).setParameter(param, value).executeUpdate();
	}
}
