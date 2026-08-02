package com.msg.fillmap.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import software.amazon.awssdk.services.s3.S3Client;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.repository.VideoRepository;

/**
 * 계정 삭제 — DB 연쇄 (MSG-205 FR-1·2·5·6·7·8·D3, 실 PostGIS).
 *
 * FK CASCADE 는 문장 실행 시점에 발화하므로 @Transactional 롤백 격리 안에서 연쇄 결과를 그대로
 * 단언할 수 있다 — 공유 로컬 DB 에 시드를 남기지 않는다. 커밋 후 정리(S3·Redis)는 여기서 실행되지
 * 않는다(등록만 되고 롤백) — 그 축은 UserAccountS3CleanupTest·UserAccountSessionInvalidationIntegrationTest 몫.
 * 카운트는 전부 user_id 로 좁혀 센다 (VideoDeleteIntegrationTest 교훈 — 격자 단위 카운트는 오염된다).
 */
@SpringBootTest
@Transactional
@DisplayName("계정 삭제 · FK 연쇄 (실 PostGIS)")
class UserAccountDeletionIntegrationTest {

	// grids 에 실재할 수 없는 999902 대역 논리키 — 실데이터·타 테스트와 무충돌.
	private static final String GRID_ID = "999902_999902";
	private static final String REGION_CODE = "9999999999";
	private static final String UNUSED_TOKEN = "unused-access-token";   // 커밋 후 축이 롤백 격리로 실행 안 됨

	@Autowired
	private UserService userService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private VideoRepository videoRepository;

	@Autowired
	private EntityManager em;

	/** 실 S3 호출 차단용 — 이 테스트에서는 롤백 격리라 호출 자체가 없어야 정상이다. */
	@MockitoBean
	private S3Client s3Client;

	private long userId;

	@BeforeEach
	void setUp() {
		userId = createUser();
		insertGrid();
	}

	private long createUser() {
		return userRepository.save(
			User.createLocalUser("account-" + UUID.randomUUID() + "@example.com", "hash", "탈퇴자")).getId();
	}

	private void insertGrid() {
		em.createNativeQuery("""
			INSERT INTO grids (grid_id, grid_y, grid_x, center_geom, bbox_geom)
			VALUES (:g, 999902, 999902, ST_GeogFromText('POINT(127.0 37.5)'),
				ST_GeogFromText('POLYGON((127.0 37.5, 127.001 37.5, 127.001 37.501, 127.0 37.501, 127.0 37.5))'))
			""").setParameter("g", GRID_ID).executeUpdate();
	}

	/** 전역 대표 영상 후보가 되도록 ACTIVE·PUBLIC·READY 로 넣는다 (FR-8 검증에 공용). */
	private long insertVideo(long ownerId, long viewCount) {
		return ((Number) em.createNativeQuery("""
			INSERT INTO videos (user_id, grid_id, original_s3_key, geom, duration_sec,
				processing_status, visibility, status, view_count, recorded_at)
			VALUES (:u, :g, :k, ST_GeogFromText('POINT(127.0 37.5)'), 10,
				'READY', 'PUBLIC', 'ACTIVE', :v, now())
			RETURNING id
			""")
			.setParameter("u", ownerId).setParameter("g", GRID_ID)
			.setParameter("k", "videos/original/" + ownerId + "/" + UUID.randomUUID() + ".mp4")
			.setParameter("v", viewCount)
			.getSingleResult()).longValue();
	}

	private void insertUserGrid(long ownerId) {
		em.createNativeQuery("INSERT INTO user_grids (user_id, grid_id) VALUES (:u, :g)")
			.setParameter("u", ownerId).setParameter("g", GRID_ID).executeUpdate();
	}

	private long insertReport(long reporterId, long videoId, Long reviewedBy) {
		// CAST — reviewed_by null 전달 시 PG 가 파라미터 타입을 못 정하는 것을 막는다.
		return ((Number) em.createNativeQuery("""
			INSERT INTO reports (reporter_id, video_id, reviewed_by, reason)
			VALUES (:r, :v, CAST(:rb AS bigint), 'SPAM') RETURNING id
			""")
			.setParameter("r", reporterId).setParameter("v", videoId).setParameter("rb", reviewedBy)
			.getSingleResult()).longValue();
	}

	private long countByUser(String table, String userColumn, long ownerId) {
		return ((Number) em.createNativeQuery(
				"SELECT count(*) FROM " + table + " WHERE " + userColumn + " = :u")
			.setParameter("u", ownerId).getSingleResult()).longValue();
	}

	private long usersRowCount(long ownerId) {
		return countByUser("users", "id", ownerId);
	}

	@Test
	void 본인_계정_삭제는_성공하고_users_행이_사라진다() {
		userService.deleteAccount(userId, UNUSED_TOKEN);

		assertThat(usersRowCount(userId)).isZero();
	}

	@Test
	void 삭제_시_영상_도감_수집률_푸시토큰_친구_스트릭_뱃지_좋아요_미션이_전부_연쇄_삭제된다() {
		long friendId = createUser();
		long videoId = insertVideo(userId, 0);
		insertUserGrid(userId);
		// 수집률 캐시 — 합성 행정동(실데이터 adm_cd2 와 무충돌 코드).
		em.createNativeQuery("""
			INSERT INTO regions (region_code, region_name, boundary_geom)
			VALUES (:c, '합성행정동', ST_GeogFromText(
				'MULTIPOLYGON(((127.0 37.5, 127.001 37.5, 127.001 37.501, 127.0 37.501, 127.0 37.5)))'))
			""").setParameter("c", REGION_CODE).executeUpdate();
		em.createNativeQuery("INSERT INTO region_stats (user_id, region_code) VALUES (:u, :c)")
			.setParameter("u", userId).setParameter("c", REGION_CODE).executeUpdate();
		em.createNativeQuery("INSERT INTO push_tokens (fcm_token, user_id, platform) VALUES (:t, :u, 'WEB')")
			.setParameter("t", "fcm-" + UUID.randomUUID()).setParameter("u", userId).executeUpdate();
		em.createNativeQuery("INSERT INTO friendships (requester_id, addressee_id) VALUES (:u, :f)")
			.setParameter("u", userId).setParameter("f", friendId).executeUpdate();
		em.createNativeQuery("INSERT INTO streaks (user_id) VALUES (:u)")
			.setParameter("u", userId).executeUpdate();
		em.createNativeQuery(
				"INSERT INTO user_badges (user_id, badge_id) VALUES (:u, (SELECT min(id) FROM badges))")
			.setParameter("u", userId).executeUpdate();
		em.createNativeQuery("INSERT INTO likes (user_id, video_id) VALUES (:u, :v)")
			.setParameter("u", userId).setParameter("v", videoId).executeUpdate();
		long missionId = ((Number) em.createNativeQuery(
				"INSERT INTO missions (type, title, target_count) VALUES ('EVENT', '합성미션', 1) RETURNING id")
			.getSingleResult()).longValue();
		em.createNativeQuery("INSERT INTO user_missions (user_id, mission_id) VALUES (:u, :m)")
			.setParameter("u", userId).setParameter("m", missionId).executeUpdate();

		userService.deleteAccount(userId, UNUSED_TOKEN);

		assertThat(countByUser("videos", "user_id", userId)).as("영상").isZero();
		assertThat(countByUser("user_grids", "user_id", userId)).as("도감").isZero();
		assertThat(countByUser("region_stats", "user_id", userId)).as("수집률").isZero();
		assertThat(countByUser("push_tokens", "user_id", userId)).as("푸시토큰").isZero();
		assertThat(countByUser("friendships", "requester_id", userId)).as("친구").isZero();
		assertThat(countByUser("streaks", "user_id", userId)).as("스트릭").isZero();
		assertThat(countByUser("user_badges", "user_id", userId)).as("뱃지").isZero();
		assertThat(countByUser("likes", "user_id", userId)).as("좋아요").isZero();
		assertThat(countByUser("user_missions", "user_id", userId)).as("미션").isZero();
	}

	@Test
	void 삭제된_이메일로_재가입하면_새_계정이_만들어진다() {
		String email = "rejoin-" + UUID.randomUUID() + "@example.com";
		long firstId = userRepository.save(User.createLocalUser(email, "hash", "첫가입")).getId();

		userService.deleteAccount(firstId, UNUSED_TOKEN);

		// uq_users_email 충돌 없이 새 행이 들어가야 한다 — saveAndFlush 로 제약 위반을 즉시 드러낸다.
		long secondId = userRepository.saveAndFlush(User.createLocalUser(email, "hash", "재가입")).getId();
		assertThat(secondId).isNotEqualTo(firstId);
	}

	@Test
	void 신고_이력이_있는_유저_삭제_시_그의_신고는_삭제되고_검토자_기록은_NULL이_된다() {
		long otherId = createUser();
		long otherVideoId = insertVideo(otherId, 0);
		long myReportId = insertReport(userId, otherVideoId, null);
		long reviewedReportId = insertReport(otherId, otherVideoId, userId);

		userService.deleteAccount(userId, UNUSED_TOKEN);

		assertThat(countByUser("reports", "id", myReportId)).as("그가 한 신고는 CASCADE 삭제 (V15)").isZero();
		Object reviewedBy = em.createNativeQuery("SELECT reviewed_by FROM reports WHERE id = :i")
			.setParameter("i", reviewedReportId).getSingleResult();
		assertThat(reviewedBy).as("검토자 기록은 SET NULL — 신고 자체는 보존 (V15)").isNull();
	}

	@Test
	void 타_사용자의_영상_도감_뱃지와_grids_행은_삭제에_영향받지_않는다() {
		long otherId = createUser();
		// 삭제 유저 쪽이 조회수가 높아 삭제 전 대표 영상이다 — 삭제 후 재선정을 관찰하기 위한 배치.
		insertVideo(userId, 100);
		insertUserGrid(userId);
		long otherVideoId = insertVideo(otherId, 1);
		insertUserGrid(otherId);
		em.createNativeQuery(
				"INSERT INTO user_badges (user_id, badge_id) VALUES (:u, (SELECT min(id) FROM badges))")
			.setParameter("u", otherId).executeUpdate();

		userService.deleteAccount(userId, UNUSED_TOKEN);

		assertThat(countByUser("videos", "user_id", otherId)).as("타 사용자 영상").isEqualTo(1);
		assertThat(countByUser("user_grids", "user_id", otherId)).as("타 사용자 도감").isEqualTo(1);
		assertThat(countByUser("user_badges", "user_id", otherId)).as("타 사용자 뱃지").isEqualTo(1);
		long gridRows = ((Number) em.createNativeQuery("SELECT count(*) FROM grids WHERE grid_id = :g")
			.setParameter("g", GRID_ID).getSingleResult()).longValue();
		assertThat(gridRows).as("전역 격자 행은 영구").isEqualTo(1);
		// FR-8: 전역 노출면 정합 — 대표 영상은 videos 실시간 조회라 별도 보정 없이 남은 사용자 영상이 나온다.
		assertThat(videoRepository.findGlobalCover(GRID_ID))
			.as("격자 대표 영상은 남은 사용자 영상으로")
			.hasValueSatisfying(cover -> assertThat(cover.getId()).isEqualTo(otherVideoId));
	}

	@Test
	void 이미_삭제된_유저의_재호출은_1404를_반환한다() {
		userService.deleteAccount(userId, UNUSED_TOKEN);

		assertThatThrownBy(() -> userService.deleteAccount(userId, UNUSED_TOKEN))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_NOT_FOUND);
	}
}
