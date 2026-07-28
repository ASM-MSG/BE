package com.msg.fillmap.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * V6 미션 스키마 마이그레이션 검증 — missions · mission_grids · user_missions
 * (MSG-166, 실 PostGIS · Owner B).
 * 컨텍스트가 뜨는 것 자체가 Flyway V1~V6 적용 증거이며, 그 위에서
 * 스키마 제약(CHECK·PK·FK)을 native INSERT 로 확인한다. 격리는
 * 합성 행(실데이터와 무충돌하는 값)과 @Transactional 롤백만 쓴다
 * — truncate 하지 않는다. grid_id 는 grids 에 실재할 수 없는 999900 대역 논리키를 써
 * FK 부재(§D2)를 드러낸다.
 */
@SpringBootTest
@Transactional
@DisplayName("V6 미션 스키마 마이그레이션 (실 PostGIS · 롤백 격리)")
class MissionSchemaMigrationTest {

	// 코스 표시용 GeoJSON LineString (§D1) — path 저장/조회·CHECK 검증에 공용.
	private static final String LINESTRING_JSON =
		"{\"type\":\"LineString\",\"coordinates\":[[127.0,37.5],[127.1,37.6]]}";

	@Autowired
	private EntityManager em;

	@Autowired
	private UserRepository userRepository;

	/**
	 * type·title·target_count 만 채운 최소 미션 하나를 INSERT 하고
	 * 생성된 id 를 돌려준다(나머지 NULL).
	 */
	private long insertMinimalMission(String type) {
		return ((Number) em.createNativeQuery(
			"INSERT INTO missions (type, title, target_count) VALUES (:t, '합성미션', 1) RETURNING id")
			.setParameter("t", type).getSingleResult()).longValue();
	}

	private void insertMissionGrid(long missionId, String gridId) {
		em.createNativeQuery("INSERT INTO mission_grids (mission_id, grid_id) VALUES (:m, :g)")
			.setParameter("m", missionId).setParameter("g", gridId).executeUpdate();
	}

	private void insertUserMission(long userId, long missionId) {
		em.createNativeQuery("INSERT INTO user_missions (user_id, mission_id) VALUES (:u, :m)")
			.setParameter("u", userId).setParameter("m", missionId).executeUpdate();
	}

	private long missionGridCount(long missionId) {
		return ((Number) em.createNativeQuery("SELECT count(*) FROM mission_grids WHERE mission_id = :m")
			.setParameter("m", missionId).getSingleResult()).longValue();
	}

	private long userMissionCount(long userId) {
		return ((Number) em.createNativeQuery("SELECT count(*) FROM user_missions WHERE user_id = :u")
			.setParameter("u", userId).getSingleResult()).longValue();
	}

	private long createUser() {
		return userRepository.save(
			User.createLocalUser("mission-" + UUID.randomUUID() + "@example.com", "hash", "미션테스터")).getId();
	}

	@Nested
	@DisplayName("마이그레이션 적용 (부팅 = V6 성공)")
	class MigrationApplied {

		@Test
		@DisplayName("V6가_적용되면_missions_mission_grids_user_missions_3테이블이_존재한다")
		void V6가_적용되면_missions_mission_grids_user_missions_3테이블이_존재한다() {
			List<?> tables = em.createNativeQuery(
				"SELECT table_name FROM information_schema.tables "
					+ "WHERE table_schema = 'public' "
					+ "AND table_name IN ('missions', 'mission_grids', 'user_missions')")
				.getResultList();

			assertThat(tables).hasSize(3);
		}
	}

	@Nested
	@DisplayName("missions 제약")
	class MissionConstraints {

		@Test
		@DisplayName("type이_5종_외_값이면_chk_missions_type_위반으로_INSERT가_실패한다")
		void type이_5종_외_값이면_chk_missions_type_위반으로_INSERT가_실패한다() {
			assertThatThrownBy(() -> insertMinimalMission("INVALID"))
				.hasStackTraceContaining("chk_missions_type");
		}

		@Test
		@DisplayName("코스_5종_전부는_INSERT된다")
		void 코스_5종_전부는_INSERT된다() {
			for (String type : List.of("COURSE", "AREA", "EVENT", "THEME", "CONTINUOUS")) {
				assertThat(insertMinimalMission(type)).isPositive();
			}
		}

		@Test
		@DisplayName("코스는_start_at_end_at이_NULL이어도_INSERT된다")
		void 코스는_start_at_end_at이_NULL이어도_INSERT된다() {
			long id = insertMinimalMission("COURSE");

			Object[] period = (Object[]) em.createNativeQuery(
				"SELECT start_at, end_at FROM missions WHERE id = :id")
				.setParameter("id", id).getSingleResult();

			assertThat(period[0]).isNull();
			assertThat(period[1]).isNull();
		}

		@Test
		@DisplayName("코스가_아닌_미션에_path를_넣으면_chk_missions_path_위반이다")
		void 코스가_아닌_미션에_path를_넣으면_chk_missions_path_위반이다() {
			assertThatThrownBy(() -> em.createNativeQuery(
				"INSERT INTO missions (type, title, target_count, path) "
					+ "VALUES ('EVENT', '합성미션', 1, CAST(:p AS jsonb))")
				.setParameter("p", LINESTRING_JSON).executeUpdate())
				.hasStackTraceContaining("chk_missions_path");
		}

		@Test
		@DisplayName("코스에_GeoJSON_LineString_path를_JSONB로_저장하고_조회할_수_있다")
		void 코스에_GeoJSON_LineString_path를_JSONB로_저장하고_조회할_수_있다() {
			long id = ((Number) em.createNativeQuery(
				"INSERT INTO missions (type, title, target_count, path) "
					+ "VALUES ('COURSE', '합성코스', 3, CAST(:p AS jsonb)) RETURNING id")
				.setParameter("p", LINESTRING_JSON).getSingleResult()).longValue();

			// JSONB 로 저장돼 json 연산자로 파싱 가능해야 한다(문자열 blob 이 아니라 구조화 저장).
			String pathType = (String) em.createNativeQuery("SELECT path->>'type' FROM missions WHERE id = :id")
				.setParameter("id", id).getSingleResult();

			assertThat(pathType).isEqualTo("LineString");
		}

		@Test
		@DisplayName("start_at이_end_at보다_뒤면_chk_missions_period_위반이다")
		void start_at이_end_at보다_뒤면_chk_missions_period_위반이다() {
			LocalDateTime start = LocalDateTime.of(2026, 8, 1, 0, 0);
			LocalDateTime end = LocalDateTime.of(2026, 7, 1, 0, 0);

			assertThatThrownBy(() -> em.createNativeQuery(
				"INSERT INTO missions (type, title, target_count, start_at, end_at) "
					+ "VALUES ('EVENT', '합성미션', 1, :s, :e)")
				.setParameter("s", start).setParameter("e", end).executeUpdate())
				.hasStackTraceContaining("chk_missions_period");
		}

		@Test
		@DisplayName("target_count가_0이하면_chk_missions_target_위반이다")
		void target_count가_0이하면_chk_missions_target_위반이다() {
			assertThatThrownBy(() -> em.createNativeQuery(
				"INSERT INTO missions (type, title, target_count) VALUES ('EVENT', '합성미션', 0)")
				.executeUpdate())
				.hasStackTraceContaining("chk_missions_target");
		}
	}

	@Nested
	@DisplayName("mission_grids 제약 (§D2 grids FK 부재)")
	class MissionGridConstraints {

		@Test
		@DisplayName("grids에_row가_없는_격자도_mission_grids에_INSERT된다")
		void grids에_row가_없는_격자도_mission_grids에_INSERT된다() {
			long missionId = insertMinimalMission("COURSE");

			// 999900 대역은 grids 에 실재할 수 없는 논리키 —
			// grids FK 가 걸려 있다면 여기서 실패한다(§D2).
			insertMissionGrid(missionId, "999900_999900");

			assertThat(missionGridCount(missionId)).isEqualTo(1L);
		}

		@Test
		@DisplayName("같은_mission_id와_grid_id_조합은_PK로_중복이_거부된다")
		void 같은_mission_id와_grid_id_조합은_PK로_중복이_거부된다() {
			long missionId = insertMinimalMission("COURSE");
			insertMissionGrid(missionId, "999900_500000");

			assertThatThrownBy(() -> insertMissionGrid(missionId, "999900_500000"))
				.hasStackTraceContaining("duplicate key value");
		}

		@Test
		@DisplayName("mission을_삭제하면_mission_grids가_CASCADE로_함께_삭제된다")
		void mission을_삭제하면_mission_grids가_CASCADE로_함께_삭제된다() {
			long missionId = insertMinimalMission("COURSE");
			insertMissionGrid(missionId, "999900_500001");

			em.createNativeQuery("DELETE FROM missions WHERE id = :m").setParameter("m", missionId).executeUpdate();

			assertThat(missionGridCount(missionId)).isEqualTo(0L);
		}

		@Test
		@DisplayName("seq는_NULL이_허용된다")
		void seq는_NULL이_허용된다() {
			long missionId = insertMinimalMission("AREA");

			insertMissionGrid(missionId, "999900_500002");   // seq 미지정 → NULL

			long nullSeq = ((Number) em.createNativeQuery(
				"SELECT count(*) FROM mission_grids WHERE mission_id = :m AND seq IS NULL")
				.setParameter("m", missionId).getSingleResult()).longValue();
			assertThat(nullSeq).isEqualTo(1L);
		}
	}

	@Nested
	@DisplayName("user_missions 제약 (§D7 스탬프)")
	class UserMissionConstraints {

		@Test
		@DisplayName("같은_user_id와_mission_id_스탬프는_PK로_중복이_거부된다")
		void 같은_user_id와_mission_id_스탬프는_PK로_중복이_거부된다() {
			long userId = createUser();
			long missionId = insertMinimalMission("EVENT");
			insertUserMission(userId, missionId);

			assertThatThrownBy(() -> insertUserMission(userId, missionId))
				.hasStackTraceContaining("duplicate key value");
		}

		@Test
		@DisplayName("스탬프가_있는_mission은_삭제되지_않는다")
		void 스탬프가_있는_mission은_삭제되지_않는다() {
			long userId = createUser();
			long missionId = insertMinimalMission("EVENT");
			insertUserMission(userId, missionId);

			// mission FK NO ACTION — 스탬프가 걸린 미션 하드삭제는
			// FK 위반으로 막힌다(비회수 보호, §D7).
			assertThatThrownBy(() -> em.createNativeQuery("DELETE FROM missions WHERE id = :m")
				.setParameter("m", missionId).executeUpdate())
				.hasStackTraceContaining("violates foreign key constraint");
		}

		@Test
		@DisplayName("user를_삭제하면_user_missions가_CASCADE로_함께_삭제된다")
		void user를_삭제하면_user_missions가_CASCADE로_함께_삭제된다() {
			long userId = createUser();
			long missionId = insertMinimalMission("EVENT");
			insertUserMission(userId, missionId);

			em.createNativeQuery("DELETE FROM users WHERE id = :u").setParameter("u", userId).executeUpdate();

			assertThat(userMissionCount(userId)).isEqualTo(0L);
		}
	}
}
