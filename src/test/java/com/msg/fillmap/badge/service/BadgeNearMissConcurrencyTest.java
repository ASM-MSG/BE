package com.msg.fillmap.badge.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.badge.entity.BadgeConditionType;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 뱃지 임박 하루 1건 상한의 경합 제어 회귀 (docs/MSG-314.md D3, 실 PostGIS). 잠금이 비대기
 * (pg_try_advisory_xact_lock — 블로킹이면 업로드 트랜잭션의 streaks 행 잠금과 순서 사이클로 데드락,
 * Codex 1R P2)라 블로킹 대기 관찰 프로토콜(RegionStatsConcurrencyTest의 pg_blocking_pids)이 성립하지
 * 않는다. 대신 별도 raw 커넥션이 같은 키('badge_near:{userId}')의 잠금을 쥔 상태를 고정해 두 국면을
 * 스레드 없이 결정적으로 관찰한다: 잠금 보유 중의 임박 시도는 기록 없이 스킵되고(경합 패자 재현),
 * 해제 후의 시도는 정상 기록된다. 최종 불변식은 "하루 1건 이하"다 — try 잠금이 깨지면(회귀) 보유 중
 * 시도가 기록되거나 대기로 멎는다.
 *
 * <p>격리: 합성 유저 1명만 커밋한다 (판정은 metric 직접 주입이라 격자·영상 시딩 불요). 커밋이 실제
 * 발생하므로 @Transactional 롤백을 못 쓴다 — @AfterEach 의 users 지정 삭제가 notifications 를
 * CASCADE 로 걷어낸다.
 */
@SpringBootTest
@DisplayName("뱃지 임박 경합 제어 — try 잠금을 못 잡은 시도는 스킵돼 하루 1건이 지켜진다 (실 PostGIS)")
class BadgeNearMissConcurrencyTest {

	@Autowired
	private BadgeAwardService badgeAwardService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private EntityManager em;

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
	@DisplayName("다른 세션이 임박 잠금을 쥔 동안의 시도는 스킵되고, 해제 후 시도만 기록된다 — 하루 1건 이하")
	void 동시_업로드가_서로_다른_뱃지로_임박해도_하루_1건이_지켜진다() throws Exception {
		// 홀더 세션: 같은 사용자의 다른 업로드가 임박 기록 구간을 진행 중인 상황을 트랜잭션 열린 raw
		// 커넥션으로 고정한다 (xact advisory 잠금은 커밋/롤백까지 유지).
		try (Connection holder = dataSource.getConnection()) {
			holder.setAutoCommit(false);
			assertThat(tryBadgeNearLock(holder)).isTrue();

			// 경합 패자: 9번째 업로드(RECORDER_10 임박)가 try 잠금 실패로 그 시도의 기록만 스킵한다.
			badgeAwardService.award(userId, BadgeConditionType.UPLOAD_COUNT, BigDecimal.valueOf(9));

			assertThat(nearCount()).isZero();
			holder.rollback();
		}

		// 잠금 해제 후: 다른 뱃지(EXPLORER_10)의 임박이 정상 기록된다 — 최종 불변식 "하루 1건 이하".
		badgeAwardService.award(userId, BadgeConditionType.TOTAL_GRIDS, BigDecimal.valueOf(9));

		assertThat(nearCount()).isEqualTo(1);
	}

	/** 서비스와 동일한 키 산식으로 홀더 세션이 try 잠금을 잡는다 (UserBadgeRepository.tryAcquireBadgeNearLock 동형). */
	private boolean tryBadgeNearLock(Connection holder) throws SQLException {
		try (PreparedStatement ps = holder.prepareStatement(
			"SELECT pg_try_advisory_xact_lock(hashtextextended('badge_near:' || ?, 0))")) {
			ps.setLong(1, userId);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getBoolean(1);
			}
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
