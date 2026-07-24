package com.msg.fillmap.mission.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.mission.entity.Mission;

/**
 * 활성 미션 판정 native 검증 (findActive, 실 PostGIS · MSG-222 §도메인 2 · Owner B). start_at/end_at 을 각 경계
 * 독립으로 판정하는지 — 무기간(양쪽 NULL) 상시, 기간 지난 이벤트 제외, 시작 전 미션 제외, 기간 내 이벤트 포함을
 * 검증한다. HTTP·shape 는 컨트롤러·서비스 테스트 담당.
 *
 * 격리(공유 로컬 DB): missions 는 시드가 없어(V6 스키마만) @Transactional 롤백으로 충분하다. 단언은 이 tx 에서
 * 삽입한 미션 id 기준으로 스코프한다.
 */
@SpringBootTest
@Transactional
@DisplayName("MissionRepository 활성 미션 판정 (findActive, 실 PostGIS)")
class MissionRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 24, 12, 0, 0);

	@Autowired
	private MissionRepository missionRepository;

	@Autowired
	private EntityManager em;

	/** type·기간으로 미션 한 건을 삽입하고 id 를 돌려준다(target_count=1, region/path 미지정). */
	private long insertMission(String type, LocalDateTime startAt, LocalDateTime endAt) {
		String title = "MSG222-repo-" + System.nanoTime();
		em.createNativeQuery("""
			INSERT INTO missions (type, title, start_at, end_at, target_count)
			VALUES (:type, :title, :startAt, :endAt, 1)
			""")
			.setParameter("type", type)
			.setParameter("title", title)
			.setParameter("startAt", startAt)
			.setParameter("endAt", endAt)
			.executeUpdate();
		return ((Number) em.createNativeQuery("SELECT id FROM missions WHERE title = :title")
			.setParameter("title", title)
			.getSingleResult()).longValue();
	}

	private List<Long> activeIds() {
		return missionRepository.findActive(NOW).stream().map(Mission::getId).toList();
	}

	@Test
	@DisplayName("무기간 코스는 start_end가 NULL이어도 active로 조회된다")
	void 무기간_코스는_start_end가_NULL이어도_active로_조회된다() {
		long course = insertMission("COURSE", null, null);

		assertThat(activeIds()).contains(course);
	}

	@Test
	@DisplayName("기간이 지난 이벤트는 active에서 제외된다")
	void 기간이_지난_이벤트는_active에서_제외된다() {
		long expired = insertMission("EVENT", NOW.minusDays(2), NOW.minusDays(1));

		assertThat(activeIds()).doesNotContain(expired);
	}

	@Test
	@DisplayName("아직 시작 전인 미션은 active에서 제외된다")
	void 아직_시작_전인_미션은_active에서_제외된다() {
		long future = insertMission("EVENT", NOW.plusDays(1), NOW.plusDays(2));

		assertThat(activeIds()).doesNotContain(future);
	}

	@Test
	@DisplayName("현재가 기간 내인 이벤트는 active로 조회된다")
	void 현재가_기간_내인_이벤트는_active로_조회된다() {
		long current = insertMission("EVENT", NOW.minusDays(1), NOW.plusDays(1));

		assertThat(activeIds()).contains(current);
	}

	@Test
	@DisplayName("비활성 미션만 넣으면 내 행은 active에 나오지 않는다")
	void 비활성_미션만_넣으면_내_행은_active에_나오지_않는다() {
		// 만료·시작전 두 비활성 행을 넣고 내 id 기준으로 스코프한다 — 시드 티켓(224/225/235)이 공유/CI DB 에
		// active 미션을 넣어도 전역 빈 테이블을 가정하지 않아 안전하다(@Transactional 은 기존 데이터를 안 지운다).
		long expired = insertMission("EVENT", NOW.minusDays(2), NOW.minusDays(1));
		long future = insertMission("EVENT", NOW.plusDays(1), NOW.plusDays(2));

		assertThat(activeIds()).doesNotContain(expired, future);
	}
}
