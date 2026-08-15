package com.msg.fillmap.mission.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

/**
 * 방문 격자 native 쿼리 검증 (실 PostgreSQL, MSG-399 §데이터 모델). 방문 = 미션 기간 안에 촬영한 내
 * 영상(status &lt;&gt; 'DELETED', 블라인드 포함)이 있는 대상 격자 — videos 조인 조건이 진행도 쿼리
 * (MissionRepository.findProgress, MSG-398 D8)와 글자 단위로 같음을 고정한다. 같은 격자의 재방문
 * 영상 여러 개는 DISTINCT 로 한 번만 나온다.
 *
 * 격리(공유 로컬 DB): 합성 유저·미션·격자만 만들고 @Transactional 롤백. 단언은 내 미션 id 로 스코프한다
 * (MissionProgressQueryTest 와 같은 구조).
 */
@SpringBootTest
@Transactional
@DisplayName("MissionGridRepository 방문 격자 쿼리 (기간 내 내 영상 술어, 실 PostgreSQL)")
class MissionVisitedGridQueryTest {

	// 다른 테스트(합정·연희·성수 등)와 겹치지 않는 기준점 (망원).
	private static final double 망원_LAT = 37.5556;
	private static final double 망원_LON = 126.9105;

	@Autowired
	private MissionGridRepository missionGridRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	private long userId;
	private long baseY;
	private long baseX;

	@BeforeEach
	void setUp() {
		userId = newUser();
		GridIndex base = GridEncoder.decode(GridEncoder.encode(망원_LAT, 망원_LON));
		baseY = base.gridY();
		baseX = base.gridX();
	}

	// 검증: FR-MISSION-18
	@Test
	@DisplayName("기간 내 촬영 영상이 있는 미션 격자만 방문한 격자로 조회한다")
	void 기간_내_촬영_영상이_있는_미션_격자만_방문한_격자로_조회한다() {
		long mission = insertMission(nowUtc().minusDays(1), nowUtc().plusDays(1));
		String visited = seedGrid(0);
		String beforeStart = seedGrid(1);
		String outside = seedGrid(2);
		insertMissionGrid(mission, visited);
		insertMissionGrid(mission, beforeStart);
		seedVideo(userId, visited, nowUtc(), "ACTIVE");
		seedVideo(userId, beforeStart, nowUtc().minusYears(1), "ACTIVE");   // 기간 밖 — 진행도와 같은 술어
		seedVideo(userId, outside, nowUtc(), "ACTIVE");                     // 미션 격자 밖
		seedVideo(newUser(), visited, nowUtc(), "ACTIVE");                  // 남의 영상은 방문이 아니다

		assertThat(missionGridRepository.findVisitedGridIds(mission, userId)).containsExactly(visited);
	}

	// 검증: FR-MISSION-17
	@Test
	@DisplayName("영상이 없는 포토스팟은 방문 목록에서 빠진다")
	void 영상이_없는_포토스팟은_방문_목록에서_빠진다() {
		long mission = insertMission(nowUtc().minusDays(1), nowUtc().plusDays(1));
		String visited = seedGrid(0);
		String untouched = seedGrid(1);
		insertMissionGrid(mission, visited);
		insertMissionGrid(mission, untouched);
		seedVideo(userId, visited, nowUtc(), "ACTIVE");

		List<String> visitedGrids = missionGridRepository.findVisitedGridIds(mission, userId);

		assertThat(visitedGrids).containsExactly(visited);
		assertThat(visitedGrids).doesNotContain(untouched);
	}

	// 검증: FR-MISSION-17
	@Test
	@DisplayName("같은 격자의 재방문 영상 여러 개는 방문 한 번으로 조회한다 — DISTINCT를 지우면 깨진다")
	void 같은_격자의_재방문_영상_여러_개는_방문_한_번으로_조회한다() {
		// videos 조인은 재방문으로 팬아웃한다 — DISTINCT 가 아니면 같은 grid_id 가 세 번 나간다.
		long mission = insertMission(nowUtc().minusDays(1), nowUtc().plusDays(1));
		String gridId = seedGrid(0);
		insertMissionGrid(mission, gridId);
		seedVideo(userId, gridId, nowUtc(), "ACTIVE");
		seedVideo(userId, gridId, nowUtc(), "ACTIVE");
		seedVideo(userId, gridId, nowUtc(), "ACTIVE");

		assertThat(missionGridRepository.findVisitedGridIds(mission, userId)).containsExactly(gridId);
	}

	// 검증: FR-MISSION-18
	@Test
	@DisplayName("삭제 영상은 방문에서 빠지고 블라인드 영상은 방문으로 잡힌다 — 진행도와 같은 status 가드")
	void 삭제_영상은_방문에서_빠지고_블라인드_영상은_방문으로_잡힌다() {
		long mission = insertMission(nowUtc().minusDays(1), nowUtc().plusDays(1));
		String deleted = seedGrid(0);
		String blinded = seedGrid(1);
		insertMissionGrid(mission, deleted);
		insertMissionGrid(mission, blinded);
		seedVideo(userId, deleted, nowUtc(), "DELETED");
		// BLINDED 는 촬영 사실이 유효해 포함 — findProgress 와 같은 status <> 'DELETED' 하나만 거른다.
		seedVideo(userId, blinded, nowUtc(), "BLINDED");

		assertThat(missionGridRepository.findVisitedGridIds(mission, userId)).containsExactly(blinded);
	}

	private LocalDateTime nowUtc() {
		return LocalDateTime.now(ZoneOffset.UTC);
	}

	private long newUser() {
		String email = "mission-visited-" + System.nanoTime() + "@example.com";
		return userRepository.save(User.createLocalUser(email, "hash", "방문테스터")).getId();
	}

	/** (baseY + offset, baseX) 셀을 grids 에 시드하고 grid_id 를 돌려준다 — videos FK 용. */
	private String seedGrid(int offset) {
		return GridFixtures.seedGrid(em, baseY + offset, baseX);
	}

	private long insertMission(LocalDateTime startAt, LocalDateTime endAt) {
		String title = "MSG399-visited-" + System.nanoTime();
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

	/** videos.geom NOT NULL — 픽스처에 지오메트리 포함 필수 (MissionProgressQueryTest.seedVideo 선례). */
	private void seedVideo(long ownerId, String gridId, LocalDateTime recordedAt, String status) {
		em.createNativeQuery("""
				INSERT INTO videos (user_id, grid_id, geom, duration_sec, recorded_at, status)
				VALUES (
					:userId, :gridId,
					ST_SetSRID(ST_MakePoint(126.91, 37.55), 4326)::geography,
					10, :recordedAt, :status
				)
				""")
			.setParameter("userId", ownerId)
			.setParameter("gridId", gridId)
			.setParameter("recordedAt", recordedAt)
			.setParameter("status", status)
			.executeUpdate();
	}
}
