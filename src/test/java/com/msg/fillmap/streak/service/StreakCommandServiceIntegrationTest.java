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
		em.createNativeQuery("""
				INSERT INTO streaks (user_id, current_count, max_count, last_recorded_date)
				VALUES (:userId, :current, :max, CAST(:lastDate AS date))
				ON CONFLICT (user_id) DO UPDATE SET
					current_count = :current, max_count = :max, last_recorded_date = CAST(:lastDate AS date)
				""")
			.setParameter("userId", userId)
			.setParameter("current", currentCount)
			.setParameter("max", maxCount)
			.setParameter("lastDate", LocalDate.now(KST).minusDays(daysAgo).toString())
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
