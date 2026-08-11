package com.msg.fillmap.mission.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 스탬프 영속층 검증 (실 PostGIS, MSG-223 모듈 1). ON CONFLICT DO NOTHING 의 영향 행이 곧 중복 발급
 * 차단(FR-14)이라 실 DB 로 본다 — @Modifying 누락도 여기서만 드러난다. 공유 로컬 DB — 합성 유저·미션만
 * 만들고 @Transactional 롤백, badges 마스터(V12 시딩분)는 불가침·code 기준 단언만 한다.
 */
@SpringBootTest
@Transactional
@DisplayName("UserMissionRepository 스탬프 영속층 (실 PostGIS)")
class UserMissionRepositoryTest {

	@Autowired
	private UserMissionRepository userMissionRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@Test
	@DisplayName("스탬프 INSERT 는 성공 시 1을 반환한다")
	void 스탬프_INSERT는_성공_시_1을_반환한다() {
		long me = newUser();
		long mission = newMission("EVENT");

		assertThat(userMissionRepository.insertIgnoreConflict(me, mission)).isEqualTo(1);
	}

	// 검증: FR-MISSION-04
	@Test
	@DisplayName("같은 미션 스탬프 중복 INSERT 는 0을 반환한다 — PK 충돌 무시(FR-14)")
	void 같은_미션_스탬프_중복_INSERT는_0을_반환한다() {
		long me = newUser();
		long mission = newMission("EVENT");
		userMissionRepository.insertIgnoreConflict(me, mission);

		assertThat(userMissionRepository.insertIgnoreConflict(me, mission)).isZero();
	}

	// 검증: FR-BADGE-12
	@Test
	@DisplayName("countMyStampsByType 은 그 종류의 내 스탬프만 센다 — 타 사용자·타 종류 미포함(MSG-363 §D4)")
	void countMyStampsByType은_그_종류의_내_스탬프만_센다() {
		long me = newUser();
		long other = newUser();
		long firstEvent = newMission("EVENT");
		long secondEvent = newMission("EVENT");
		long popup = newMission("POPUP");
		userMissionRepository.insertIgnoreConflict(me, firstEvent);
		userMissionRepository.insertIgnoreConflict(me, secondEvent);
		userMissionRepository.insertIgnoreConflict(me, popup);
		userMissionRepository.insertIgnoreConflict(other, firstEvent);

		assertThat(userMissionRepository.countMyStampsByType(me, "EVENT"))
			.isEqualByComparingTo(BigDecimal.valueOf(2));
		assertThat(userMissionRepository.countMyStampsByType(me, "POPUP"))
			.isEqualByComparingTo(BigDecimal.ONE);
		assertThat(userMissionRepository.countMyStampsByType(me, "COURSE"))
			.isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	@DisplayName("V12 MISSION_COUNT 뱃지 3종이 임계값 1·5·10 으로 시딩된다 — 재실행 멱등(ON CONFLICT)")
	void V12_MISSION_COUNT_뱃지_3종이_임계값_1_5_10으로_시딩된다() throws IOException {
		// 같은 시딩 SQL 재실행이 0행이면 멱등이 실증된다 (BadgeSchemaSeedTest 소급 재실행 선례).
		int reseeded = em.createNativeQuery(readV12()).executeUpdate();

		assertThat(reseeded).isZero();
		assertThat(missionBadgeRows()).containsExactly("MISSION_1:1", "MISSION_5:5", "MISSION_10:10");
	}

	@SuppressWarnings("unchecked")
	private List<String> missionBadgeRows() {
		List<Object[]> rows = em.createNativeQuery("""
				SELECT code, (condition_value->>'value')::int FROM badges
				WHERE condition_type = 'MISSION_COUNT'
				ORDER BY (condition_value->>'value')::int
				""")
			.getResultList();
		return rows.stream().map(row -> row[0] + ":" + row[1]).toList();
	}

	private String readV12() throws IOException {
		return new String(
			getClass().getClassLoader()
				.getResourceAsStream("db/migration/V12__mission_badges_seed.sql")
				.readAllBytes(),
			StandardCharsets.UTF_8);
	}

	private long newUser() {
		String email = "mission-stamp-" + System.nanoTime() + "@example.com";
		return userRepository.save(User.createLocalUser(email, "hash", "스탬프테스터")).getId();
	}

	private long newMission(String type) {
		String title = "MSG223-stamp-" + System.nanoTime();
		em.createNativeQuery("""
				INSERT INTO missions (type, title, target_count)
				VALUES (:type, :title, 1)
				""")
			.setParameter("type", type)
			.setParameter("title", title)
			.executeUpdate();
		return ((Number) em.createNativeQuery("SELECT id FROM missions WHERE title = :title")
			.setParameter("title", title)
			.getSingleResult()).longValue();
	}
}
