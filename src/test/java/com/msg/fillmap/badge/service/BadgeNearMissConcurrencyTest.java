package com.msg.fillmap.badge.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
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

import com.msg.fillmap.badge.entity.BadgeConditionType;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 뱃지 임박 하루 1건 상한 동시성 회귀 (docs/MSG-314.md D3, 실 PostGIS). 같은 사용자의 업로드 둘이 거의
 * 동시에 서로 다른 뱃지(RECORDER_10·EXPLORER_10)로 임박하는 두 트랜잭션을, 사용자 단위 advisory
 * 잠금('badge_near:')이 직렬화해 BADGE_NEAR 행이 1건으로 수렴하는지 검증한다. 잠금이 없으면 두 존재
 * 확인(countRecordedSince)이 서로의 미커밋 행을 못 보고 둘 다 기록해 2건이 된다 — PR #114 가 지적한
 * select-then-act 경합이 정확히 이 형태다.
 *
 * <p>결정적 대기 프로토콜 (RegionStatsConcurrencyTest 선례 축약): 배리어는 두 트랜잭션이 열린 뒤·award
 * 진입 직전에 둬 판정을 겹치게 한다. 각 워커는 award 리턴 직후 자기 done 플래그를 set 하고, 커밋 전에
 * 제3의 커넥션(JdbcTemplate)으로 "내 백엔드 pid 가 다른 세션을 막고 있는가"(pg_blocking_pids) 또는 상대
 * done 을 폴링한다. 잠금이 살아 있으면 선행이 후행의 advisory 대기를 관측한 뒤에야 커밋하므로 후행의
 * 존재 확인은 선행 커밋 뒤(새 스냅샷)에 실행돼 결정적으로 스킵한다. 잠금이 깨지면(회귀) 둘 다 블록 없이
 * award 를 마쳐 상대 done 으로 커밋 — 2건이 결정적으로 재현된다. 폴링 15s < lock_timeout 20s < join 30s.
 *
 * <p>격리: 합성 유저 1명만 커밋한다 (판정은 metric 직접 주입이라 격자·영상 시딩 불요). 커밋이 실제
 * 발생하므로 @Transactional 롤백을 못 쓴다 — @AfterEach 의 users 지정 삭제가 user_badges·notifications
 * 를 CASCADE 로 걷어낸다.
 */
@SpringBootTest
@DisplayName("뱃지 임박 동시성 — advisory 잠금이 하루 1건 상한을 직렬화한다 (실 PostGIS)")
class BadgeNearMissConcurrencyTest {

	private static final long POLL_INTERVAL_MS = 20L;
	private static final long PROTOCOL_TIMEOUT_SEC = 15L;
	private static final long JOIN_TIMEOUT_SEC = 30L;

	@Autowired
	private BadgeAwardService badgeAwardService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager txManager;

	private TransactionTemplate tx;
	private long userId;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(txManager);
		String email = "badge-near-conc-" + System.nanoTime() + "@example.com";
		tx.executeWithoutResult(status ->
			userId = userRepository.save(User.createLocalUser(email, "hash", "임박동시테스터")).getId());
	}

	@AfterEach
	void tearDown() {
		tx.executeWithoutResult(status ->
			em.createNativeQuery("DELETE FROM users WHERE id = :userId")
				.setParameter("userId", userId)
				.executeUpdate());
	}

	@Test
	@DisplayName("동시 업로드가 서로 다른 뱃지로 임박해도 잠금이 직렬화해 BADGE_NEAR 는 1건이다")
	void 동시_업로드가_서로_다른_뱃지로_임박해도_하루_1건이_지켜진다() throws Exception {
		CyclicBarrier barrier = new CyclicBarrier(2);
		List<Throwable> errors = new CopyOnWriteArrayList<>();
		AtomicBoolean aDone = new AtomicBoolean(false);
		AtomicBoolean bDone = new AtomicBoolean(false);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			// 업로드 9번째(RECORDER_10 임박) vs 격자 9개째(EXPLORER_10 임박) — 서로 다른 뱃지, 같은 KST 날.
			Future<?> f1 = pool.submit(nearMiss(BadgeConditionType.UPLOAD_COUNT, aDone, bDone, barrier, errors));
			Future<?> f2 = pool.submit(nearMiss(BadgeConditionType.TOTAL_GRIDS, bDone, aDone, barrier, errors));
			f1.get(JOIN_TIMEOUT_SEC, TimeUnit.SECONDS);
			f2.get(JOIN_TIMEOUT_SEC, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
			if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("워커 스레드가 종료되지 않음 — teardown 오염 위험");
			}
		}

		if (!errors.isEmpty()) {
			throw new AssertionError("워커 스레드에서 예외 발생", errors.get(0));
		}
		// 잠금이 직렬화했으면 후행의 존재 확인이 선행 커밋을 봐 스킵한다 (없으면 select-then-act 로 2건).
		tx.executeWithoutResult(status -> assertThat(nearCount()).isEqualTo(1));
	}

	/**
	 * 워커 하나: 자기 트랜잭션에서 (lock_timeout 설정 → 배리어 대기 → award 로 임박 판정 → done 플래그 set
	 * → 결정적 커밋 신호 대기) 후 커밋한다. award 는 반드시 서비스 경로로 — 리포지토리 직접 호출은 잠금을
	 * 우회한다. metric 9 는 두 축 모두 지급 후보 0건·임박 후보 1건이다.
	 */
	private Runnable nearMiss(BadgeConditionType type, AtomicBoolean myDone, AtomicBoolean peerDone,
		CyclicBarrier barrier, List<Throwable> errors) {
		return () -> {
			try {
				tx.executeWithoutResult(status -> {
					em.createNativeQuery("SET LOCAL lock_timeout = '20s'").executeUpdate();
					await(barrier);
					badgeAwardService.award(userId, type, BigDecimal.valueOf(9));
					int myPid = ((Number) em.createNativeQuery("SELECT pg_backend_pid()").getSingleResult()).intValue();
					myDone.set(true);
					awaitCommitSignal(myPid, peerDone);
				});
			} catch (Throwable t) {
				errors.add(t);
			}
		};
	}

	/**
	 * 커밋해도 되는 신호를 결정적으로 기다린다: (a) 내 백엔드 pid 가 다른 세션을 막고 있음(홀더 — 대기자가
	 * 내 advisory 잠금에 블록됨) 또는 (b) 상대 워커의 award 가 이미 리턴됨. 타임아웃/인터럽트 시엔 조용히
	 * 커밋하지 않고 예외로 트랜잭션을 중단시킨다 (RegionStatsConcurrencyTest 선례).
	 */
	private void awaitCommitSignal(int myPid, AtomicBoolean peerDone) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROTOCOL_TIMEOUT_SEC);
		while (System.nanoTime() < deadline) {
			if (blocksAnotherSession(myPid) || peerDone.get()) {
				return;
			}
			if (!sleepQuietly(POLL_INTERVAL_MS)) {
				throw new IllegalStateException("커밋 신호 대기 중 인터럽트 — 트랜잭션 중단");
			}
		}
		throw new IllegalStateException("커밋 신호 대기 타임아웃(" + PROTOCOL_TIMEOUT_SEC + "s): pid=" + myPid
			+ " 가 아무 세션도 막지 못했고 상대 award 도 안 끝남 — 잠금 경합 구도가 형성되지 않았다");
	}

	/** 내 백엔드 pid 가 (다른) 어떤 세션의 blocker 인지 — pg_blocking_pids 로 내 pid 스코프 정밀 판정. */
	private boolean blocksAnotherSession(int myPid) {
		Long blocked = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM pg_stat_activity WHERE pid <> ? AND ? = ANY(pg_blocking_pids(pid))",
			Long.class, myPid, myPid);
		return blocked != null && blocked > 0;
	}

	private static void await(CyclicBarrier barrier) {
		try {
			barrier.await(10, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new IllegalStateException("배리어 대기 실패", e);
		}
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

	private long nearCount() {
		return ((Number) em.createNativeQuery("""
				SELECT count(*) FROM notifications WHERE user_id = :userId AND event_key LIKE 'BADGE_NEAR:%'
				""")
			.setParameter("userId", userId)
			.getSingleResult()).longValue();
	}
}
