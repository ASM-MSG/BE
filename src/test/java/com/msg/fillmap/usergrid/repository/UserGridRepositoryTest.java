package com.msg.fillmap.usergrid.repository;

import static com.msg.fillmap.region.RegionTestFixtures.CELL_AREA_M2;
import static com.msg.fillmap.region.RegionTestFixtures.cellBlockPolygonJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.region.repository.RegionRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 개인 도감 요약 집계 (실 PostGIS, MSG-152 · 방문 행정동 축 정정 MSG-246). 각 테스트는 자기 유저를 새로 만들고
 * @Transactional 로 롤백한다 — 공유 로컬 DB 오염(NonUniqueResult) 방지. 단언은 전부 자기 유저 기준으로만 한다
 * (전체 COUNT 금지). 방문 행정동 시딩은 프로덕션 형상 그대로 — videos.region_code 는 NULL 로 두고
 * 격자 라벨(grids.region_code, by-grid 귀속 MSG-167)만 시드한다.
 */
@SpringBootTest
@Transactional
@DisplayName("UserGridRepository 도감 요약 집계 (실 PostGIS)")
class UserGridRepositoryTest {

	// 공해상(서해) 기준점 — ST_Covers 라벨링에 실데이터 행정동(11… 대역, 999 보다 앞 정렬)이 끼어들 수 없는
	// 좌표 대역. CollectionGridsRegionNameTest(GY 17516/GX 7535)와 다른 블록으로 격리.
	private static final long GY0 = 17413L;
	private static final long GX0 = 7637L;

	// 합성 행정동(999 대역) — 동A 는 dx [0,2) 격자 2개, 동B 는 dx [2,3) 격자 1개를 덮는다.
	private static final String 동A = "9992460001";
	private static final String 동B = "9992460002";

	@Autowired
	private UserGridRepository userGridRepository;

	@Autowired
	private RegionRepository regionRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

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
	@DisplayName("프로덕션 데이터 형상에서 방문 행정동 수가 1 이상이다")
	void 프로덕션_데이터_형상에서_방문_행정동_수가_1_이상이다() {
		long me = newUser();
		seedRegion(동A, 0, 2);
		String g = labeledGrid(0, 0);
		occupy(me, g, 1);
		video(me, g, "ACTIVE");

		CollectionSummaryProjection summary = userGridRepository.getCollectionSummary(me);

		// 프로덕션 쓰기 경로는 videos.region_code 를 채우지 않는다(MSG-246) — 격자 라벨 축이어야 1 이 나온다.
		assertThat(summary.getVisitedRegionCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("방문 행정동 수는 영상이 있는 격자들의 행정동 distinct 개수다")
	void 방문_행정동_수는_영상이_있는_격자들의_행정동_distinct_개수다() {
		long me = newUser();
		seedRegion(동A, 0, 2);
		seedRegion(동B, 2, 3);
		String a1 = labeledGrid(0, 0);
		String a2 = labeledGrid(0, 1);
		String b = labeledGrid(0, 2);
		occupy(me, a1, 1);
		occupy(me, a2, 1);
		occupy(me, b, 1);
		video(me, a1, "ACTIVE");
		video(me, a2, "ACTIVE");
		video(me, b, "ACTIVE");

		CollectionSummaryProjection summary = userGridRepository.getCollectionSummary(me);

		assertThat(summary.getVisitedRegionCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("라벨 없는 격자의 영상은 방문 행정동 수에 포함되지 않는다")
	void 라벨_없는_격자의_영상은_방문_행정동_수에_포함되지_않는다() {
		long me = newUser();
		seedRegion(동A, 0, 2);
		String labeled = labeledGrid(0, 0);
		String unlabeled = grid(0, 3);
		occupy(me, labeled, 1);
		occupy(me, unlabeled, 1);
		video(me, labeled, "ACTIVE");
		video(me, unlabeled, "ACTIVE");

		CollectionSummaryProjection summary = userGridRepository.getCollectionSummary(me);

		// 무귀속(해안 등) 격자는 grids.region_code NULL → COUNT DISTINCT 가 자동 제외.
		assertThat(summary.getVisitedRegionCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("DELETED 영상만 있는 행정동은 방문 수에 포함되지 않는다")
	void DELETED_영상만_있는_행정동은_방문_수에_포함되지_않는다() {
		long me = newUser();
		seedRegion(동A, 0, 2);
		seedRegion(동B, 2, 3);
		String a = labeledGrid(0, 0);
		String b = labeledGrid(0, 2);
		occupy(me, a, 1);
		video(me, a, "ACTIVE");
		video(me, b, "DELETED");

		CollectionSummaryProjection summary = userGridRepository.getCollectionSummary(me);

		assertThat(summary.getVisitedRegionCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("BLINDED 영상의 행정동은 방문 행정동 수에 포함된다")
	void BLINDED_영상의_행정동은_방문_행정동_수에_포함된다() {
		long me = newUser();
		seedRegion(동A, 0, 2);
		seedRegion(동B, 2, 3);
		String a = labeledGrid(0, 0);
		String b = labeledGrid(0, 2);
		occupy(me, a, 1);
		occupy(me, b, 1);
		video(me, a, "ACTIVE");
		video(me, b, "BLINDED");

		CollectionSummaryProjection summary = userGridRepository.getCollectionSummary(me);

		// BLINDED(모더레이션 숨김)는 방문 사실로 보고 포함(MSG-152 D6 승계).
		assertThat(summary.getVisitedRegionCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("다른 사용자의 점령과 영상은 집계에 포함되지 않는다")
	void 다른_사용자의_점령과_영상은_집계에_포함되지_않는다() {
		long me = newUser();
		long other = newUser();
		seedRegion(동A, 0, 2);
		seedRegion(동B, 2, 3);
		String a = labeledGrid(0, 0);
		String b = labeledGrid(0, 2);
		occupy(me, a, 1);
		video(me, a, "ACTIVE");
		occupy(other, a, 9);
		video(other, a, "ACTIVE");
		occupy(other, b, 1);
		video(other, b, "ACTIVE");

		CollectionSummaryProjection summary = userGridRepository.getCollectionSummary(me);

		// other 의 동B 방문이 내 행정동 수로 새면 2 가 된다 — 사용자 격리 확인.
		assertThat(summary.getTotalGridCount()).isEqualTo(1);
		assertThat(summary.getTotalVideoCount()).isEqualTo(1L);
		assertThat(summary.getVisitedRegionCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("스트릭 행이 없는 사용자는 새 세 지표가 모두 0이다")
	void 스트릭_행이_없는_사용자는_새_세_지표가_모두_0이다() {
		long me = newUser();

		CollectionSummaryProjection summary = userGridRepository.getCollectionSummary(me);

		// FR-3: streaks 서브쿼리 NULL → 바깥 COALESCE 로 0, user_badges COUNT 는 0(NULL 아님).
		assertThat(summary.getCurrentStreak()).isEqualTo(0);
		assertThat(summary.getMaxStreak()).isEqualTo(0);
		assertThat(summary.getBadgeCount()).isEqualTo(0);
	}

	@Test
	@DisplayName("오늘 기록한 사용자는 현재 스트릭이 저장값 그대로다")
	void 오늘_기록한_사용자는_현재_스트릭이_저장값_그대로다() {
		long me = newUser();
		seedStreak(me, 12, 21, 0);

		CollectionSummaryProjection summary = userGridRepository.getCollectionSummary(me);

		assertThat(summary.getCurrentStreak()).isEqualTo(12);
		assertThat(summary.getMaxStreak()).isEqualTo(21);
	}

	@Test
	@DisplayName("마지막 기록이 어제면 현재 스트릭이 유지된다")
	void 마지막_기록이_어제면_현재_스트릭이_유지된다() {
		long me = newUser();
		seedStreak(me, 5, 9, 1);

		CollectionSummaryProjection summary = userGridRepository.getCollectionSummary(me);

		assertThat(summary.getCurrentStreak()).isEqualTo(5);
	}

	@Test
	@DisplayName("마지막 기록이 그제 이전이면 현재 스트릭은 0이고 최장 스트릭은 남는다")
	void 마지막_기록이_그제_이전이면_현재_스트릭은_0이고_최장_스트릭은_남는다() {
		long me = newUser();
		seedStreak(me, 7, 21, 2);

		CollectionSummaryProjection summary = userGridRepository.getCollectionSummary(me);

		// D-3: current_count 에 남은 끊긴 옛 값(7)이 그대로 나가면 안 된다. max 는 끊겨도 유지.
		assertThat(summary.getCurrentStreak()).isEqualTo(0);
		assertThat(summary.getMaxStreak()).isEqualTo(21);
	}

	@Test
	@DisplayName("획득한 뱃지 수가 요약에 실린다")
	void 획득한_뱃지_수가_요약에_실린다() {
		long me = newUser();
		grantBadge(me, "EXPLORER_1");
		grantBadge(me, "EXPLORER_10");

		CollectionSummaryProjection summary = userGridRepository.getCollectionSummary(me);

		assertThat(summary.getBadgeCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("기존 세 지표는 값이 변하지 않는다")
	void 기존_세_지표는_값이_변하지_않는다() {
		// 회귀: 서브쿼리 3개를 얹은 뒤에도 스트릭·뱃지 행 존재가 기존 세 지표에 영향을 주지 않는다.
		long me = newUser();
		seedRegion(동A, 0, 2);
		String g = labeledGrid(0, 0);
		occupy(me, g, 3);
		occupy(me, grid(0, 3), 2);
		video(me, g, "ACTIVE");
		seedStreak(me, 4, 8, 0);
		grantBadge(me, "EXPLORER_1");

		CollectionSummaryProjection summary = userGridRepository.getCollectionSummary(me);

		assertThat(summary.getTotalGridCount()).isEqualTo(2);
		assertThat(summary.getTotalVideoCount()).isEqualTo(5L);
		assertThat(summary.getVisitedRegionCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("같은 행정동의 격자 여러개여도 행정동 수는 한번만 센다")
	void 같은_행정동의_격자_여러개여도_행정동_수는_한번만_센다() {
		long me = newUser();
		seedRegion(동A, 0, 2);
		String a1 = labeledGrid(0, 0);
		String a2 = labeledGrid(0, 1);
		occupy(me, a1, 1);
		occupy(me, a2, 1);
		video(me, a1, "ACTIVE");
		video(me, a2, "ACTIVE");

		CollectionSummaryProjection summary = userGridRepository.getCollectionSummary(me);

		assertThat(summary.getVisitedRegionCount()).isEqualTo(1);
	}

	private long newUser() {
		String email = "usergrid-" + System.nanoTime() + "@example.com";
		return userRepository.save(User.createLocalUser(email, "hash", "요약테스터")).getId();
	}

	/**
	 * 스트릭 행 시드 — last_recorded_date 는 KST 오늘 기준 daysAgo 일 전
	 * (StreakCommandServiceIntegrationTest 의 seedStreak 패턴, KST 자정 직전 실행 시 하루 어긋날 수 있는
	 * 허용 오차 동일).
	 */
	private void seedStreak(long userId, int currentCount, int maxCount, int daysAgo) {
		em.createNativeQuery("""
			INSERT INTO streaks (user_id, current_count, max_count, last_recorded_date)
			VALUES (:userId, :current, :max, CAST(:lastDate AS date))
			""")
			.setParameter("userId", userId)
			.setParameter("current", currentCount)
			.setParameter("max", maxCount)
			.setParameter("lastDate", LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(daysAgo).toString())
			.executeUpdate();
	}

	/** V9 시드 뱃지를 code 로 지급한다 (BadgeQueryServiceIntegrationTest 의 grantRetro 패턴). */
	private void grantBadge(long userId, String code) {
		em.createNativeQuery("""
			INSERT INTO user_badges (user_id, badge_id)
			SELECT :userId, id FROM badges WHERE code = :code
			""")
			.setParameter("userId", userId)
			.setParameter("code", code)
			.executeUpdate();
	}

	private String grid(long dy, long dx) {
		return GridFixtures.seedGrid(em, GY0 + dy, GX0 + dx);
	}

	private String labeledGrid(long dy, long dx) {
		return GridFixtures.seedLabeledGrid(em, GY0 + dy, GX0 + dx);
	}

	private void occupy(long userId, String gridId, int videoCount) {
		GridFixtures.seedUserGrid(em, userId, gridId, videoCount);
	}

	/** GY0 행의 격자 dx 구간 [dxFrom, dxTo) 중심점을 덮는 합성 행정동(999 대역)을 시드한다. */
	private void seedRegion(String code, long dxFrom, long dxTo) {
		String polygon = cellBlockPolygonJson(GY0, GY0 + 1, GX0 + dxFrom, GX0 + dxTo);
		regionRepository.upsert(code, code, code.substring(0, 5), polygon, CELL_AREA_M2);
	}

	/** videos 네이티브 삽입 — 프로덕션 형상 그대로 region_code 미지정(NULL 유지). */
	private void video(long userId, String gridId, String status) {
		em.createNativeQuery("""
			INSERT INTO videos (user_id, grid_id, geom, duration_sec, recorded_at, status)
			VALUES (
				:userId, :gridId,
				ST_SetSRID(ST_MakePoint(124.89, 35.64), 4326)::geography,
				10, now(), :status
			)
			""")
			.setParameter("userId", userId)
			.setParameter("gridId", gridId)
			.setParameter("status", status)
			.executeUpdate();
	}
}
