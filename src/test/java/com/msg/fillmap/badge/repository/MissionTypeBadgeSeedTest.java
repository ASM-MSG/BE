package com.msg.fillmap.badge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.badge.dto.MyBadgeResponseDto;
import com.msg.fillmap.badge.service.BadgeQueryService;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * V29 미션 종류별 뱃지 재편 검증 (실 PostGIS, MSG-363). 시딩 9종·합산 뱃지 은퇴·소급 지급이 대상이다.
 * 공유 로컬 DB — 합성 유저·미션만 만들고 @Transactional 롤백, badges 마스터(V29 시딩분)는 불가침이라
 * 단언은 code 기준으로만 한다. 소급 블록은 V29 가 이미 적용된 DB 에선 재현 불가라 같은 SQL 을
 * 테스트에서 재실행해 검증한다(ON CONFLICT 멱등 — BadgeSchemaSeedTest 선례).
 */
@SpringBootTest
@Transactional
@DisplayName("V29 미션 종류별 뱃지 — 시딩·은퇴·소급 (실 PostGIS)")
class MissionTypeBadgeSeedTest {

	/** V29 5번 블록의 소급 SQL 과 동일 문장 (마이그레이션 본문이 곧 테스트 대상). */
	private static final String RETRO_MISSION_TYPE_SQL = """
		INSERT INTO user_badges (user_id, badge_id)
		SELECT m.user_id, b.id
		FROM (
			SELECT um.user_id, mi.type, COUNT(*) AS metric
			FROM user_missions um
			JOIN missions mi ON mi.id = um.mission_id
			WHERE mi.type IN ('EVENT', 'COURSE', 'POPUP')
			GROUP BY um.user_id, mi.type
		) m
		JOIN badges b ON b.condition_type = m.type || '_COUNT'
			AND (b.condition_value->>'value')::numeric <= m.metric
		ON CONFLICT DO NOTHING
		""";

	@Autowired
	private BadgeRepository badgeRepository;

	@Autowired
	private BadgeQueryService badgeQueryService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@Nested
	@DisplayName("마스터 시딩")
	class 마스터_시딩 {

		// 검증: FR-BADGE-01, FR-BADGE-12
		@Test
		@DisplayName("종류별 뱃지 9종이 임계값 1·3·10 으로 시딩된다")
		void 종류별_뱃지_9종이_임계값_1_3_10으로_시딩된다() {
			assertThat(seededTiers("EVENT_COUNT")).containsExactly("EVENT_1:1", "EVENT_3:3", "EVENT_10:10");
			assertThat(seededTiers("COURSE_COUNT"))
				.containsExactly("COURSE_1:1", "COURSE_3:3", "COURSE_10:10");
			assertThat(seededTiers("POPUP_COUNT")).containsExactly("POPUP_1:1", "POPUP_3:3", "POPUP_10:10");
		}

		// 검증: FR-BADGE-12
		@Test
		@DisplayName("condition_type CHECK 가 종류별 축 3종을 허용한다 — enum 과 동기(§D1)")
		void condition_type_CHECK가_종류별_축_3종을_허용한다() {
			// 합성 row — 트랜잭션 롤백으로 사라진다. 마스터(V29 시딩분)는 건드리지 않는다.
			assertThat(insertSynthetic("EVENT_COUNT")).isEqualTo(1);
			assertThat(insertSynthetic("COURSE_COUNT")).isEqualTo(1);
			assertThat(insertSynthetic("POPUP_COUNT")).isEqualTo(1);
		}

		// 검증: FR-BADGE-12
		@Test
		@DisplayName("합산 뱃지 3종은 retired_at 이 채워져 있다 — 행은 남기고 지급만 끊는다(§D2)")
		void 합산_뱃지_3종은_retired_at이_채워져_있다() {
			assertThat(retiredCodes()).containsExactlyInAnyOrder("MISSION_1", "MISSION_5", "MISSION_10");
		}

		@Test
		@DisplayName("뱃지 이름에 금지 표현이 없다 — 용어집 '점령'·'정복' 금지(FR-9)")
		void 뱃지_이름에_금지_표현이_없다() {
			Number offending = (Number) em.createNativeQuery("""
					SELECT count(*) FROM badges WHERE name LIKE '%점령%' OR name LIKE '%정복%'
					""")
				.getSingleResult();

			assertThat(offending.longValue()).isZero();
		}

		@SuppressWarnings("unchecked")
		private List<String> seededTiers(String conditionType) {
			List<Object[]> rows = em.createNativeQuery("""
					SELECT code, (condition_value->>'value')::int FROM badges
					WHERE condition_type = :type
					ORDER BY (condition_value->>'value')::int
					""")
				.setParameter("type", conditionType)
				.getResultList();
			return rows.stream().map(row -> row[0] + ":" + row[1]).toList();
		}

		@SuppressWarnings("unchecked")
		private List<String> retiredCodes() {
			return em.createNativeQuery("SELECT code FROM badges WHERE retired_at IS NOT NULL")
				.getResultList();
		}

		private int insertSynthetic(String conditionType) {
			return em.createNativeQuery("""
					INSERT INTO badges (code, name, condition_type, condition_value, icon_url)
					VALUES (:code, '테스트', :type, '{"value": 1}',
						'https://fillmap-static.s3.ap-northeast-2.amazonaws.com/badges/test_fixture.png')
					""")
				.setParameter("code", "TEST_" + conditionType + "_" + System.nanoTime())
				.setParameter("type", conditionType)
				.executeUpdate();
		}
	}

	@Nested
	@DisplayName("합산 뱃지 은퇴 (§D2)")
	class 합산_뱃지_은퇴 {

		// 검증: FR-BADGE-12
		@Test
		@DisplayName("은퇴 뱃지는 지급 후보로 조회되지 않는다 — 도달 불가 임계여도(FR-3)")
		void 은퇴_뱃지는_지급_후보로_조회되지_않는다() {
			long me = newUser();

			List<EligibleBadgeProjection> eligible =
				badgeRepository.findEligible("MISSION_COUNT", BigDecimal.valueOf(999), me);

			assertThat(eligible).isEmpty();
		}

		// 검증: FR-BADGE-12
		@Test
		@DisplayName("은퇴 뱃지는 획득자에게만 목록에 보인다 — 미획득자에겐 회색 칸조차 없다(FR-4)")
		void 은퇴_뱃지는_획득자에게만_목록에_보인다() {
			long earner = newUser();
			long newcomer = newUser();
			grantRetro(earner, badgeId("MISSION_1"));

			List<MyBadgeResponseDto> earnerBadges = badgeQueryService.findMyBadges(earner);
			List<MyBadgeResponseDto> newcomerBadges = badgeQueryService.findMyBadges(newcomer);

			assertThat(earnerBadges).extracting(MyBadgeResponseDto::code).contains("MISSION_1");
			assertThat(newcomerBadges).extracting(MyBadgeResponseDto::code)
				.doesNotContain("MISSION_1", "MISSION_5", "MISSION_10");
			// 현역 뱃지는 미획득자에게도 그대로 보인다 — 은퇴 필터가 목록 전체를 좁히지 않는다.
			assertThat(newcomerBadges).extracting(MyBadgeResponseDto::code).contains("EVENT_1", "EXPLORER_1");
		}
	}

	@Nested
	@DisplayName("소급 지급 (§V29 5번 블록)")
	class 소급_지급 {

		// 검증: FR-BADGE-06, FR-BADGE-12
		@Test
		@DisplayName("소급 SQL 은 종류별로 충족 티어만 지급한다 — 축제 5곳이면 1·3 만(엣지 케이스 2)")
		void 소급_SQL은_종류별로_충족_티어만_지급한다() {
			long me = newUser();
			seedStamps(me, "EVENT", 5);
			seedStamps(me, "POPUP", 1);

			runRetroBlock();

			assertThat(earnedCodes(me)).containsExactlyInAnyOrder("EVENT_1", "EVENT_3", "POPUP_1");
		}

		// 검증: FR-BADGE-12
		@Test
		@DisplayName("소급 SQL 은 구역·테마·지속 스탬프를 대상으로 삼지 않는다 (FR-6)")
		void 소급_SQL은_구역_테마_지속_스탬프를_대상으로_삼지_않는다() {
			long me = newUser();
			seedStamps(me, "AREA", 5);
			seedStamps(me, "THEME", 5);
			seedStamps(me, "CONTINUOUS", 5);

			runRetroBlock();

			assertThat(earnedCodes(me)).isEmpty();
		}

		// 검증: FR-BADGE-06, FR-BADGE-12
		@Test
		@DisplayName("소급 SQL 은 재실행해도 중복 row 가 생기지 않는다 — ON CONFLICT 멱등")
		void 소급_SQL은_재실행해도_중복_row가_생기지_않는다() {
			long me = newUser();
			seedStamps(me, "COURSE", 3);

			runRetroBlock();
			runRetroBlock();

			assertThat(earnedCodes(me)).containsExactlyInAnyOrder("COURSE_1", "COURSE_3");
		}

		// 검증: FR-BADGE-06, FR-BADGE-12
		@Test
		@DisplayName("소급 지급분은 notified_at 이 NULL 이다 — 다음 조회에서 '새 뱃지' 표시")
		void 소급_지급분은_notified_at이_NULL이다() {
			long me = newUser();
			seedStamps(me, "EVENT", 1);

			runRetroBlock();

			Number notified = (Number) em.createNativeQuery("""
					SELECT COUNT(*) FROM user_badges WHERE user_id = :me AND notified_at IS NOT NULL
					""")
				.setParameter("me", me)
				.getSingleResult();
			assertThat(earnedCodes(me)).containsExactly("EVENT_1");
			assertThat(notified.longValue()).isZero();
		}

		private void runRetroBlock() {
			em.createNativeQuery(RETRO_MISSION_TYPE_SQL).executeUpdate();
		}
	}

	private long newUser() {
		String email = "mission-badge-" + System.nanoTime() + "@example.com";
		return userRepository.save(User.createLocalUser(email, "hash", "미션뱃지테스터")).getId();
	}

	/** 합성 미션 count 개를 만들고 전부 스탬프를 찍는다 — 소급 SQL 의 입력(user_missions × missions.type). */
	private void seedStamps(long userId, String type, int count) {
		for (int i = 0; i < count; i++) {
			String title = "MSG363-" + type + "-" + System.nanoTime() + "-" + i;
			em.createNativeQuery("""
					INSERT INTO missions (type, title, target_count)
					VALUES (:type, :title, 1)
					""")
				.setParameter("type", type)
				.setParameter("title", title)
				.executeUpdate();
			long missionId = ((Number) em.createNativeQuery("SELECT id FROM missions WHERE title = :title")
				.setParameter("title", title)
				.getSingleResult()).longValue();
			em.createNativeQuery("INSERT INTO user_missions (user_id, mission_id) VALUES (:userId, :missionId)")
				.setParameter("userId", userId)
				.setParameter("missionId", missionId)
				.executeUpdate();
		}
	}

	/** 소급 지급과 같은 형태의 미확인 지급 — notified_at NULL(V29 5번 블록과 동일 모양). */
	private void grantRetro(long userId, long badgeId) {
		em.createNativeQuery("INSERT INTO user_badges (user_id, badge_id) VALUES (:userId, :badgeId)")
			.setParameter("userId", userId)
			.setParameter("badgeId", badgeId)
			.executeUpdate();
	}

	private long badgeId(String code) {
		return ((Number) em.createNativeQuery("SELECT id FROM badges WHERE code = :code")
			.setParameter("code", code)
			.getSingleResult()).longValue();
	}

	@SuppressWarnings("unchecked")
	private List<String> earnedCodes(long userId) {
		return em.createNativeQuery("""
				SELECT b.code FROM user_badges ub JOIN badges b ON b.id = ub.badge_id
				WHERE ub.user_id = :userId
				""")
			.setParameter("userId", userId)
			.getResultList();
	}
}
