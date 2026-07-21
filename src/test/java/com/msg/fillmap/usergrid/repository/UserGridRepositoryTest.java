package com.msg.fillmap.usergrid.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 개인 도감 요약 집계 (실 PostGIS, MSG-152). 각 테스트는 자기 유저를 새로 만들고 @Transactional 로
 * 롤백한다 — 공유 로컬 DB 오염(NonUniqueResult) 방지. 단언은 전부 자기 유저 기준으로만 한다(전체 COUNT 금지).
 */
@SpringBootTest
@Transactional
@DisplayName("UserGridRepository 도감 요약 집계 (실 PostGIS)")
class UserGridRepositoryTest {

	// 다른 테스트와 겹치지 않는 임의 기준점. 실제 grid_y/grid_x 는 GridEncoder 로 산출한다.
	private static final double 잠실_LAT = 37.5133;
	private static final double 잠실_LON = 127.1000;

	@Autowired
	private UserGridRepository userGridRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	private long baseY;
	private long baseX;

	@BeforeEach
	void setUp() {
		GridIndex base = GridEncoder.decode(GridEncoder.encode(잠실_LAT, 잠실_LON));
		baseY = base.gridY();
		baseX = base.gridX();
	}

	@Test
	@DisplayName("점령 격자 수와 영상 총합을 집계한다")
	void 점령_격자_수와_영상_총합을_집계한다() {
		long me = newUser();
		occupy(me, grid(0, 0), 3);
		occupy(me, grid(1, 1), 2);

		CollectionSummaryProjection summary = userGridRepository.getCollectionSummary(me);

		assertThat(summary.getTotalGridCount()).isEqualTo(2);
		assertThat(summary.getTotalVideoCount()).isEqualTo(5L);
	}

	@Test
	@DisplayName("점령이 없는 사용자는 격자수0 영상합0을 반환한다")
	void 점령이_없는_사용자는_격자수0_영상합0을_반환한다() {
		long me = newUser();

		CollectionSummaryProjection summary = userGridRepository.getCollectionSummary(me);

		// SUM 이 NULL → COALESCE 로 0(NULL 아님).
		assertThat(summary.getTotalGridCount()).isEqualTo(0);
		assertThat(summary.getTotalVideoCount()).isEqualTo(0L);
	}

	@Test
	@DisplayName("방문 행정동 수는 videos region_code 의 distinct 개수다")
	void 방문_행정동_수는_videos_region_code의_distinct_개수다() {
		long me = newUser();
		String g = grid(0, 0);
		occupy(me, g, 3);
		video(me, g, "TR152A", "ACTIVE");
		video(me, g, "TR152B", "ACTIVE");
		video(me, g, "TR152C", "ACTIVE");

		CollectionSummaryProjection summary = userGridRepository.getCollectionSummary(me);

		assertThat(summary.getVisitedRegionCount()).isEqualTo(3);
	}

	@Test
	@DisplayName("region_code가 null인 영상은 방문 행정동 수에 포함되지 않는다")
	void region_code가_null인_영상은_방문_행정동_수에_포함되지_않는다() {
		long me = newUser();
		String g = grid(0, 0);
		occupy(me, g, 2);
		video(me, g, "TR152A", "ACTIVE");
		video(me, g, null, "ACTIVE");

		CollectionSummaryProjection summary = userGridRepository.getCollectionSummary(me);

		// regions 미시딩·좌표 밖 업로드로 region_code 가 NULL 이어도 계산 가능(COUNT DISTINCT 가 NULL 제외).
		assertThat(summary.getVisitedRegionCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("DELETED 영상의 행정동은 방문 수에 포함되지 않는다")
	void DELETED_영상의_행정동은_방문_수에_포함되지_않는다() {
		long me = newUser();
		String g = grid(0, 0);
		occupy(me, g, 2);
		video(me, g, "TR152A", "ACTIVE");
		video(me, g, "TR152B", "DELETED");

		CollectionSummaryProjection summary = userGridRepository.getCollectionSummary(me);

		assertThat(summary.getVisitedRegionCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("BLINDED 영상의 행정동은 방문 수에 포함된다")
	void BLINDED_영상의_행정동은_방문_수에_포함된다() {
		long me = newUser();
		String g = grid(0, 0);
		occupy(me, g, 2);
		video(me, g, "TR152A", "ACTIVE");
		video(me, g, "TR152B", "BLINDED");

		CollectionSummaryProjection summary = userGridRepository.getCollectionSummary(me);

		// BLINDED(모더레이션 숨김)는 방문 사실로 보고 포함(D6).
		assertThat(summary.getVisitedRegionCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("다른 사용자의 점령과 영상은 집계에 포함되지 않는다")
	void 다른_사용자의_점령과_영상은_집계에_포함되지_않는다() {
		long me = newUser();
		long other = newUser();
		String g = grid(0, 0);
		occupy(me, g, 1);
		video(me, g, "TR152A", "ACTIVE");
		occupy(other, g, 9);
		video(other, g, "TR152B", "ACTIVE");
		video(other, g, "TR152C", "ACTIVE");

		CollectionSummaryProjection summary = userGridRepository.getCollectionSummary(me);

		assertThat(summary.getTotalGridCount()).isEqualTo(1);
		assertThat(summary.getTotalVideoCount()).isEqualTo(1L);
		assertThat(summary.getVisitedRegionCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("같은 행정동에 영상이 여러개여도 행정동 수는 한번만 센다")
	void 같은_행정동에_영상이_여러개여도_행정동_수는_한번만_센다() {
		long me = newUser();
		String g = grid(0, 0);
		occupy(me, g, 3);
		video(me, g, "TR152A", "ACTIVE");
		video(me, g, "TR152A", "ACTIVE");
		video(me, g, "TR152A", "ACTIVE");

		CollectionSummaryProjection summary = userGridRepository.getCollectionSummary(me);

		assertThat(summary.getVisitedRegionCount()).isEqualTo(1);
	}

	private long newUser() {
		String email = "usergrid-" + System.nanoTime() + "@example.com";
		return userRepository.save(User.createLocalUser(email, "hash", "요약테스터")).getId();
	}

	private String grid(long dy, long dx) {
		return GridFixtures.seedGrid(em, baseY + dy, baseX + dx);
	}

	private void occupy(long userId, String gridId, int videoCount) {
		GridFixtures.seedUserGrid(em, userId, gridId, videoCount);
	}

	/**
	 * videos 네이티브 삽입 (region_code·status 지정). region_code 는 regions FK 라 먼저 시딩한다.
	 * null 은 CAST 로 타입을 지정해 바인딩한다.
	 */
	private void video(long userId, String gridId, String regionCode, String status) {
		if (regionCode != null) {
			seedRegion(regionCode);
		}
		em.createNativeQuery("""
			INSERT INTO videos (user_id, grid_id, region_code, geom, duration_sec, recorded_at, status)
			VALUES (
				:userId, :gridId, CAST(:regionCode AS varchar),
				ST_SetSRID(ST_MakePoint(127.10, 37.51), 4326)::geography,
				10, now(), :status
			)
			""")
			.setParameter("userId", userId)
			.setParameter("gridId", gridId)
			.setParameter("regionCode", regionCode)
			.setParameter("status", status)
			.executeUpdate();
	}

	private void seedRegion(String code) {
		em.createNativeQuery("""
			INSERT INTO regions (region_code, region_name, boundary_geom)
			VALUES (
				:code, :code,
				ST_GeomFromText('MULTIPOLYGON(((127.0 37.5, 127.0 37.6, 127.1 37.6, 127.1 37.5, 127.0 37.5)))', 4326)
			)
			ON CONFLICT (region_code) DO NOTHING
			""")
			.setParameter("code", code)
			.executeUpdate();
	}
}
