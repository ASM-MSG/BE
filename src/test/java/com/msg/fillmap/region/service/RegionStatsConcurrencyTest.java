package com.msg.fillmap.region.service;

import static com.msg.fillmap.region.RegionTestFixtures.CELL_AREA_M2;
import static com.msg.fillmap.region.RegionTestFixtures.cellBlockPolygonJson;
import static org.assertj.core.api.Assertions.assertThat;

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

import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.region.repository.RegionRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * region_stats recompute 동시성 회귀 (MSG-165, 실 PostGIS). 같은 사용자가 같은 행정동의 서로 다른 격자 2개를
 * 거의 동시에 첫 점령하는 두 트랜잭션이, per-user advisory 락(MSG-155 선반영)으로 recompute 가 직렬화돼
 * 최종 collected_count = 2 로 수렴하는지 검증한다. 락이 없으면 두 recompute 가 서로의 미커밋 user_grids 를
 * 못 보고 각자 1 을 써 lost update(최종 1)가 난다 — 이 테스트가 그 회귀를 잡는다(설계 근거는 docs/MSG-165.md).
 *
 * <p>결정적 대기 프로토콜(고정 sleep 없이 스케줄링 지연에 무관): 배리어는 두 user_grids 삽입 완료 직후·refresh
 * 진입 직전에 둬 두 미커밋 점령을 공존시킨다. 이어 각 워커는 refresh 리턴 직후 자기 done 플래그를 set 하고,
 * 커밋 전에 제3의 커넥션(JdbcTemplate, 트랜잭션 밖)으로 "내 백엔드 pid 가 다른 세션을 막고 있는가"를 폴링한다
 * (pg_blocking_pids — 내 pid 로 스코프해 다른 트랙 오탐 없음). 홀더는 대기자가 자기 락을 물고 블록된 걸 확인한
 * 뒤에야 커밋하므로, 대기자의 recompute SELECT 는 홀더 커밋 뒤(새 스냅샷)에 실행돼 결정적으로 2 를 센다.
 * 락이 깨지면(회귀) 대기자가 advisory 가 아니라 region_stats PK 충돌에 블록되는데 이 역시 "내 pid 가 막음"으로
 * 잡혀 홀더가 커밋 → 대기자는 자기 SELECT(=1, 홀더 미커밋)로 덮어써 최종 1 이 결정적으로 재현된다. 둘째 워커는
 * 상대 done 플래그로 커밋한다. pg_blocking_pids 로 두 락(advisory·행) 대기를 한 조건으로 통일하므로, advisory
 * 키만 보던 원안이 회귀 경로에서 타임아웃하던 문제를 없앤다. 폴링 타임아웃 시엔 조용히 커밋하지 않고 진단과
 * 함께 실패시킨다. 순서: 폴링 15s < lock_timeout 20s < join 30s.
 *
 * <p>격리: 실존하지 않는 99920 대역 region_code + 서해 공해상 합성 폴리곤/격자, m165 접두 유저. 커밋이 실제
 * 발생하므로 @Transactional 롤백을 못 쓴다 — @AfterEach 에서 자기 행만 FK 역순 대상 지정 삭제(truncate 금지).
 * 워커는 SET LOCAL lock_timeout 으로 DB 대기를 스스로 끊고, join 실패 시 executor 를 shutdownNow·종료 대기한
 * 뒤에만 teardown 이 돌게 해(try/finally) 살아있는 워커 트랜잭션이 정리와 경합하지 않게 한다.
 */
@SpringBootTest
@DisplayName("region_stats recompute 동시성 — per-user 락이 첫 점령 2건을 직렬화한다 (실 PostGIS)")
class RegionStatsConcurrencyTest {

	// 서해 공해상(lat ≈36.0, lon ≈124.96) 기준 격자 인덱스 — 육상 실데이터 행정동과 겹치지 않는 좌표 대역.
	private static final long GY0 = 17811L;
	private static final long GX0 = 7715L;

	// 실존하지 않는 99920 대역 합성 코드(153 트랙의 9993x·9994x 와도 분리). parent_code = 앞 5자리 "99920".
	private static final String REGION_CODE = "9992000165";

	private static final long POLL_INTERVAL_MS = 20L;
	private static final long PROTOCOL_TIMEOUT_SEC = 15L;
	private static final long JOIN_TIMEOUT_SEC = 30L;

	@Autowired
	private RegionStatsCommandService regionStatsCommandService;

	@Autowired
	private RegionRepository regionRepository;

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
	private String gridA;
	private String gridB;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(txManager);
		// region·user·grids 는 먼저 커밋해 둔다 — 두 워커 트랜잭션은 자기 user_grids 점령 row 만 삽입한다.
		tx.executeWithoutResult(status -> {
			userId = userRepository.save(User.createLocalUser("m165-concurrency@example.com", "hash", "m165동시")).getId();
			String polygon = cellBlockPolygonJson(GY0, GY0 + 3, GX0, GX0 + 3);
			regionRepository.upsert(REGION_CODE, "m165합성동", REGION_CODE.substring(0, 5), polygon, CELL_AREA_M2);
			// 같은 행정동 안의 서로 다른 두 격자 — region 을 먼저 upsert 했으므로 seedLabeledGrid 가 둘 다 REGION_CODE 로 라벨한다
			// (equi refreshRegionStats 가 이 저장 라벨을 읽는다).
			gridA = GridFixtures.seedLabeledGrid(em, GY0, GX0);
			gridB = GridFixtures.seedLabeledGrid(em, GY0, GX0 + 1);
		});
	}

	@AfterEach
	void tearDown() {
		// 커밋해 둔 합성 행만 FK 역순으로 대상 지정 삭제한다(실데이터 불가침).
		tx.executeWithoutResult(status -> {
			exec("DELETE FROM region_stats WHERE user_id = :u", "u", userId);
			exec("DELETE FROM user_grids WHERE user_id = :u", "u", userId);
			exec("DELETE FROM grids WHERE grid_id = :v", "v", gridA);
			exec("DELETE FROM grids WHERE grid_id = :v", "v", gridB);
			exec("DELETE FROM regions WHERE region_code = :v", "v", REGION_CODE);
			exec("DELETE FROM users WHERE id = :u", "u", userId);
		});
	}

	@Test
	@DisplayName("같은 행정동의 두 격자를 동시에 첫 점령해도 락이 recompute 를 직렬화해 collected_count 가 2 다")
	void 같은_행정동의_두_격자를_동시에_첫_점령해도_collected_count가_2다() throws Exception {
		CyclicBarrier barrier = new CyclicBarrier(2);
		List<Throwable> errors = new CopyOnWriteArrayList<>();
		AtomicBoolean aDone = new AtomicBoolean(false);
		AtomicBoolean bDone = new AtomicBoolean(false);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<?> f1 = pool.submit(occupy(gridA, aDone, bDone, barrier, errors));
			Future<?> f2 = pool.submit(occupy(gridB, bDone, aDone, barrier, errors));
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
		// 락이 직렬화했으면 뒤에 커밋한 recompute 가 앞 점령을 커밋 스냅샷으로 세어 2 가 된다(없으면 lost update 로 1).
		tx.executeWithoutResult(status -> assertThat(collectedCount()).isEqualTo(2));
	}

	/**
	 * 워커 하나: 자기 트랜잭션에서 (lock_timeout 설정 → 자기 격자 점령 삽입 → 배리어 대기 → 락 있는 경로로 refresh
	 * → done 플래그 set → 결정적 커밋 신호 대기) 후 커밋한다. refresh 는 반드시 락 있는 커맨드 경로로 — 리포지토리
	 * refreshRegionStats 직접 호출은 락을 우회한다.
	 */
	private Runnable occupy(String gridId, AtomicBoolean myDone, AtomicBoolean peerDone,
		CyclicBarrier barrier, List<Throwable> errors) {
		return () -> {
			try {
				tx.executeWithoutResult(status -> {
					em.createNativeQuery("SET LOCAL lock_timeout = '20s'").executeUpdate();
					GridFixtures.seedUserGrid(em, userId, gridId, 1);
					await(barrier);
					regionStatsCommandService.refresh(userId, gridId);
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
	 * 커밋해도 되는 신호를 결정적으로 기다린다: (a) 내 백엔드 pid 가 다른 세션을 막고 있음(홀더 — 대기자가 내
	 * advisory 락 또는 region_stats 행을 물고 블록됨) 또는 (b) 상대 워커의 refresh 가 이미 리턴됨(둘째 워커).
	 * 고정 지연이 아니라 실제 락 경합 관측이라 스케줄링 지연과 무관하게 성립한다. 타임아웃/인터럽트 시엔 조용히
	 * 커밋하지 않고 예외로 트랜잭션을 중단시킨다(모호 상태 은폐 금지).
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
			+ " 가 아무 세션도 막지 못했고 상대 refresh 도 안 끝남 — 락 경합 구도가 형성되지 않았다");
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

	private Integer collectedCount() {
		List<?> rows = em.createNativeQuery(
				"SELECT collected_count FROM region_stats WHERE user_id = :u AND region_code = :c")
			.setParameter("u", userId).setParameter("c", REGION_CODE).getResultList();
		return rows.isEmpty() ? null : ((Number) rows.get(0)).intValue();
	}

	private void exec(String sql, String param, Object value) {
		em.createNativeQuery(sql).setParameter(param, value).executeUpdate();
	}
}
