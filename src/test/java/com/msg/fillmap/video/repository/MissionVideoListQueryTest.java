package com.msg.fillmap.video.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

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
import com.msg.fillmap.video.entity.Video;

/**
 * 미션 영상 목록 native 쿼리 검증 (실 PostGIS, MSG-390). 후보 술어(미션 격자 조인 + 미션 기간 +
 * 전역 공개 게이트 3종)와 정렬(recorded_at DESC, id DESC), 커서 경계를 본다. 판정 쿼리
 * (MissionRepository.findCompleted) 와 같은 조인·기간 조건을 물려받되 사용자 조건이 없고 게이트가 더 세다.
 *
 * <p>격리(공유 로컬 DB): 합성 유저·미션·격자만 만들고 @Transactional 롤백한다. 격자는 시드 데이터와
 * 겹치지 않도록 한국 밖 합성 인덱스(+7000)를 쓰고(MissionAwardQueryTest 선례), 미션은 매번 새로 만든
 * id 로만 조회해 시드 미션과 섞이지 않는다.
 */
@SpringBootTest
@Transactional
@DisplayName("VideoRepository 미션 영상 목록 조회 (실 PostGIS)")
class MissionVideoListQueryTest {

	private static final double 연희_LAT = 37.5686;
	private static final double 연희_LON = 126.9292;

	// 시드·타 테스트와 못 겹치는 합성 격자 오프셋 (한국 밖).
	private static final long SYNTHETIC_OFFSET = 7000L;

	@Autowired
	private VideoRepository videoRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	private long userId;
	private long baseY;
	private long baseX;

	@BeforeEach
	void setUp() {
		String email = "mission-video-" + System.nanoTime() + "@example.com";
		userId = userRepository.save(User.createLocalUser(email, "hash", "미션영상테스터")).getId();
		GridIndex base = GridEncoder.decode(GridEncoder.encode(연희_LAT, 연희_LON));
		baseY = base.gridY() + SYNTHETIC_OFFSET;
		baseX = base.gridX();
	}

	private String seedGrid(int offset) {
		return GridFixtures.seedGrid(em, baseY + offset, baseX);
	}

	private long insertMission(LocalDateTime startAt, LocalDateTime endAt) {
		String title = "MSG390-list-" + System.nanoTime();
		em.createNativeQuery("""
				INSERT INTO missions (type, title, start_at, end_at, target_count)
				VALUES ('EVENT', :title, :startAt, :endAt, 1)
				""")
			.setParameter("title", title)
			.setParameter("startAt", startAt)
			.setParameter("endAt", endAt)
			.executeUpdate();
		return ((Number) em.createNativeQuery("SELECT id FROM missions WHERE title = :title")
			.setParameter("title", title)
			.getSingleResult()).longValue();
	}

	private void insertMissionGrid(long missionId, String gridId) {
		em.createNativeQuery("INSERT INTO mission_grids (mission_id, grid_id) VALUES (:missionId, :gridId)")
			.setParameter("missionId", missionId)
			.setParameter("gridId", gridId)
			.executeUpdate();
	}

	/** 후보 술어 전 조건(ACTIVE·PUBLIC·READY)을 만족하는 영상. */
	private long publicReady(String gridId, LocalDateTime recordedAt) {
		return insertVideo(gridId, recordedAt, "ACTIVE", "PUBLIC", "READY");
	}

	private long insertVideo(String gridId, LocalDateTime recordedAt,
		String status, String visibility, String processingStatus) {
		em.createNativeQuery("""
				INSERT INTO videos (user_id, grid_id, geom, duration_sec, recorded_at,
					status, visibility, processing_status)
				VALUES (
					:userId, :gridId,
					ST_SetSRID(ST_MakePoint(126.92, 37.56), 4326)::geography,
					10, :recordedAt, :status, :visibility, :processingStatus
				)
				""")
			.setParameter("userId", userId)
			.setParameter("gridId", gridId)
			.setParameter("recordedAt", recordedAt)
			.setParameter("status", status)
			.setParameter("visibility", visibility)
			.setParameter("processingStatus", processingStatus)
			.executeUpdate();
		// 같은 커넥션의 직전 시퀀스 값 = 방금 넣은 영상 id (native INSERT 라 엔티티 id 를 못 받는다).
		return ((Number) em.createNativeQuery("SELECT lastval()").getSingleResult()).longValue();
	}

	private List<Long> list(long missionId, int limit) {
		em.flush();
		em.clear();
		return videoRepository.findMissionVideos(missionId, limit).stream().map(Video::getId).toList();
	}

	private List<Long> listAfter(long missionId, LocalDateTime recordedAt, long id, int limit) {
		em.flush();
		em.clear();
		return videoRepository.findMissionVideosAfter(missionId, recordedAt, id, limit)
			.stream().map(Video::getId).toList();
	}

	private static LocalDateTime at(int day, int hour) {
		return LocalDateTime.of(2026, 7, day, hour, 0, 0);
	}

	@Test
	@DisplayName("미션 격자에서 촬영된 공개 영상만 최신순으로 나온다")
	void 미션_격자에서_촬영된_공개_영상만_최신순으로_나온다() {
		long mission = insertMission(at(1, 0), at(31, 23));
		String first = seedGrid(0);
		String second = seedGrid(1);
		insertMissionGrid(mission, first);
		insertMissionGrid(mission, second);
		long oldest = publicReady(first, at(10, 9));
		long newest = publicReady(second, at(20, 18));
		long middle = publicReady(first, at(15, 12));

		assertThat(list(mission, 20)).containsExactly(newest, middle, oldest);
	}

	@Test
	@DisplayName("미션 격자가 아닌 격자의 영상은 빠진다")
	void 미션_격자가_아닌_격자의_영상은_빠진다() {
		long mission = insertMission(at(1, 0), at(31, 23));
		String target = seedGrid(0);
		String outside = seedGrid(1);
		insertMissionGrid(mission, target);
		long inside = publicReady(target, at(10, 9));
		publicReady(outside, at(11, 9));

		assertThat(list(mission, 20)).containsExactly(inside);
	}

	@Test
	@DisplayName("미션 기간 밖에 촬영된 영상은 빠진다")
	void 미션_기간_밖에_촬영된_영상은_빠진다() {
		long mission = insertMission(at(10, 0), at(20, 0));
		String gridId = seedGrid(0);
		insertMissionGrid(mission, gridId);
		publicReady(gridId, at(9, 23));    // 시작 전
		publicReady(gridId, at(20, 1));    // 종료 후
		long inside = publicReady(gridId, at(15, 12));

		assertThat(list(mission, 20)).containsExactly(inside);
	}

	@Test
	@DisplayName("기간 경계 정각에 촬영된 영상은 포함된다 (양끝 포함 — 판정 쿼리와 같다)")
	void 기간_경계_정각에_촬영된_영상은_포함된다() {
		long mission = insertMission(at(10, 0), at(20, 0));
		String gridId = seedGrid(0);
		insertMissionGrid(mission, gridId);
		long startEdge = publicReady(gridId, at(10, 0));
		long endEdge = publicReady(gridId, at(20, 0));

		assertThat(list(mission, 20)).containsExactly(endEdge, startEdge);
	}

	@Test
	@DisplayName("기간이 없는 미션은 과거 영상도 모두 나온다 (코스·지속형 — IS NULL OR 자연 생략)")
	void 기간이_없는_미션은_과거_영상도_모두_나온다() {
		long mission = insertMission(null, null);
		String gridId = seedGrid(0);
		insertMissionGrid(mission, gridId);
		long ancient = insertVideo(gridId, LocalDateTime.of(2001, 3, 4, 5, 6), "ACTIVE", "PUBLIC", "READY");
		long recent = publicReady(gridId, at(20, 18));

		assertThat(list(mission, 20)).containsExactly(recent, ancient);
	}

	@Test
	@DisplayName("기간이 끝난 미션도 목록이 조회된다 (활성 여부를 보지 않는다)")
	void 기간이_끝난_미션도_목록이_조회된다() {
		long mission = insertMission(LocalDateTime.of(2020, 1, 1, 0, 0), LocalDateTime.of(2020, 1, 31, 0, 0));
		String gridId = seedGrid(0);
		insertMissionGrid(mission, gridId);
		long ended = insertVideo(gridId, LocalDateTime.of(2020, 1, 15, 12, 0), "ACTIVE", "PUBLIC", "READY");

		assertThat(list(mission, 20)).containsExactly(ended);
	}

	@Test
	@DisplayName("비공개 영상과 친구 공개 영상은 본인 것이라도 빠진다 (소유자 분기 없음)")
	void 비공개_영상과_친구_공개_영상은_본인_것이라도_빠진다() {
		long mission = insertMission(at(1, 0), at(31, 23));
		String gridId = seedGrid(0);
		insertMissionGrid(mission, gridId);
		insertVideo(gridId, at(10, 9), "ACTIVE", "PRIVATE", "READY");
		insertVideo(gridId, at(11, 9), "ACTIVE", "FRIENDS", "READY");
		long open = publicReady(gridId, at(12, 9));

		assertThat(list(mission, 20)).containsExactly(open);
	}

	@Test
	@DisplayName("삭제되거나 블라인드된 영상은 빠진다 (status = ACTIVE)")
	void 삭제되거나_블라인드된_영상은_빠진다() {
		long mission = insertMission(at(1, 0), at(31, 23));
		String gridId = seedGrid(0);
		insertMissionGrid(mission, gridId);
		insertVideo(gridId, at(10, 9), "DELETED", "PUBLIC", "READY");
		insertVideo(gridId, at(11, 9), "BLINDED", "PUBLIC", "READY");
		long alive = publicReady(gridId, at(12, 9));

		assertThat(list(mission, 20)).containsExactly(alive);
	}

	@Test
	@DisplayName("인코딩이 끝나지 않은 영상은 빠진다 (processing_status = READY)")
	void 인코딩이_끝나지_않은_영상은_빠진다() {
		long mission = insertMission(at(1, 0), at(31, 23));
		String gridId = seedGrid(0);
		insertMissionGrid(mission, gridId);
		insertVideo(gridId, at(10, 9), "ACTIVE", "PUBLIC", "ENCODING");
		insertVideo(gridId, at(11, 9), "ACTIVE", "PUBLIC", "UPLOADED");
		long ready = publicReady(gridId, at(12, 9));

		assertThat(list(mission, 20)).containsExactly(ready);
	}

	@Test
	@DisplayName("촬영 시각이 같으면 id 내림차순으로 갈린다 (페이지 셔플 방지)")
	void 촬영_시각이_같으면_id_내림차순으로_갈린다() {
		long mission = insertMission(at(1, 0), at(31, 23));
		String gridId = seedGrid(0);
		insertMissionGrid(mission, gridId);
		long first = publicReady(gridId, at(10, 9));
		long second = publicReady(gridId, at(10, 9));
		long third = publicReady(gridId, at(10, 9));

		assertThat(list(mission, 20)).containsExactly(third, second, first);
	}

	@Test
	@DisplayName("한 미션에 같은 영상이 두 번 실리지 않는다 (조인 팬아웃 부재)")
	void 한_미션에_같은_영상이_두_번_실리지_않는다() {
		long mission = insertMission(at(1, 0), at(31, 23));
		String gridId = seedGrid(0);
		String other = seedGrid(1);
		// 미션이 격자를 여럿 가져도 영상은 격자 하나에만 속하고 mission_grids PK 가 (mission_id, grid_id)라
		// 짝이 한 번씩만 잡힌다 — DISTINCT 없이도 중복이 없다.
		insertMissionGrid(mission, gridId);
		insertMissionGrid(mission, other);
		long video = publicReady(gridId, at(10, 9));

		assertThat(list(mission, 20)).containsExactly(video);
	}

	@Test
	@DisplayName("격자가 겹치는 두 미션의 목록에 같은 영상이 각각 나온다 (중복 제거 대상 아님)")
	void 격자가_겹치는_두_미션의_목록에_같은_영상이_각각_나온다() {
		long festival = insertMission(at(1, 0), at(31, 23));
		long popup = insertMission(at(1, 0), at(31, 23));
		String shared = seedGrid(0);
		insertMissionGrid(festival, shared);
		insertMissionGrid(popup, shared);
		long video = publicReady(shared, at(10, 9));

		assertThat(list(festival, 20)).containsExactly(video);
		assertThat(list(popup, 20)).containsExactly(video);
	}

	@Test
	@DisplayName("존재하지 않는 미션 ID 는 빈 결과다 (예외 아님)")
	void 존재하지_않는_미션_ID는_빈_결과다() {
		long mission = insertMission(at(1, 0), at(31, 23));
		String gridId = seedGrid(0);
		insertMissionGrid(mission, gridId);
		publicReady(gridId, at(10, 9));

		assertThat(list(-1L, 20)).isEmpty();
	}

	@Test
	@DisplayName("커서 이후 페이지는 경계값보다 뒤인 행만 돌려준다 (row-value 비교)")
	void 커서_이후_페이지는_경계값보다_뒤인_행만_돌려준다() {
		long mission = insertMission(at(1, 0), at(31, 23));
		String gridId = seedGrid(0);
		insertMissionGrid(mission, gridId);
		long oldest = publicReady(gridId, at(10, 9));
		long tieOld = publicReady(gridId, at(15, 12));
		long tieNew = publicReady(gridId, at(15, 12));   // 촬영 시각 동률 — id 로만 갈린다
		long newest = publicReady(gridId, at(20, 18));

		assertThat(list(mission, 20)).containsExactly(newest, tieNew, tieOld, oldest);
		// 동률 그룹 한복판(tieNew)이 경계면 같은 시각의 더 작은 id 부터 이어진다.
		assertThat(listAfter(mission, at(15, 12), tieNew, 20)).containsExactly(tieOld, oldest);
		assertThat(listAfter(mission, at(20, 18), newest, 20)).containsExactly(tieNew, tieOld, oldest);
	}

	// --- 정합 (술어 동등 확인, 문자열 대조 아님)

	@Test
	@DisplayName("같은 데이터에서 목록 길이와 상세 영상 개수가 같다 (미션 상세 집계 대조)")
	void 같은_데이터에서_목록_길이와_상세_영상_개수가_같다() {
		long mission = insertMission(at(10, 0), at(20, 0));
		String inside = seedGrid(0);
		String outside = seedGrid(1);
		insertMissionGrid(mission, inside);
		publicReady(inside, at(12, 9));
		publicReady(inside, at(15, 9));
		insertVideo(inside, at(13, 9), "ACTIVE", "PRIVATE", "READY");     // 게이트 탈락
		insertVideo(inside, at(14, 9), "ACTIVE", "PUBLIC", "ENCODING");   // 게이트 탈락
		publicReady(inside, at(25, 9));                                   // 기간 밖
		publicReady(outside, at(12, 9));                                  // 미션 격자 밖

		// 미션 상세의 영상 개수 집계는 후속 티켓 몫이라, 같은 술어로 세는 임시 카운트 쿼리로 대조한다.
		assertThat(list(mission, 100)).hasSize(countByCandidatePredicate(mission));
	}

	/** 후보 술어를 그대로 세는 임시 집계 — 상세 개수 집계가 붙으면 그 쿼리로 교체한다. */
	private int countByCandidatePredicate(long missionId) {
		em.flush();
		em.clear();
		return ((Number) em.createNativeQuery("""
				SELECT COUNT(*) FROM videos v
				JOIN mission_grids mg ON mg.grid_id = v.grid_id
				JOIN missions m ON m.id = mg.mission_id
				WHERE mg.mission_id = :missionId
				  AND v.status = 'ACTIVE' AND v.visibility = 'PUBLIC' AND v.processing_status = 'READY'
				  AND (m.start_at IS NULL OR v.recorded_at >= m.start_at)
				  AND (m.end_at IS NULL OR v.recorded_at <= m.end_at)
				""")
			.setParameter("missionId", missionId)
			.getSingleResult()).intValue();
	}

	@Test
	@DisplayName("게이트 조건 세 개가 격자 전역 목록과 같은 행 집합을 고른다")
	void 게이트_조건_세_개가_격자_전역_목록과_같은_행_집합을_고른다() {
		// 무기간 미션 + 격자 하나로 두 조회의 후보 차이를 게이트 3종만 남긴다 — 나머지 조건(기간·격자)은
		// 양쪽 모두 통과시키는 배치라, 포함 여부가 갈리면 그건 게이트가 어긋났다는 뜻이다.
		long mission = insertMission(null, null);
		String gridId = seedGrid(0);
		insertMissionGrid(mission, gridId);
		insertVideo(gridId, at(10, 9), "ACTIVE", "PRIVATE", "READY");
		insertVideo(gridId, at(11, 9), "ACTIVE", "FRIENDS", "READY");
		insertVideo(gridId, at(12, 9), "DELETED", "PUBLIC", "READY");
		insertVideo(gridId, at(13, 9), "BLINDED", "PUBLIC", "READY");
		insertVideo(gridId, at(14, 9), "ACTIVE", "PUBLIC", "ENCODING");
		publicReady(gridId, at(15, 9));
		publicReady(gridId, at(16, 9));

		em.flush();
		em.clear();
		List<Long> globalIds = videoRepository.findGlobalVideos(gridId, 100).stream().map(Video::getId).toList();

		assertThat(list(mission, 100)).containsExactlyInAnyOrderElementsOf(globalIds);
	}
}
