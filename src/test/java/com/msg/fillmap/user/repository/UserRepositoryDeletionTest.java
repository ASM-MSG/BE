package com.msg.fillmap.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.user.entity.User;

/**
 * 계정 삭제(MSG-205) 리포지토리 계층 — V15 reports FK 정책 + UserRepository 삭제용 쿼리 2건 (실 PostGIS).
 *
 * 컨텍스트가 뜨는 것 자체가 Flyway V15 적용 증거이며(MissionSchemaMigrationTest 선례),
 * 격리는 합성 행(999901 대역 격자·UUID 이메일)과 @Transactional 롤백만 쓴다 — 공유 로컬 DB 에
 * 시드를 남기지 않는다.
 */
@SpringBootTest
@Transactional
@DisplayName("계정 삭제 리포지토리 (V15 · UserRepository 쿼리)")
class UserRepositoryDeletionTest {

	// grids 에 실재할 수 없는 999901 대역 논리키 — 실데이터·타 테스트와 무충돌.
	private static final String GRID_ID = "999901_999901";

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	private long createUser() {
		return userRepository.save(
			User.createLocalUser("deletion-" + UUID.randomUUID() + "@example.com", "hash", "삭제테스터")).getId();
	}

	private void insertGrid() {
		em.createNativeQuery("""
			INSERT INTO grids (grid_id, grid_y, grid_x, center_geom, bbox_geom)
			VALUES (:g, 999901, 999901, ST_GeogFromText('POINT(127.0 37.5)'),
				ST_GeogFromText('POLYGON((127.0 37.5, 127.001 37.5, 127.001 37.501, 127.0 37.501, 127.0 37.5))'))
			""").setParameter("g", GRID_ID).executeUpdate();
	}

	private void insertVideo(long userId, String status, String original, String encoded, String thumb, String blurred) {
		em.createNativeQuery("""
			INSERT INTO videos (user_id, grid_id, original_s3_key, encoded_url, thumbnail_url, blurred_s3_key,
				geom, duration_sec, status, recorded_at)
			VALUES (:u, :g, :o, :e, :t, :b, ST_GeogFromText('POINT(127.0 37.5)'), 10, :s, now())
			""")
			.setParameter("u", userId).setParameter("g", GRID_ID)
			.setParameter("o", original).setParameter("e", encoded)
			.setParameter("t", thumb).setParameter("b", blurred)
			.setParameter("s", status)
			.executeUpdate();
	}

	@Nested
	@DisplayName("V15 — reports FK ON DELETE 정책")
	class ReportsFkPolicy {

		private String onDeleteTypeOf(String constraintName) {
			// confdeltype: 'c' = CASCADE, 'n' = SET NULL, 'a' = NO ACTION (V15 이전 상태)
			return (String) em.createNativeQuery(
					"SELECT confdeltype::text FROM pg_constraint WHERE conname = :n AND conrelid = 'reports'::regclass")
				.setParameter("n", constraintName)
				.getSingleResult();
		}

		// 검증: FR-USER-11
		@Test
		@DisplayName("reporter_id FK 는 ON DELETE CASCADE 다 — 그가 한 신고는 함께 삭제 (FR-6)")
		void reporter_FK는_CASCADE_정책이다() {
			assertThat(onDeleteTypeOf("reports_reporter_id_fkey")).isEqualTo("c");
		}

		// 검증: FR-USER-11
		@Test
		@DisplayName("reviewed_by FK 는 ON DELETE SET NULL 이다 — 검토자 기록만 비움 (FR-6)")
		void reviewed_by_FK는_SET_NULL_정책이다() {
			assertThat(onDeleteTypeOf("reports_reviewed_by_fkey")).isEqualTo("n");
		}
	}

	@Nested
	@DisplayName("findAllS3KeysByUserId — 계정 삭제용 S3 키 수집")
	class FindAllS3Keys {

		// 검증: FR-USER-08
		@Test
		void 소프트_삭제된_영상_포함_그_유저_영상의_4컬럼_키를_전량_수집한다() {
			long userId = createUser();
			insertGrid();
			insertVideo(userId, "ACTIVE", "k-original", "k-encoded", "k-thumb", "k-blurred");
			// status DELETED(soft) 행도 수집 대상 — S3 객체는 아직 남아 있을 수 있다. 미인코딩이라 뒤 3키 null.
			insertVideo(userId, "DELETED", "k-deleted-original", null, null, null);

			List<Object[]> rows = userRepository.findAllS3KeysByUserId(userId);

			assertThat(rows).hasSize(2);
			assertThat(rows).allSatisfy(row -> assertThat(row).hasSize(4));
			assertThat(rows.stream().flatMap(Arrays::stream).filter(Objects::nonNull))
				.containsExactlyInAnyOrder("k-original", "k-encoded", "k-thumb", "k-blurred", "k-deleted-original");
		}

		// 검증: FR-USER-08
		@Test
		void 타_사용자의_영상_키는_수집되지_않는다() {
			long userId = createUser();
			long otherUserId = createUser();
			insertGrid();
			insertVideo(otherUserId, "ACTIVE", "other-original", null, null, null);

			assertThat(userRepository.findAllS3KeysByUserId(userId)).isEmpty();
		}
	}

	@Nested
	@DisplayName("deleteUser — 삭제 행 수 반환")
	class DeleteUser {

		private long userRowCount(long userId) {
			return ((Number) em.createNativeQuery("SELECT count(*) FROM users WHERE id = :u")
				.setParameter("u", userId).getSingleResult()).longValue();
		}

		// 검증: FR-USER-06
		@Test
		void 존재하는_유저를_지우면_1을_반환하고_users_행이_사라진다() {
			long userId = createUser();

			assertThat(userRepository.deleteUser(userId)).isEqualTo(1);
			assertThat(userRowCount(userId)).isZero();
		}

		@Test
		void 없는_유저를_지우면_0을_반환한다() {
			assertThat(userRepository.deleteUser(999_999_999L)).isZero();
		}
	}
}
