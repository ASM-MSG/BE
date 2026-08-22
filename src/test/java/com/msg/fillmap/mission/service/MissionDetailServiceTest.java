package com.msg.fillmap.mission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.mission.dto.MissionDetailResponseDto;
import com.msg.fillmap.mission.dto.MissionDetailResponseDto.SpotStats;
import com.msg.fillmap.mission.dto.MissionShape.PathShape;
import com.msg.fillmap.mission.dto.MissionShape.Spot;
import com.msg.fillmap.mission.exception.MissionErrorCode;
import com.msg.fillmap.mission.repository.MissionGridRepository;
import com.msg.fillmap.mission.repository.MissionRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 미션 상세 조립 검증 (MissionQueryServiceImpl, 실 PostGIS · MSG-399 §도메인 로직 · Owner B).
 * 진행도 재사용(MSG-398 findProgress)·기간 무판정·스팟 순서(seq ASC NULLS LAST)·0 채움·전체 합·
 * 비코스 빈 배열·404 를 본다. 상세는 전역 캐시를 타지 않아 공유 서비스 빈을 그대로 쓴다.
 *
 * <p>격리(공유 로컬 DB): 합성 유저·미션·격자만 만들고 @Transactional 롤백한다. 영상 개수 집계에 사용자
 * 조건이 없어 격자는 시드 데이터와 겹치지 않는 한국 밖 합성 인덱스(+7000)를 쓴다
 * (MissionVideoListQueryTest 선례).
 */
@SpringBootTest
@Transactional
@DisplayName("MissionQueryServiceImpl 미션 상세 조회 (실 PostGIS)")
class MissionDetailServiceTest {

	private static final double 상수_LAT = 37.5478;
	private static final double 상수_LON = 126.9227;

	// 시드·타 테스트와 못 겹치는 합성 격자 오프셋 (한국 밖).
	private static final long SYNTHETIC_OFFSET = 7000L;

	@Autowired
	private MissionQueryService missionQueryService;

	@Autowired
	private UserRepository userRepository;

	// 스파이(실동작 그대로) — 익명 상세가 사용자별 두 조회를 아예 건너뛰는지(MSG-454) 호출 여부로 본다.
	@MockitoSpyBean
	private MissionRepository missionRepository;

	@MockitoSpyBean
	private MissionGridRepository missionGridRepository;

	@Autowired
	private EntityManager em;

	private long userId;
	private long baseY;
	private long baseX;

	@BeforeEach
	void setUp() {
		userId = newUser();
		GridIndex base = GridEncoder.decode(GridEncoder.encode(상수_LAT, 상수_LON));
		baseY = base.gridY() + SYNTHETIC_OFFSET;
		baseX = base.gridX();
	}

	// 검증: FR-MISSION-18
	@Test
	@DisplayName("상세는 MSG398의 진행도와 완료 값을 그대로 쓴다 — 같은 계산 한 곳 (findProgress 재사용)")
	void 상세는_MSG398의_진행도와_완료_값을_그대로_쓴다() {
		long mission = insertMission("EVENT", nowUtc().minusDays(1), nowUtc().plusDays(1), 3);
		String first = seedGrid(0);
		String second = seedGrid(1);
		insertMissionGrid(mission, first, null);
		insertMissionGrid(mission, second, null);
		insertMissionGrid(mission, seedGrid(2), null);
		insertVideo(userId, first, nowUtc(), "ACTIVE", "PUBLIC", "READY");
		insertVideo(userId, second, nowUtc(), "ACTIVE", "PRIVATE", "READY");
		insertStamp(userId, mission);

		MissionDetailResponseDto detail = missionQueryService.getMissionDetail(mission, userId);

		// 목록 진행도(getMyProgress)와 같은 쿼리에서 나온 같은 값이어야 한다 — 계산 복제 금지 (§MSG-398 선행).
		assertThat(detail.progress())
			.isEqualTo(missionQueryService.getMyProgress(userId, List.of(mission)).get(0));
		assertThat(detail.progress().filledCount()).isEqualTo(2);
		assertThat(detail.progress().completed()).isTrue();
	}

	// 검증: FR-MISSION-16
	@Test
	@DisplayName("기간이 끝난 미션도 상세를 조회한다 — 활성 기간 조건은 목록 노출에만 쓴다")
	void 기간이_끝난_미션도_상세를_조회한다() {
		long mission = insertMission("EVENT", nowUtc().minusDays(10), nowUtc().minusDays(1), 1);
		String gridId = seedGrid(0);
		insertMissionGrid(mission, gridId, null);
		// 활성 당시 촬영분 — 종료 뒤에도 상세와 영상 집계에 그대로 잡힌다.
		insertVideo(userId, gridId, nowUtc().minusDays(5), "ACTIVE", "PUBLIC", "READY");

		MissionDetailResponseDto detail = missionQueryService.getMissionDetail(mission, userId);

		assertThat(detail.mission().missionId()).isEqualTo(mission);
		assertThat(detail.videoCount()).isEqualTo(1);
		assertThat(detail.progress().filledCount()).isEqualTo(1);
	}

	// 검증: FR-MISSION-17
	@Test
	@DisplayName("영상이 없으면 전체와 스팟 개수는 0이다 — 200 응답에 실패가 없다")
	void 영상이_없으면_전체와_스팟_개수는_0이다() {
		long mission = insertMission("COURSE", null, null, 2);
		insertMissionGrid(mission, seedGrid(0), 1);
		insertMissionGrid(mission, seedGrid(1), 2);

		MissionDetailResponseDto detail = missionQueryService.getMissionDetail(mission, userId);

		assertThat(detail.videoCount()).isZero();
		assertThat(detail.spotStats()).hasSize(2);
		assertThat(detail.spotStats()).allSatisfy(spot -> {
			assertThat(spot.videoCount()).isZero();
			assertThat(spot.visited()).isFalse();
		});
	}

	// 검증: FR-MISSION-17
	@Test
	@DisplayName("코스 스팟은 seq순이고 방문 여부와 영상 개수를 담는다 — shape.spots와 같은 순서")
	void 코스_스팟은_seq순이고_방문_여부와_영상_개수를_담는다() {
		long mission = insertMission("COURSE", null, null, 3);
		String firstSpot = seedGrid(0);
		String secondSpot = seedGrid(1);
		String thirdSpot = seedGrid(2);
		// 삽입 순서를 섞어 seq 정렬이 삽입 순서 우연이 아님을 고정한다.
		insertMissionGrid(mission, thirdSpot, 3);
		insertMissionGrid(mission, firstSpot, 1);
		insertMissionGrid(mission, secondSpot, 2);
		insertVideo(userId, firstSpot, nowUtc(), "ACTIVE", "PUBLIC", "READY");         // 내 방문 + 공개 1
		insertVideo(newUser(), thirdSpot, nowUtc(), "ACTIVE", "PUBLIC", "READY");      // 남의 공개 — 방문 아님

		MissionDetailResponseDto detail = missionQueryService.getMissionDetail(mission, userId);

		assertThat(detail.spotStats()).containsExactly(
			new SpotStats(firstSpot, true, 1),
			new SpotStats(secondSpot, false, 0),
			new SpotStats(thirdSpot, false, 1));
		// spotStats 는 shape.spots 와 항목 단위로 대응한다 (§성공 기준).
		PathShape shape = (PathShape) detail.mission().shape();
		assertThat(shape.spots()).extracting(Spot::gridId)
			.containsExactly(firstSpot, secondSpot, thirdSpot);
	}

	// 검증: FR-MISSION-17
	@Test
	@DisplayName("코스 전체 영상 개수는 스팟별 영상 개수의 합이다 — 별도 COUNT 쿼리가 없다")
	void 코스_전체_영상_개수는_스팟별_영상_개수의_합이다() {
		long mission = insertMission("COURSE", null, null, 2);
		String firstSpot = seedGrid(0);
		String secondSpot = seedGrid(1);
		insertMissionGrid(mission, firstSpot, 1);
		insertMissionGrid(mission, secondSpot, 2);
		insertVideo(userId, firstSpot, nowUtc(), "ACTIVE", "PUBLIC", "READY");
		insertVideo(newUser(), firstSpot, nowUtc(), "ACTIVE", "PUBLIC", "READY");
		insertVideo(newUser(), secondSpot, nowUtc(), "ACTIVE", "PUBLIC", "READY");
		insertVideo(newUser(), secondSpot, nowUtc(), "ACTIVE", "PRIVATE", "READY");    // 게이트 탈락

		MissionDetailResponseDto detail = missionQueryService.getMissionDetail(mission, userId);

		assertThat(detail.videoCount()).isEqualTo(3);
		assertThat(detail.spotStats().stream().mapToLong(SpotStats::videoCount).sum())
			.isEqualTo(detail.videoCount());
	}

	// 검증: FR-MISSION-17
	@Test
	@DisplayName("코스가 아니면 스팟 조립을 하지 않는다 — spotStats는 null이 아니라 빈 배열")
	void 코스가_아니면_스팟_조립을_하지_않는다() {
		long mission = insertMission("EVENT", nowUtc().minusDays(1), nowUtc().plusDays(1), 1);
		String gridId = seedGrid(0);
		insertMissionGrid(mission, gridId, null);
		insertVideo(userId, gridId, nowUtc(), "ACTIVE", "PUBLIC", "READY");

		MissionDetailResponseDto detail = missionQueryService.getMissionDetail(mission, userId);

		assertThat(detail.spotStats()).isNotNull().isEmpty();
		// 스팟 조립만 빠질 뿐 전체 영상 개수는 유형과 무관하게 담긴다.
		assertThat(detail.videoCount()).isEqualTo(1);
	}

	// 검증: FR-MISSION-16
	@Test
	@DisplayName("익명 상세는 진행도가 null이고 스팟 방문이 전부 false다 — 전역 값은 로그인과 같다 (MSG-454)")
	void 익명_상세는_진행도가_null이고_스팟_방문이_전부_false다() {
		long mission = insertMission("COURSE", null, null, 2);
		String firstSpot = seedGrid(0);
		String secondSpot = seedGrid(1);
		insertMissionGrid(mission, firstSpot, 1);
		insertMissionGrid(mission, secondSpot, 2);
		insertVideo(userId, firstSpot, nowUtc(), "ACTIVE", "PUBLIC", "READY");
		insertStamp(userId, mission);

		MissionDetailResponseDto anonymous = missionQueryService.getMissionDetail(mission, null);

		assertThat(anonymous.progress()).isNull();
		assertThat(anonymous.spotStats()).extracting(SpotStats::visited).containsOnly(false);
		// 전역 값은 로그인 조회와 글자 그대로 같다 — 익명이라고 미션·영상 수가 달라지지 않는다.
		MissionDetailResponseDto loggedIn = missionQueryService.getMissionDetail(mission, userId);
		assertThat(anonymous.mission()).isEqualTo(loggedIn.mission());
		assertThat(anonymous.videoCount()).isEqualTo(loggedIn.videoCount());
		assertThat(anonymous.spotStats()).extracting(SpotStats::gridId, SpotStats::videoCount)
			.containsExactlyElementsOf(loggedIn.spotStats().stream()
				.map(spot -> tuple(spot.gridId(), spot.videoCount())).toList());
		// 로그인 쪽은 그대로 개인화 값을 담는다 (익명 분기가 로그인 경로를 갉아먹지 않았다).
		assertThat(loggedIn.progress().completed()).isTrue();
		assertThat(loggedIn.spotStats().get(0).visited()).isTrue();
	}

	// 검증: FR-MISSION-16
	@Test
	@DisplayName("익명 상세는 진행도와 방문 조회를 실행하지 않는다 — 사용자별 쿼리 두 건이 빠진다")
	void 익명_상세는_진행도와_방문_조회를_실행하지_않는다() {
		long mission = insertMission("COURSE", null, null, 1);
		insertMissionGrid(mission, seedGrid(0), 1);

		missionQueryService.getMissionDetail(mission, null);

		then(missionRepository).should(never()).findProgress(anyLong(), any());
		then(missionGridRepository).should(never()).findVisitedGridIds(anyLong(), anyLong());
	}

	// 검증: FR-MISSION-16
	@Test
	@DisplayName("존재하지 않는 미션은 MISSION_NOT_FOUND를 던진다 — 단일 리소스라 404로 구분")
	void 존재하지_않는_미션은_MISSION_NOT_FOUND를_던진다() {
		assertThatThrownBy(() -> missionQueryService.getMissionDetail(999_999_999L, userId))
			.isInstanceOf(ApiException.class)
			.extracting(e -> ((ApiException) e).getErrorCode())
			.isEqualTo(MissionErrorCode.MISSION_NOT_FOUND);
	}

	private LocalDateTime nowUtc() {
		return LocalDateTime.now(ZoneOffset.UTC);
	}

	private long newUser() {
		String email = "mission-detail-" + System.nanoTime() + "@example.com";
		return userRepository.save(User.createLocalUser(email, "hash", "상세테스터")).getId();
	}

	/** (baseY + offset, baseX) 셀을 grids 에 시드하고 grid_id 를 돌려준다 — videos FK 용. */
	private String seedGrid(int offset) {
		return GridFixtures.seedGrid(em, baseY + offset, baseX);
	}

	private long insertMission(String type, LocalDateTime startAt, LocalDateTime endAt, int targetCount) {
		String title = "MSG399-detail-" + System.nanoTime();
		em.createNativeQuery("""
				INSERT INTO missions (type, title, start_at, end_at, target_count)
				VALUES (:type, :title, :startAt, :endAt, :targetCount)
				""")
			.setParameter("type", type)
			.setParameter("title", title)
			.setParameter("startAt", startAt)
			.setParameter("endAt", endAt)
			.setParameter("targetCount", targetCount)
			.executeUpdate();
		return ((Number) em.createNativeQuery("SELECT id FROM missions WHERE title = :title")
			.setParameter("title", title)
			.getSingleResult()).longValue();
	}

	/** seq — 코스 포토스팟 순번(1..N), 순서 없는 유형은 null. */
	private void insertMissionGrid(long missionId, String gridId, Integer seq) {
		em.createNativeQuery("INSERT INTO mission_grids (mission_id, grid_id, seq) VALUES (:missionId, :gridId, :seq)")
			.setParameter("missionId", missionId)
			.setParameter("gridId", gridId)
			.setParameter("seq", seq)
			.executeUpdate();
	}

	private void insertStamp(long stampUserId, long missionId) {
		em.createNativeQuery("INSERT INTO user_missions (user_id, mission_id) VALUES (:userId, :missionId)")
			.setParameter("userId", stampUserId)
			.setParameter("missionId", missionId)
			.executeUpdate();
	}

	/** videos.geom NOT NULL — 픽스처에 지오메트리 포함 필수 (MissionVideoListQueryTest.insertVideo 선례). */
	private void insertVideo(long ownerId, String gridId, LocalDateTime recordedAt,
		String status, String visibility, String processingStatus) {
		em.createNativeQuery("""
				INSERT INTO videos (user_id, grid_id, geom, duration_sec, recorded_at,
					status, visibility, processing_status)
				VALUES (
					:userId, :gridId,
					ST_SetSRID(ST_MakePoint(126.92, 37.54), 4326)::geography,
					10, :recordedAt, :status, :visibility, :processingStatus
				)
				""")
			.setParameter("userId", ownerId)
			.setParameter("gridId", gridId)
			.setParameter("recordedAt", recordedAt)
			.setParameter("status", status)
			.setParameter("visibility", visibility)
			.setParameter("processingStatus", processingStatus)
			.executeUpdate();
	}
}
