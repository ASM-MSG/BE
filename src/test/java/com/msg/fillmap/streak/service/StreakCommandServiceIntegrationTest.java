package com.msg.fillmap.streak.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.badge.dto.EarnedBadgeResponseDto;
import com.msg.fillmap.streak.repository.StreakRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 스트릭 갱신·뱃지 배선 통합 검증 (실 PostGIS, MSG-200 모듈 2). 공유 로컬 DB — 합성 유저만 만들고
 * @Transactional 롤백, badges 마스터(V10 시딩분)는 code 기준 단언만 한다. UPSERT 가 now() 기준이라
 * 시계 주입이 불가하므로 "어제/끊김" 시나리오는 fixture 행의 last_recorded_date 를 직접 어제/그제로
 * UPDATE 한 뒤 upsert 를 호출해 등가 검증한다(§테스트 시나리오 주석).
 */
@SpringBootTest
@Transactional
@DisplayName("StreakCommandService 스트릭 갱신·꾸준함 뱃지 배선 (실 PostGIS)")
class StreakCommandServiceIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	@Autowired
	private StreakCommandService streakCommandService;

	@Autowired
	private StreakRepository streakRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	private long userId;

	@BeforeEach
	void setUp() {
		String email = "streak-" + System.nanoTime() + "@example.com";
		userId = userRepository.save(User.createLocalUser(email, "hash", "스트릭테스터")).getId();
	}

	@Nested
	@DisplayName("갱신 판정 (§도메인 로직 1)")
	class 갱신_판정 {

		// 검증: FR-STREAK-03
		@Test
		@DisplayName("첫 업로드는 스트릭 행을 lazy 생성하고 1을 기록한다 (§D5)")
		void 첫_업로드는_스트릭_행을_생성하고_1을_기록한다() {
			streakCommandService.recordUpload(userId);

			Object[] row = streakRow();
			assertThat(((Number) row[0]).intValue()).isEqualTo(1);
			assertThat(((Number) row[1]).intValue()).isEqualTo(1);
			assertThat(row[2]).isEqualTo(LocalDate.now(KST));
		}

		// 검증: FR-STREAK-03
		@Test
		@DisplayName("같은 날 두 번째 업로드는 스트릭을 바꾸지 않는다 — 카운트 no-op")
		void 같은_날_두번째_업로드는_스트릭을_바꾸지_않는다() {
			streakCommandService.recordUpload(userId);
			streakCommandService.recordUpload(userId);

			Object[] row = streakRow();
			assertThat(((Number) row[0]).intValue()).isEqualTo(1);
			assertThat(((Number) row[1]).intValue()).isEqualTo(1);
		}

		// 검증: FR-STREAK-03
		@Test
		@DisplayName("어제 기록이 있으면 스트릭이 1 증가한다 — max 동반 갱신")
		void 어제_기록이_있으면_스트릭이_1_증가한다() {
			seedStreak(1, 1, 1);

			streakCommandService.recordUpload(userId);

			Object[] row = streakRow();
			assertThat(((Number) row[0]).intValue()).isEqualTo(2);
			assertThat(((Number) row[1]).intValue()).isEqualTo(2);
		}

		// 검증: FR-STREAK-03, FR-STREAK-06
		@Test
		@DisplayName("하루 이상 끊기면 스트릭이 1로 리셋된다 (§D2 freeze 미도입)")
		void 하루_이상_끊기면_스트릭이_1로_리셋된다() {
			seedStreak(5, 5, 2);

			streakCommandService.recordUpload(userId);

			assertThat(((Number) streakRow()[0]).intValue()).isEqualTo(1);
		}

		// 검증: FR-STREAK-03
		@Test
		@DisplayName("최대 스트릭은 리셋되어도 유지된다 — max_count 불변")
		void 최대_스트릭은_리셋되어도_유지된다() {
			seedStreak(5, 5, 2);

			streakCommandService.recordUpload(userId);

			Object[] row = streakRow();
			assertThat(((Number) row[0]).intValue()).isEqualTo(1);
			assertThat(((Number) row[1]).intValue()).isEqualTo(5);
		}

		// 검증: FR-STREAK-03
		@Test
		@DisplayName("동시 업로드에서도 스트릭 행은 하나만 생성된다 — ON CONFLICT 직렬화(§D5)")
		void 동시_업로드에서도_스트릭_행은_하나만_생성된다() {
			// 동시 요청이 같은 유저 행을 UPSERT 한 경합의 재현 — 두 번째는 ON CONFLICT 로 "같은 날" 분기.
			streakRepository.upsertOnUpload(userId);
			streakRepository.upsertOnUpload(userId);

			Number rows = (Number) em.createNativeQuery(
					"SELECT COUNT(*) FROM streaks WHERE user_id = :userId")
				.setParameter("userId", userId)
				.getSingleResult();
			Object[] row = streakRow();
			assertThat(rows.longValue()).isEqualTo(1);
			// 직렬화가 깨져 같은 날 2회가 2로 오르면 여기서 red 가 된다.
			assertThat(((Number) row[0]).intValue()).isEqualTo(1);
			assertThat(((Number) row[1]).intValue()).isEqualTo(1);
		}
	}

	@Nested
	@DisplayName("KST 자정 경계 판정 (FR-STREAK-02)")
	class KST_자정_경계_판정 {

		// 검증: FR-STREAK-02
		@Test
		@DisplayName("KST 자정 직전(어제) 업로드와 직후(오늘) 업로드는 다른 날로 판정되어 스트릭이 이어진다")
		void 자정_직전_업로드와_직후_업로드는_다른_날로_판정되어_스트릭이_이어진다() {
			// statement_timestamp() 는 주입 불가 — 자정 직전 업로드는 last_recorded_date = KST 어제 시드로
			// 등가 재현하고, 직후 업로드는 실제 UPSERT 를 오늘(KST) 실행한다 (§클래스 주석).
			seedStreak(3, 3, 1);

			streakCommandService.recordUpload(userId);

			Object[] row = streakRow();
			assertThat(((Number) row[0]).intValue()).isEqualTo(4);
			assertThat(row[2]).isEqualTo(LocalDate.now(KST));
		}

		// 검증: FR-STREAK-02
		@Test
		@DisplayName("같은 KST 날짜의 업로드는 같은 날로 판정된다 — 기준일이 UTC 날짜면 새벽(UTC 15~24시)에 리셋으로 어긋난다")
		void 같은_KST_날짜의_업로드는_같은_날로_판정되어_카운트가_유지된다() {
			seedStreak(3, 3, 0);   // last = 앱이 계산한 KST 오늘 — DB 판정일과 일치해야 no-op 분기

			streakCommandService.recordUpload(userId);

			assertThat(((Number) streakRow()[0]).intValue()).isEqualTo(3);
		}

		// 검증: FR-STREAK-02
		@Test
		@DisplayName("UTC 로는 같은 날(14:59 vs 15:01)이라도 KST 자정을 넘으면 다른 날로 판정된다 — UPSERT 전문 실행")
		void UTC_같은_날이라도_KST_자정을_넘으면_다른_날로_판정된다() {
			// 리플렉션으로 가져온 현행 UPSERT 전문의 statement_timestamp() 를 고정 시각으로 치환해 실행 —
			// 본문 여섯 사용처 중 어느 하나가 인라인 UTC 식으로 퇴행해도 테스트는 항상 현행 SQL 을 돌리므로
			// 결정적으로 red 가 된다 (Codex 2라운드 — 판정식 복사·상수 공유 방식의 탐지 구멍 수정).
			String sql = upsertSql();
			assertThat(sql).contains("statement_timestamp()");   // now() 등으로 바뀌어 치환이 no-op 되는 퇴행 검출

			seedStreakOn(LocalDate.of(2026, 8, 10), 3, 3);
			runUpsertAt(sql, "2026-08-10 14:59:00+00");          // KST 2026-08-10 23:59 — 자정 직전
			Object[] beforeMidnight = streakRow();

			seedStreakOn(LocalDate.of(2026, 8, 10), 3, 3);
			runUpsertAt(sql, "2026-08-10 15:01:00+00");          // KST 2026-08-11 00:01 — 자정 직후
			Object[] afterMidnight = streakRow();

			assertThat(((Number) beforeMidnight[0]).intValue()).isEqualTo(3);      // 같은 날 판정: no-op
			assertThat(beforeMidnight[2]).isEqualTo(LocalDate.of(2026, 8, 10));
			assertThat(((Number) afterMidnight[0]).intValue()).isEqualTo(4);       // 어제 판정: 연속 +1
			// max_count CASE 의 KST 식만 퇴행해 "같은 날" 분기로 빠지면 max 가 3에 머문다 (Codex 4라운드)
			assertThat(((Number) afterMidnight[1]).intValue()).isEqualTo(4);
			assertThat(afterMidnight[2]).isEqualTo(LocalDate.of(2026, 8, 11));
		}

		// 검증: FR-STREAK-02
		@Test
		@DisplayName("행 없는 첫 업로드도 KST 날짜로 기록된다 — INSERT 경로의 날짜 식 검증")
		void 행_없는_첫_업로드도_KST_날짜로_기록된다() {
			// 시드 없이 고정 시각 실행 — ON CONFLICT 가 아닌 INSERT VALUES 경로를 태워, VALUES 쪽
			// 날짜 식만 UTC 로 퇴행하는 사각을 닫는다 (Codex 4라운드 — UTC 날짜면 8/10 기록이라 red).
			runUpsertAt(upsertSql(), "2026-08-10 15:01:00+00");          // KST 2026-08-11 00:01

			Object[] row = streakRow();
			assertThat(((Number) row[0]).intValue()).isEqualTo(1);
			assertThat(((Number) row[1]).intValue()).isEqualTo(1);
			assertThat(row[2]).isEqualTo(LocalDate.of(2026, 8, 11));
		}
	}

	@Nested
	@DisplayName("꾸준함 뱃지 배선 (§D7)")
	class 뱃지_배선 {

		// 검증: FR-STREAK-04
		@Test
		@DisplayName("스트릭 3일 도달 시 꾸준함 뱃지가 지급되고 반환된다 (STREAK_3)")
		void 스트릭_3일_도달_시_꾸준함_뱃지가_지급되고_반환된다() {
			seedStreak(2, 2, 1);

			List<EarnedBadgeResponseDto> earned = streakCommandService.recordUpload(userId);

			assertThat(earned).extracting(EarnedBadgeResponseDto::code).containsExactly("STREAK_3");
			assertThat(earnedStreakCodes()).containsExactly("STREAK_3");
		}

		// 검증: FR-STREAK-04, FR-BADGE-08
		@Test
		@DisplayName("동기 지급된 스트릭 뱃지는 notified_at 이 기록된다 (239 §D8)")
		void 동기_지급된_스트릭_뱃지는_notified_at이_기록된다() {
			seedStreak(2, 2, 1);

			streakCommandService.recordUpload(userId);

			Number unnotified = (Number) em.createNativeQuery("""
					SELECT COUNT(*) FROM user_badges ub
					JOIN badges b ON b.id = ub.badge_id
					WHERE ub.user_id = :userId AND b.condition_type = 'STREAK_DAYS'
						AND ub.notified_at IS NULL
					""")
				.setParameter("userId", userId)
				.getSingleResult();
			assertThat(earnedStreakCodes()).isNotEmpty();
			assertThat(unnotified.longValue()).isZero();
		}

		// 검증: FR-BADGE-03, FR-BADGE-04
		@Test
		@DisplayName("이미 획득한 스트릭 뱃지는 리셋 후 재도달해도 다시 지급되지 않는다 — 비회수(FR-3)")
		void 이미_획득한_스트릭_뱃지는_리셋_후_재도달해도_다시_지급되지_않는다() {
			seedStreak(2, 2, 1);
			streakCommandService.recordUpload(userId);            // 3일 도달 → STREAK_3 지급

			// 끊겼다가 다시 3일 도달한 상태의 재현 — current 2·last=어제로 되돌린 뒤 업로드.
			seedStreak(2, 3, 1);
			List<EarnedBadgeResponseDto> second = streakCommandService.recordUpload(userId);

			assertThat(second).isEmpty();
			assertThat(earnedStreakCodes()).containsExactly("STREAK_3");
		}

		// 검증: FR-BADGE-03
		@Test
		@DisplayName("같은 날 반복 업로드에도 뱃지가 중복 지급되지 않는다")
		void 같은_날_반복_업로드에도_뱃지가_중복_지급되지_않는다() {
			seedStreak(2, 2, 1);
			List<EarnedBadgeResponseDto> first = streakCommandService.recordUpload(userId);

			List<EarnedBadgeResponseDto> second = streakCommandService.recordUpload(userId);

			assertThat(first).extracting(EarnedBadgeResponseDto::code).containsExactly("STREAK_3");
			assertThat(second).isEmpty();
			assertThat(earnedStreakCodes()).containsExactly("STREAK_3");
		}
	}

	/** current_count·max_count·last_recorded_date 스냅샷 — 단언은 호출부에서. */
	private Object[] streakRow() {
		return (Object[]) em.createNativeQuery("""
				SELECT current_count, max_count, last_recorded_date FROM streaks
				WHERE user_id = :userId
				""")
			.setParameter("userId", userId)
			.getSingleResult();
	}

	/**
	 * fixture 행 시딩 — UPSERT 가 now() 기준이라 "어제/그제" 시나리오는 last_recorded_date 를 직접
	 * daysAgo 만큼 물려 넣는다 (KST 오늘 - daysAgo).
	 */
	private void seedStreak(int currentCount, int maxCount, int daysAgo) {
		seedStreakOn(LocalDate.now(KST).minusDays(daysAgo), currentCount, maxCount);
	}

	/** 절대 날짜 시딩 — KST 경계 테스트는 고정 시각(2026-08-10 기준)과 짝이 되는 절대 날짜가 필요하다. */
	private void seedStreakOn(LocalDate lastDate, int currentCount, int maxCount) {
		em.createNativeQuery("""
				INSERT INTO streaks (user_id, current_count, max_count, last_recorded_date)
				VALUES (:userId, :current, :max, CAST(:lastDate AS date))
				ON CONFLICT (user_id) DO UPDATE SET
					current_count = :current, max_count = :max, last_recorded_date = CAST(:lastDate AS date)
				""")
			.setParameter("userId", userId)
			.setParameter("current", currentCount)
			.setParameter("max", maxCount)
			.setParameter("lastDate", lastDate.toString())
			.executeUpdate();
	}

	/** 현행 UPSERT 전문 (리플렉션) — 프로덕션 SQL 이 어떻게 바뀌든 경계 테스트는 항상 실제 문장을 실행한다. */
	private String upsertSql() {
		try {
			return StreakRepository.class.getMethod("upsertOnUpload", long.class).getAnnotation(Query.class).value();
		} catch (NoSuchMethodException e) {
			throw new IllegalStateException("upsertOnUpload 시그니처 변경 — 경계 테스트 갱신 필요", e);
		}
	}

	/** UPSERT 전문을 고정 UTC 시각으로 실행 — statement_timestamp() 전부를 리터럴로 치환. */
	private void runUpsertAt(String sql, String utcInstant) {
		em.createNativeQuery(sql.replace("statement_timestamp()", "TIMESTAMPTZ '" + utcInstant + "'"))
			.setParameter("userId", userId)
			.executeUpdate();
	}

	@SuppressWarnings("unchecked")
	private List<String> earnedStreakCodes() {
		return em.createNativeQuery("""
				SELECT b.code FROM user_badges ub JOIN badges b ON b.id = ub.badge_id
				WHERE ub.user_id = :userId AND b.condition_type = 'STREAK_DAYS'
				""")
			.setParameter("userId", userId)
			.getResultList();
	}
}
