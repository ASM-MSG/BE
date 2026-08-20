package com.msg.fillmap.mission.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.mission.config.MissionViewportProperties;
import com.msg.fillmap.mission.dto.CompletedMissionResponseDto;
import com.msg.fillmap.mission.dto.MissionAwardResult;
import com.msg.fillmap.mission.dto.MissionResponseDto;
import com.msg.fillmap.mission.dto.MissionShape.PathShape;
import com.msg.fillmap.mission.dto.MissionShape.Spot;
import com.msg.fillmap.mission.entity.Mission;
import com.msg.fillmap.mission.entity.MissionType;
import com.msg.fillmap.mission.repository.MissionGridRepository;
import com.msg.fillmap.mission.repository.MissionRepository;
import com.msg.fillmap.mission.service.MissionAwardService;
import com.msg.fillmap.mission.service.MissionQueryService;
import com.msg.fillmap.mission.service.impl.MissionQueryServiceImpl;
import com.msg.fillmap.region.service.RegionQueryService;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.repository.VideoRepository;

/**
 * 코스 시드 계약 라운드트립 (MSG-225 모듈 4, 실 PostgreSQL). 이 티켓이 조회(MSG-222)·판정(MSG-223)
 * 경로를 0줄 수정으로 소비한다는 스펙 주장(성공 기준 4·5)을 기존 경로 그대로 실행해 증명한다.
 *
 * 조회는 전역 1h 캐시(MissionQueryServiceImpl)를 피해 새 인스턴스로 재계산한다
 * (MissionQueryServiceImplTest.newService 선례). 격리(공유 로컬 DB): 합성 제목·남반구 합성 격자
 * 인덱스만 쓰고 @Transactional 롤백.
 */
@SpringBootTest
@Transactional
@DisplayName("코스 시드 계약 라운드트립 — MSG-222 PATH shape·MSG-223 무기간 판정 (기존 경로 무수정)")
class CourseSeedContractTest {

	private static final String PATH_JSON = """
		{"type": "LineString", "coordinates": [[129.03597, 35.09656], [129.03642, 35.09721]]}""";
	// 음수 y 합성 격자 블록 — 타 테스트·실데이터와 못 겹친다 (모듈 3과도 다른 대역). EPSG:5179 전환 후에도
	// 값 그대로 둔다: mission_grids 는 grids FK 가 없어 gridId 를 불투명 문자열로만 다루고, 이 대역은
	// 새 체계 한국 범위(y 15000~23000)와도 안 겹쳐 격리 근거가 유지된다 (MSG-347).
	private static final long BASE_Y = -39200L;
	private static final long BASE_X = 112198L;

	@Autowired
	private MissionRepository missionRepository;

	@Autowired
	private MissionGridRepository missionGridRepository;

	@Autowired
	private CourseSeedReader reader;

	@Autowired
	private AwsProperties awsProperties;

	@Autowired
	private MissionAwardService missionAwardService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private VideoRepository videoRepository;

	@Autowired
	private RegionQueryService regionQueryService;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private EntityManager em;

	@TempDir
	private Path tempDir;

	// 검증: FR-MISSION-01, FR-MISSION-09
	@Test
	@DisplayName("시드된 코스가 PATH shape로 path 원문과 스팟마커를 seq순으로 반환한다 — MSG-222 조회 계약")
	void 시드된_코스가_PATH_shape로_path_원문과_스팟마커를_seq순으로_반환한다() throws IOException {
		String title = unique("라운드트립 코스");
		seedCourse("T_RT_1", title, BASE_Y);

		// MSG-398 부터 조회는 뷰포트 필수 — 코스 사각형은 path 좌표(부산 영도) 기준이라 그 일대 뷰포트로 찾는다.
		MissionResponseDto dto = newQueryService()
			.getMissionsInViewport(new ViewportBounds(35.05, 129.00, 35.15, 129.10), MissionType.COURSE).stream()
			.filter(mission -> title.equals(mission.title()))
			.findFirst()
			.orElseThrow();

		assertThat(dto.type()).isEqualTo("COURSE");
		assertThat(dto.targetCount()).isEqualTo(3);
		assertThat(dto.startAt()).isNull();
		assertThat(dto.endAt()).isNull();
		PathShape shape = (PathShape) dto.shape();
		// line = missions.path 원문 passthrough(@JsonRawValue) — jsonb 정규화가 있어 JSON 동등성으로 비교(선례 계승).
		assertThat(objectMapper.readTree(shape.line())).isEqualTo(objectMapper.readTree(PATH_JSON));
		assertThat(shape.spots()).extracting(Spot::seq).containsExactly(1, 2, 3, 4, 5);
		assertThat(shape.spots()).extracting(Spot::gridId)
			.containsExactly(gid(BASE_Y), gid(BASE_Y + 1), gid(BASE_Y + 2), gid(BASE_Y + 3), gid(BASE_Y + 4));
	}

	// 검증: FR-MISSION-03, FR-MISSION-09
	@Test
	@DisplayName("무기간 코스는 과거 recorded_at 영상도 판정에 인정된다 — MSG-223 엔진 무수정")
	void 무기간_코스는_과거_recorded_at_영상도_판정에_인정된다() throws IOException {
		long userId = userRepository.save(
			User.createLocalUser("msg225-" + System.nanoTime() + "@example.com", "hash", "코스판정테스터")).getId();
		String title = unique("과거 영상 코스");
		long baseY = BASE_Y - 100;
		Mission mission = seedCourse("T_RT_2", title, baseY);
		em.flush();

		// 과거 영상 2스팟(기간 밖 recorded_at — 무기간이라 기간 조건이 자연 생략된다, FR-9·12) + 신규 업로드 1스팟.
		seedVideo(userId, seedGrid(baseY), nowUtc().minusYears(2));
		seedVideo(userId, seedGrid(baseY + 1), nowUtc().minusYears(1));
		String uploadGrid = seedGrid(baseY + 2);
		seedVideo(userId, uploadGrid, nowUtc());

		MissionAwardResult result = missionAwardService.awardOnUpload(userId, uploadGrid);

		// target_count=3: 과거 2 + 신규 1 = distinct 3격자 충족 → 스탬프 발급 (성공 기준 5).
		assertThat(result.completedMissions())
			.extracting(CompletedMissionResponseDto::missionId)
			.contains(mission.getId());
		assertThat(stampCount(userId, mission.getId())).isEqualTo(1);
	}

	/** 전역 1h 캐시를 피한 새 조회 인스턴스 — 이 tx 의 시드만 재계산한다 (선례: MissionQueryServiceImplTest). */
	private MissionQueryService newQueryService() {
		return new MissionQueryServiceImpl(missionRepository, missionGridRepository, videoRepository, objectMapper,
			new MissionViewportProperties(Map.of()), regionQueryService, Clock.systemUTC(),
			Duration.ofHours(1).toMillis());
	}

	/** 산출물 파일을 만들어 시더로 적재하고 미션을 돌려준다 — 스팟 5곳 = (baseY..baseY+4, BASE_X), seq 1..5. */
	private Mission seedCourse(String crsIdx, String title, long baseY) throws IOException {
		StringBuilder spots = new StringBuilder();
		for (int seq = 1; seq <= 5; seq++) {
			if (seq > 1) {
				spots.append(",");
			}
			spots.append("""
				{"seq": %d, "gridId": "%d_%d", "name": "스팟%d", "method": "tourapi"}"""
				.formatted(seq, baseY + seq - 1, BASE_X, seq));
		}
		Path file = tempDir.resolve(crsIdx + ".json");
		Files.writeString(file, """
			[{"crsIdx": "%s", "name": "%s", "path": %s, "spots": [%s]}]"""
			.formatted(crsIdx, title, PATH_JSON, spots));

		CourseMissionSeeder seeder = new CourseMissionSeeder(missionRepository, missionGridRepository, reader,
			awsProperties);
		ReflectionTestUtils.setField(seeder, "enabled", true);
		ReflectionTestUtils.setField(seeder, "seedPath", file.toString());
		seeder.seed(file);
		return missionRepository.findBySource(CourseMissionSeeder.SOURCE_DURUNUBI).stream()
			.filter(mission -> mission.getTitle().equals(title))
			.findFirst()
			.orElseThrow();
	}

	private static String gid(long gridY) {
		return gridY + "_" + BASE_X;
	}

	private String unique(String tag) {
		return "MSG225-rt-" + tag + "-" + System.nanoTime();
	}

	private LocalDateTime nowUtc() {
		return LocalDateTime.now(ZoneOffset.UTC);
	}

	/** videos FK 용 grids 시드 — 스팟 격자(논리 식별자)는 FK 가 없지만 영상은 grids row 가 필요하다. */
	private String seedGrid(long gridY) {
		return GridFixtures.seedGrid(em, gridY, BASE_X);
	}

	/** videos.geom NOT NULL — 픽스처에 지오메트리 포함 필수 (MissionAwardQueryTest.seedVideo 선례). */
	private void seedVideo(long ownerId, String gridId, LocalDateTime recordedAt) {
		em.createNativeQuery("""
				INSERT INTO videos (user_id, grid_id, geom, duration_sec, recorded_at, status)
				VALUES (
					:userId, :gridId,
					ST_SetSRID(ST_MakePoint(126.92, 37.56), 4326)::geography,
					10, :recordedAt, 'ACTIVE'
				)
				""")
			.setParameter("userId", ownerId)
			.setParameter("gridId", gridId)
			.setParameter("recordedAt", recordedAt)
			.executeUpdate();
	}

	private long stampCount(long userId, long missionId) {
		return ((Number) em.createNativeQuery(
				"SELECT COUNT(*) FROM user_missions WHERE user_id = :userId AND mission_id = :missionId")
			.setParameter("userId", userId)
			.setParameter("missionId", missionId)
			.getSingleResult()).longValue();
	}
}
