package com.msg.fillmap.mission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.event.EventVideoFixtures;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.mission.config.MissionViewportProperties;
import com.msg.fillmap.mission.dto.MissionResponseDto;
import com.msg.fillmap.mission.entity.MissionType;
import com.msg.fillmap.mission.repository.MissionGridRepository;
import com.msg.fillmap.mission.repository.MissionRepository;
import com.msg.fillmap.mission.service.impl.MissionQueryServiceImpl;
import com.msg.fillmap.region.service.RegionQueryService;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.repository.VideoRepository;

/**
 * 목록 카드 영상 수 계약 검증 (MissionQueryServiceImpl.getMissionCardsInViewport, 실 PostGIS · MSG-597).
 * 카드가 그리던 "영상 N"이 화면이 지어낸 해시였던 자리를 서버 실측값으로 채운 것이라, 여기서 보는 것은
 * 값이 어떤 술어로 세어지는가와 집계가 몇 번 나가는가 둘이다. 술어 자체는 MSG-390·MSG-399 쿼리 테스트가
 * 이미 고정하고 있으므로 이 클래스는 목록 경로가 그 술어에 제대로 이어졌는지를 본다.
 *
 * <p>서비스는 매번 {@code newService()} 로 새로 만든다 — 전역 스냅숏 홀더를 공유하면 롤백되는 합성 미션이
 * 최대 1시간 다른 테스트의 스냅숏에 남는다(MissionViewportFilterTest 와 같은 이유).
 *
 * <p>격리(공유 로컬 DB): 합성 유저·미션·격자만 만들고 @Transactional 롤백한다. 격자는 시드 데이터와
 * 겹치지 않는 한국 밖 합성 인덱스(+7000)라, 이 뷰포트에 잡히는 미션은 이 tx 가 만든 것뿐이다.
 */
@SpringBootTest
@Transactional
@DisplayName("미션 목록 카드 영상 수 (실 PostGIS)")
class MissionListVideoCountTest {

	private static final double 연희_LAT = 37.5686;
	private static final double 연희_LON = 126.9292;

	// 시드·타 테스트와 못 겹치는 합성 격자 오프셋 (한국 밖).
	private static final long SYNTHETIC_OFFSET = 7000L;

	/** 미션도 영상도 없는 서해 원해 — 빈 목록 가드를 보는 뷰포트다(MissionViewportFilterTest 와 같은 좌표). */
	private static final ViewportBounds 빈_바다 = new ViewportBounds(33.01, 124.01, 33.05, 124.05);

	@Autowired
	private MissionRepository missionRepository;

	@Autowired
	private MissionGridRepository missionGridRepository;

	@Autowired
	private VideoRepository videoRepository;

	@Autowired
	private RegionQueryService regionQueryService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private EntityManager em;

	/** 실 리포지토리에 위임하는 목 — 값은 그대로 두고 집계 호출 횟수만 센다(AC-597-05·08). */
	private VideoRepository videoRepositorySpy;

	private long userId;
	private long baseY;
	private long baseX;

	@BeforeEach
	void setUp() {
		videoRepositorySpy = mock(VideoRepository.class, delegatesTo(videoRepository));
		userId = userRepository.save(User.createLocalUser(
			"mission-card-count-" + System.nanoTime() + "@example.com", "hash", "카드수테스터")).getId();
		GridIndex base = GridEncoder.decode(GridEncoder.encode(연희_LAT, 연희_LON));
		baseY = base.gridY() + SYNTHETIC_OFFSET;
		baseX = base.gridX();
	}

	/** 캐시를 비운 새 서비스 인스턴스 — 이 tx 의 미션만 재계산해 조회한다. */
	private MissionQueryService newService() {
		return new MissionQueryServiceImpl(missionRepository, missionGridRepository, videoRepositorySpy,
			objectMapper, new MissionViewportProperties(Map.of()), regionQueryService, Clock.systemUTC(),
			Duration.ofHours(1).toMillis());
	}

	/** 합성 격자 블록을 넉넉히 덮는 뷰포트 — 이 좌표대에는 시드 미션이 없다. */
	private ViewportBounds 합성_뷰포트() {
		GridPoint sw = GridFixtures.pointAt(baseY - 2 + 0.5, baseX - 2 + 0.5);
		GridPoint ne = GridFixtures.pointAt(baseY + 5 + 0.5, baseX + 5 + 0.5);
		return new ViewportBounds(sw.lat(), sw.lon(), ne.lat(), ne.lon());
	}

	private List<MissionResponseDto> 카드들() {
		em.flush();
		em.clear();
		return newService().getMissionCardsInViewport(합성_뷰포트(), MissionType.EVENT);
	}

	private long 카드_영상_수(long missionId) {
		return 카드들().stream()
			.filter(card -> card.missionId() == missionId)
			.findFirst()
			.orElseThrow()
			.videoCount();
	}

	// 검증: FR-MISSION-14, AC-597-02
	@Test
	@DisplayName("영상이 없는 미션도 목록에 실리고 videoCount는 0이다")
	void 영상이_없는_미션도_목록에_실리고_videoCount는_0이다() {
		long mission = 활성_미션();
		insertMissionGrid(mission, seedGrid(0));

		// 키가 빠지거나 null 이 아니라 0 이다(D3 long 0 채움) — 화면은 0 을 "표시하지 않음"으로 읽는다.
		assertThat(카드_영상_수(mission)).isZero();
	}

	// 검증: FR-MISSION-14, AC-597-01
	@Test
	@DisplayName("목록 videoCount는 그 미션에 올라온 전역 공개 영상 수와 같다")
	void 목록_videoCount는_그_미션에_올라온_전역_공개_영상_수와_같다() {
		long mission = 활성_미션();
		String first = seedGrid(0);
		String second = seedGrid(1);
		insertMissionGrid(mission, first);
		insertMissionGrid(mission, second);
		insertVideo(first, nowUtc(), "ACTIVE", "PUBLIC", "READY");
		insertVideo(first, nowUtc(), "ACTIVE", "PUBLIC", "READY");
		insertVideo(second, nowUtc(), "ACTIVE", "PUBLIC", "READY");

		assertThat(카드_영상_수(mission)).isEqualTo(3);
	}

	// 검증: FR-MISSION-14, AC-597-07
	@Test
	@DisplayName("기간 밖에 촬영된 영상은 목록 videoCount에서 빠진다")
	void 기간_밖에_촬영된_영상은_목록_videoCount에서_빠진다() {
		long mission = 활성_미션();
		String grid = seedGrid(0);
		insertMissionGrid(mission, grid);
		insertVideo(grid, nowUtc(), "ACTIVE", "PUBLIC", "READY");
		insertVideo(grid, nowUtc().minusDays(30), "ACTIVE", "PUBLIC", "READY");  // 미션 시작 전 촬영
		insertVideo(grid, nowUtc().plusDays(30), "ACTIVE", "PUBLIC", "READY");   // 미션 종료 후 촬영

		assertThat(카드_영상_수(mission)).isEqualTo(1);
	}

	// 검증: FR-MISSION-14, AC-597-07
	@Test
	@DisplayName("비공개와 친구공개와 인코딩중과 삭제 영상은 목록 videoCount에서 빠진다")
	void 비공개와_친구공개와_인코딩중과_삭제_영상은_목록_videoCount에서_빠진다() {
		long mission = 활성_미션();
		String grid = seedGrid(0);
		insertMissionGrid(mission, grid);
		insertVideo(grid, nowUtc(), "ACTIVE", "PUBLIC", "READY");
		insertVideo(grid, nowUtc(), "ACTIVE", "PRIVATE", "READY");
		insertVideo(grid, nowUtc(), "ACTIVE", "FRIENDS", "READY");
		insertVideo(grid, nowUtc(), "ACTIVE", "PUBLIC", "ENCODING");
		insertVideo(grid, nowUtc(), "DELETED", "PUBLIC", "READY");

		assertThat(카드_영상_수(mission)).isEqualTo(1);
	}

	// 검증: FR-MISSION-14, FR-EVENT-12, AC-597-07
	@Test
	@DisplayName("행사 영상은 목록 videoCount에서 빠진다 — MSG-450 안티조인")
	void 행사_영상은_목록_videoCount에서_빠진다() {
		long mission = 활성_미션();
		String grid = seedGrid(0);
		insertMissionGrid(mission, grid);
		insertVideo(grid, nowUtc(), "ACTIVE", "PUBLIC", "READY");
		// 게이트 3종을 전부 통과하지만 행사 위치에 올린 영상 — 미션 방문의 증거가 아니다.
		EventVideoFixtures.linkEventVideo(em, insertVideo(grid, nowUtc(), "ACTIVE", "PUBLIC", "READY"));

		assertThat(카드_영상_수(mission)).isEqualTo(1);
	}

	// 검증: FR-MISSION-02, AC-597-05
	@Test
	@DisplayName("뷰포트에 미션이 없으면 빈 배열이고 집계 쿼리를 부르지 않는다")
	void 뷰포트에_미션이_없으면_빈_배열이고_집계_쿼리를_부르지_않는다() {
		em.flush();
		em.clear();

		assertThat(newService().getMissionCardsInViewport(빈_바다, MissionType.POPUP)).isEmpty();

		// 빈 IN 절은 SQL 이 깨진다 — 가드를 지우면 쿼리가 나가고 여기서 드러난다.
		verify(videoRepositorySpy, never()).countVideosByMissionIds(any());
	}

	// 검증: FR-MISSION-14, AC-597-08
	@Test
	@DisplayName("미션이 여러 건이어도 집계 쿼리는 한 번만 실행된다")
	void 미션이_여러_건이어도_집계_쿼리는_한_번만_실행된다() {
		for (int offset = 0; offset < 3; offset++) {
			long mission = 활성_미션();
			insertMissionGrid(mission, seedGrid(offset));
			insertVideo(seedGrid(offset), nowUtc(), "ACTIVE", "PUBLIC", "READY");
		}
		em.flush();
		em.clear();

		List<MissionResponseDto> cards = newService().getMissionCardsInViewport(합성_뷰포트(), MissionType.EVENT);

		assertThat(cards).hasSize(3);
		assertThat(cards).allSatisfy(card -> assertThat(card.videoCount()).isEqualTo(1));
		// 미션마다 세면 N+1 이다 — 배치 집계라 미션 수와 무관하게 왕복 1회다.
		verify(videoRepositorySpy, times(1)).countVideosByMissionIds(any());
	}

	private static LocalDateTime nowUtc() {
		return LocalDateTime.now(ZoneOffset.UTC);
	}

	/** 지금 활성인 축제 미션 — 스냅숏(findActive)에 들어와야 목록 경로가 성립한다. */
	private long 활성_미션() {
		String title = "MSG597-card-" + System.nanoTime();
		em.createNativeQuery("""
				INSERT INTO missions (type, title, start_at, end_at, target_count)
				VALUES ('EVENT', :title, :startAt, :endAt, 1)
				""")
			.setParameter("title", title)
			.setParameter("startAt", nowUtc().minusDays(10))
			.setParameter("endAt", nowUtc().plusDays(10))
			.executeUpdate();
		return ((Number) em.createNativeQuery("SELECT id FROM missions WHERE title = :title")
			.setParameter("title", title)
			.getSingleResult()).longValue();
	}

	private String seedGrid(int offset) {
		return GridFixtures.seedGrid(em, baseY + offset, baseX);
	}

	private void insertMissionGrid(long missionId, String gridId) {
		em.createNativeQuery("INSERT INTO mission_grids (mission_id, grid_id) VALUES (:missionId, :gridId)")
			.setParameter("missionId", missionId)
			.setParameter("gridId", gridId)
			.executeUpdate();
	}

	/** videos.geom NOT NULL — 픽스처에 지오메트리 포함 필수 (MissionVideoListQueryTest.insertVideo 선례). */
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
}
