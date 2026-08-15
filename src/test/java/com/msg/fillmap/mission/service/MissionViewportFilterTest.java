package com.msg.fillmap.mission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.mission.config.MissionViewportProperties;
import com.msg.fillmap.mission.dto.MissionResponseDto;
import com.msg.fillmap.mission.entity.Mission;
import com.msg.fillmap.mission.entity.MissionType;
import com.msg.fillmap.mission.repository.MissionGridRepository;
import com.msg.fillmap.mission.repository.MissionRepository;
import com.msg.fillmap.mission.service.impl.MissionQueryServiceImpl;

/**
 * 뷰포트 필터 검증 (MissionQueryServiceImpl, 실 PostgreSQL · MSG-398 D2·D3·D4 · Owner B).
 * 스냅샷에 동봉된 미션 사각형(코스=경로 점별 환산, 그 밖=격자 min/max)과 요청 사각형(꼭짓점 4점 환산
 * + 투영 보정 1칸 + 종류별 마진)의 교차 판정을 본다. shape 합성은 MissionQueryServiceImplTest,
 * bbox 검증의 HTTP 계약은 MissionValidationHttpTest 담당.
 *
 * 격리(공유 로컬 DB): 합성 미션만 만들고 @Transactional 롤백. 시드 미션과 지역이 겹칠 수 있으므로
 * 모든 단언은 이 tx 에서 삽입한 미션 id 로 스코프한다(빈 배열 단언만 서해 원해 뷰포트 사용).
 * 기준 격자 블록은 중앙자오선(경도 127.5) 부근(GX0=9980)이다 — 자오선에서 멀수록 등위도선 곡률로
 * 행 산술이 어긋나므로, 곡률 자체를 검증하는 테스트(남쪽 경계 칸 2종) 밖에서는 곡률을 0 에 가깝게 둔다.
 */
@SpringBootTest
@Transactional
@DisplayName("MissionQueryServiceImpl 뷰포트 필터 (실 PostgreSQL)")
class MissionViewportFilterTest {

	private static final long GY0 = 17700L;
	private static final long GX0 = 9980L;

	/** 중앙자오선 곡률 반례 — 남쪽 변 중간점이 두 귀퉁이보다 20.21m 아래(gridY 14947 vs 14948, 스펙 D3 실측). */
	private static final double 곡률_LAT = 33.444927;
	private static final double 곡률_서쪽_LON = 127.279553;
	private static final double 곡률_중간_LON = 127.441772;
	private static final double 곡률_동쪽_LON = 127.603991;

	@Autowired
	private MissionRepository missionRepository;

	@Autowired
	private MissionGridRepository missionGridRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private EntityManager em;

	private MissionQueryService newService() {
		return newService(Map.of());
	}

	/** 캐시를 비운 새 서비스 인스턴스 — 종류별 마진을 주입해 이 tx 의 미션만 재계산해 조회한다. */
	private MissionQueryService newService(Map<MissionType, Integer> marginMeters) {
		return new MissionQueryServiceImpl(missionRepository, missionGridRepository, objectMapper,
			new MissionViewportProperties(marginMeters), Clock.systemUTC(), Duration.ofHours(1).toMillis());
	}

	/** 셀 중심 기준 뷰포트 — 행 [rowA..rowB], 열 [colA..colB] 를 정확히 덮는다(중심은 경계에서 50m 라 안전). */
	private static ViewportBounds viewport(long rowA, long colA, long rowB, long colB) {
		GridPoint sw = GridFixtures.pointAt(rowA + 0.5, colA + 0.5);
		GridPoint ne = GridFixtures.pointAt(rowB + 0.5, colB + 0.5);
		return new ViewportBounds(sw.lat(), sw.lon(), ne.lat(), ne.lon());
	}

	private List<Long> missionIds(List<MissionResponseDto> missions) {
		return missions.stream().map(MissionResponseDto::missionId).toList();
	}

	private static String gid(long gridY, long gridX) {
		return gridY + "_" + gridX;
	}

	/** 셀 중심들을 잇는 LineString GeoJSON — 코스 path 픽스처. */
	private static String pathThrough(long[][] cells) {
		StringBuilder coordinates = new StringBuilder();
		for (long[] cell : cells) {
			if (coordinates.length() > 0) {
				coordinates.append(",");
			}
			GridPoint point = GridFixtures.pointAt(cell[0] + 0.5, cell[1] + 0.5);
			coordinates.append("[").append(point.lon()).append(",").append(point.lat()).append("]");
		}
		return "{\"type\":\"LineString\",\"coordinates\":[" + coordinates + "]}";
	}

	private long insertMission(String type, String pathJson, LocalDateTime startAt, LocalDateTime endAt) {
		String title = "MSG398-vf-" + System.nanoTime();
		em.createNativeQuery("""
			INSERT INTO missions (type, title, start_at, end_at, target_count, path)
			VALUES (:type, :title, :startAt, :endAt, 1, CAST(:path AS jsonb))
			""")
			.setParameter("type", type)
			.setParameter("title", title)
			.setParameter("startAt", startAt)
			.setParameter("endAt", endAt)
			.setParameter("path", pathJson)
			.executeUpdate();
		return ((Number) em.createNativeQuery("SELECT id FROM missions WHERE title = :title")
			.setParameter("title", title)
			.getSingleResult()).longValue();
	}

	private long insertMission(String type, String pathJson) {
		return insertMission(type, pathJson, null, null);
	}

	private void insertMissionGrid(long missionId, String gridId, Integer seq) {
		em.createNativeQuery("""
			INSERT INTO mission_grids (mission_id, grid_id, seq)
			VALUES (:missionId, :gridId, CAST(:seq AS integer))
			""")
			.setParameter("missionId", missionId)
			.setParameter("gridId", gridId)
			.setParameter("seq", seq)
			.executeUpdate();
	}

	// 검증: FR-MISSION-13
	@Test
	@DisplayName("뷰포트 밖 미션은 목록에 없다")
	void 뷰포트_밖_미션은_목록에_없다() {
		long mission = insertMission("EVENT", null);
		insertMissionGrid(mission, gid(GY0, GX0), null);

		List<MissionResponseDto> result =
			newService().getMissionsInViewport(viewport(GY0 + 50, GX0, GY0 + 55, GX0 + 5), MissionType.EVENT);

		assertThat(missionIds(result)).doesNotContain(mission);
	}

	// 검증: FR-MISSION-13
	@Test
	@DisplayName("뷰포트에 일부만 걸친 미션은 목록에 들어온다")
	void 뷰포트에_일부만_걸친_미션은_목록에_들어온다() {
		long mission = insertMission("EVENT", null);
		for (long dy = 0; dy <= 6; dy++) {
			insertMissionGrid(mission, gid(GY0 + dy, GX0), null);
		}

		// 미션 사각형(행 GY0..GY0+6)의 북쪽 일부만 화면에 걸친다.
		List<MissionResponseDto> result =
			newService().getMissionsInViewport(viewport(GY0 + 4, GX0 - 2, GY0 + 10, GX0 + 2), MissionType.EVENT);

		assertThat(missionIds(result)).contains(mission);
	}

	// 검증: FR-MISSION-13
	@Test
	@DisplayName("경로 중간만 화면에 걸친 코스가 사라지지 않는다 — 양 끝이 화면 밖인 긴 코스")
	void 경로_중간만_화면에_걸친_코스가_사라지지_않는다() {
		long course = insertMission("COURSE",
			pathThrough(new long[][] {{GY0 - 30, GX0}, {GY0, GX0}, {GY0 + 30, GX0}}));
		insertMissionGrid(course, gid(GY0 - 30, GX0), 1);
		insertMissionGrid(course, gid(GY0 + 30, GX0), 2);

		// 중심점 포함 판정이었다면 이 코스의 중심 행도 화면 안이지만, 스팟·양끝은 전부 화면 밖이다.
		List<MissionResponseDto> result =
			newService().getMissionsInViewport(viewport(GY0 - 2, GX0 - 2, GY0 + 2, GX0 + 2), MissionType.COURSE);

		assertThat(missionIds(result)).contains(course);
	}

	// 검증: FR-MISSION-13
	@Test
	@DisplayName("코스 사각형은 스팟이 아니라 경로 전체를 감싼다 — 스팟 밖으로 뻗은 경로 끝만 보이는 뷰포트")
	void 코스_사각형은_스팟이_아니라_경로_전체를_감싼다() {
		long course = insertMission("COURSE",
			pathThrough(new long[][] {{GY0, GX0}, {GY0 + 25, GX0}, {GY0 + 50, GX0}}));
		// 스팟은 경로 남쪽 끝에만 — 스팟 사각형(행 GY0..GY0+1)으로 판정하면 경로 북쪽 끝 뷰포트에서 빠진다.
		insertMissionGrid(course, gid(GY0, GX0), 1);
		insertMissionGrid(course, gid(GY0 + 1, GX0), 2);

		List<MissionResponseDto> result =
			newService().getMissionsInViewport(viewport(GY0 + 45, GX0 - 2, GY0 + 49, GX0 + 2), MissionType.COURSE);

		assertThat(missionIds(result)).contains(course);
	}

	// 검증: FR-MISSION-13
	@Test
	@DisplayName("경로 점이 셀 경계에 걸쳐도 그 칸이 사각형에 들어온다 — 귀퉁이 근사 회귀 방지")
	void 경로_점이_셀_경계에_걸쳐도_그_칸이_사각형에_들어온다() {
		// 등위도 세 점 — 중앙자오선(127.5)에 가까운 중간점의 5179 y 가 양끝보다 낮아 한 행 아래 셀에 든다.
		// 위경도 사각형을 먼저 만들고 네 귀퉁이만 환산하는 구현으로 되돌리면(D3 기각안) 중간점의 행이
		// 사각형에서 빠져 이 테스트가 깨진다.
		GridIndex west = GridEncoder.decode(GridEncoder.encode(곡률_LAT, 곡률_서쪽_LON));
		GridIndex middle = GridEncoder.decode(GridEncoder.encode(곡률_LAT, 곡률_중간_LON));
		GridIndex east = GridEncoder.decode(GridEncoder.encode(곡률_LAT, 곡률_동쪽_LON));
		assertThat(Math.min(west.gridY(), east.gridY()))
			.as("전제: 중간점이 양끝보다 남쪽 행이어야 곡률 반례가 성립한다 (스펙 D3 실측)")
			.isGreaterThan(middle.gridY());

		long course = insertMission("COURSE", """
			{"type":"LineString","coordinates":[[%s,%s],[%s,%s],[%s,%s]]}"""
			.formatted(곡률_서쪽_LON, 곡률_LAT, 곡률_중간_LON, 곡률_LAT, 곡률_동쪽_LON, 곡률_LAT));

		// 중간점 행(middle.gridY())의 바로 남쪽 행까지만 보이는 뷰포트 — 투영 보정 1칸으로 중간점 행에 닿는다.
		// 귀퉁이 기반 사각형이면 미션 최소 행이 west.gridY() 라 보정 1칸으로도 닿지 않는다.
		ViewportBounds bounds =
			viewport(middle.gridY() - 3, middle.gridX() - 3, middle.gridY() - 1, middle.gridX());

		List<MissionResponseDto> result = newService().getMissionsInViewport(bounds, MissionType.COURSE);

		assertThat(missionIds(result)).contains(course);
	}

	// 검증: FR-MISSION-13
	@Test
	@DisplayName("중앙자오선을 품는 뷰포트의 남쪽 경계 칸이 누락되지 않는다 — 사방 1칸 확장을 지우면 깨진다")
	void 중앙자오선을_품는_뷰포트의_남쪽_경계_칸이_누락되지_않는다() {
		// 스펙 D3 의 실측 bbox 그대로 — 남쪽 변 중간점의 행(14947)이 네 귀퉁이 환산 범위(14948~)에서 빠진다.
		ViewportBounds bounds = new ViewportBounds(곡률_LAT, 곡률_서쪽_LON, 33.878816, 곡률_동쪽_LON);
		String southEdgeCell = GridEncoder.encode(곡률_LAT, 곡률_중간_LON);
		GridIndex southEdge = GridEncoder.decode(southEdgeCell);
		assertThat(GridEncoder.viewportRange(bounds).minGridY())
			.as("전제: 네 귀퉁이 환산만으로는 남쪽 변 중간점의 행이 범위 밖이어야 한다 (스펙 D3 실측)")
			.isGreaterThan(southEdge.gridY());

		long mission = insertMission("EVENT", null);
		insertMissionGrid(mission, southEdgeCell, null);

		List<MissionResponseDto> result = newService().getMissionsInViewport(bounds, MissionType.EVENT);

		assertThat(missionIds(result)).contains(mission);
	}

	// 검증: FR-MISSION-13
	@Test
	@DisplayName("극단 좌표를 가진 코스가 스냅숏 재계산을 막지 않는다 — 좌표 변환 앞 범위 검증을 지우면 멈춘다")
	void 극단_좌표를_가진_코스가_스냅숏_재계산을_막지_않는다() {
		// 1e308 은 유한값이라 isFinite 검사를 통과하고, Proj4J 경도 정규화(반복 감산)를 사실상 무한 루프로
		// 만든다 — 시간 상한으로 그 멈춤을 실패로 드러낸다(D3). assertTimeoutPreemptively 는 별도 스레드라
		// 이 클래스의 tx 미커밋 데이터를 못 본다 — 이 케이스만 리포지토리를 목으로 갈아 DB 없이 재계산한다.
		Mission broken = Mission.builder().type(MissionType.COURSE).title("깨진 코스").targetCount(1)
			.path("{\"type\":\"LineString\",\"coordinates\":[[1.0E308,35.1],[127.0,36.0]]}").build();
		ReflectionTestUtils.setField(broken, "id", 901L);
		Mission healthy = Mission.builder().type(MissionType.COURSE).title("정상 코스").targetCount(1)
			.path(pathThrough(new long[][] {{GY0, GX0}, {GY0 + 1, GX0 + 1}})).build();
		ReflectionTestUtils.setField(healthy, "id", 902L);
		MissionRepository mockMissions = mock(MissionRepository.class);
		MissionGridRepository mockGrids = mock(MissionGridRepository.class);
		given(mockMissions.findActive(any())).willReturn(List.of(broken, healthy));
		given(mockGrids.findByMissionIds(any())).willReturn(List.of());
		MissionQueryService service = new MissionQueryServiceImpl(mockMissions, mockGrids, objectMapper,
			new MissionViewportProperties(Map.of()), Clock.systemUTC(), Duration.ofHours(1).toMillis());

		List<MissionResponseDto> result = assertTimeoutPreemptively(Duration.ofSeconds(10),
			() -> service.getMissionsInViewport(viewport(GY0 - 2, GX0 - 2, GY0 + 3, GX0 + 3),
				MissionType.COURSE));

		assertThat(missionIds(result)).contains(902L);
		// 깨진 코스는 격자도 없어 폴백 사각형조차 없다 — 조용히 어디에도 실리지 않는다.
		assertThat(missionIds(result)).doesNotContain(901L);
	}

	@Test
	@DisplayName("범위 밖 좌표를 가진 코스는 스팟 사각형으로 판정한다 — 기존 폴백 자리로 합류")
	void 범위_밖_좌표를_가진_코스는_스팟_사각형으로_판정한다() {
		long course = insertMission("COURSE", """
			{"type":"LineString","coordinates":[[125.0,100.0],[127.0,36.0]]}""");
		insertMissionGrid(course, gid(GY0, GX0), 1);
		insertMissionGrid(course, gid(GY0 + 1, GX0), 2);

		List<MissionResponseDto> result =
			newService().getMissionsInViewport(viewport(GY0 - 2, GX0 - 2, GY0 + 3, GX0 + 3), MissionType.COURSE);

		assertThat(missionIds(result)).contains(course);
	}

	@Test
	@DisplayName("path가 없는 코스는 스팟 사각형으로 판정한다")
	void path가_없는_코스는_스팟_사각형으로_판정한다() {
		long course = insertMission("COURSE", null);
		insertMissionGrid(course, gid(GY0, GX0), 1);
		insertMissionGrid(course, gid(GY0 + 1, GX0), 2);

		List<MissionResponseDto> result =
			newService().getMissionsInViewport(viewport(GY0 - 2, GX0 - 2, GY0 + 3, GX0 + 3), MissionType.COURSE);

		assertThat(missionIds(result)).contains(course);
	}

	@Test
	@DisplayName("격자도 경로도 없는 미션은 어떤 뷰포트에도 없다 — 위치를 알 수 없다")
	void 격자도_경로도_없는_미션은_어떤_뷰포트에도_없다() {
		long mission = insertMission("EVENT", null);

		List<MissionResponseDto> result =
			newService().getMissionsInViewport(viewport(GY0 - 2, GX0 - 2, GY0 + 3, GX0 + 3), MissionType.EVENT);

		assertThat(missionIds(result)).doesNotContain(mission);
	}

	// 검증: FR-MISSION-02
	@Test
	@DisplayName("보이는 범위에 미션이 없으면 빈 배열이다")
	void 보이는_범위에_미션이_없으면_빈_배열이다() {
		// 서해 원해 — 시드·합성 미션이 없는 지역이라 종류와 무관하게 빈 배열이다(실패가 아니다).
		ViewportBounds ocean = new ViewportBounds(33.01, 124.01, 33.05, 124.05);

		assertThat(newService().getMissionsInViewport(ocean, MissionType.POPUP)).isEmpty();
	}

	@Test
	@DisplayName("한국 밖 뷰포트는 오류가 아니라 빈 배열이다 — 조회 창에 서비스 범위를 걸지 않는다 (D6)")
	void 한국_밖_뷰포트는_오류가_아니라_빈_배열이다() {
		ViewportBounds tokyo = new ViewportBounds(35.65, 139.70, 35.70, 139.75);

		assertThat(newService().getMissionsInViewport(tokyo, MissionType.EVENT)).isEmpty();
	}

	// 검증: FR-MISSION-13
	@Test
	@DisplayName("고른 종류의 미션만 나온다 — 같은 뷰포트에 세 종류가 있을 때")
	void 고른_종류의_미션만_나온다() {
		long event = insertMission("EVENT", null);
		insertMissionGrid(event, gid(GY0, GX0), null);
		long popup = insertMission("POPUP", null);
		insertMissionGrid(popup, gid(GY0, GX0 + 1), null);
		long course = insertMission("COURSE", pathThrough(new long[][] {{GY0, GX0}, {GY0 + 1, GX0 + 1}}));
		insertMissionGrid(course, gid(GY0, GX0), 1);

		List<MissionResponseDto> result =
			newService().getMissionsInViewport(viewport(GY0 - 2, GX0 - 2, GY0 + 3, GX0 + 3), MissionType.EVENT);

		assertThat(missionIds(result)).contains(event);
		assertThat(missionIds(result)).doesNotContain(popup, course);
	}

	// 검증: FR-MISSION-13
	@Test
	@DisplayName("종류별 마진이 각각 적용된다 — 설정값이 다른 두 종류에서 경계 밖 포함 여부가 갈린다")
	void 종류별_마진이_각각_적용된다() {
		long event = insertMission("EVENT", null);
		insertMissionGrid(event, gid(GY0, GX0), null);
		long popup = insertMission("POPUP", null);
		insertMissionGrid(popup, gid(GY0, GX0), null);

		// 뷰포트 북단이 미션에서 4행 남쪽 — EVENT 는 보정 1 + 마진 3칸(300m)으로 닿고 POPUP(마진 0)은 못 닿는다.
		MissionQueryService service = newService(Map.of(MissionType.EVENT, 300, MissionType.POPUP, 0));
		ViewportBounds bounds = viewport(GY0 - 8, GX0 - 2, GY0 - 4, GX0 + 2);

		assertThat(missionIds(service.getMissionsInViewport(bounds, MissionType.EVENT))).contains(event);
		assertThat(missionIds(service.getMissionsInViewport(bounds, MissionType.POPUP))).doesNotContain(popup);
	}

	@Test
	@DisplayName("마진이 0이어도 투영 보정 1칸은 적용된다 — 보정과 마진은 다른 값이다 (D3·D4)")
	void 마진이_0이어도_투영_보정_1칸은_적용된다() {
		long mission = insertMission("EVENT", null);
		insertMissionGrid(mission, gid(GY0, GX0), null);
		MissionQueryService service = newService(Map.of());

		// 뷰포트 북단이 바로 아래 행 — 마진 0 이어도 보정 1칸으로 들어온다.
		assertThat(missionIds(service.getMissionsInViewport(
			viewport(GY0 - 5, GX0 - 2, GY0 - 1, GX0 + 2), MissionType.EVENT))).contains(mission);
		// 두 행 아래부터는 보정 1칸 밖 — 마진이 0 이므로 빠진다.
		assertThat(missionIds(service.getMissionsInViewport(
			viewport(GY0 - 7, GX0 - 2, GY0 - 3, GX0 + 2), MissionType.EVENT))).doesNotContain(mission);
	}

	// 검증: FR-MISSION-02
	@Test
	@DisplayName("기간이 끝난 미션은 목록에 없다 — 활성 판정은 기간 양끝을 독립적으로 본다")
	void 기간이_끝난_미션은_목록에_없다() {
		LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
		long ended = insertMission("EVENT", null, nowUtc.minusDays(2), nowUtc.minusDays(1));
		insertMissionGrid(ended, gid(GY0, GX0), null);
		long active = insertMission("EVENT", null, nowUtc.minusDays(1), nowUtc.plusDays(1));
		insertMissionGrid(active, gid(GY0, GX0 + 1), null);

		List<MissionResponseDto> result =
			newService().getMissionsInViewport(viewport(GY0 - 2, GX0 - 2, GY0 + 3, GX0 + 3), MissionType.EVENT);

		// 활성 형제가 나오는 같은 뷰포트에서 종료 미션만 빠진다 — 뷰포트 탓이 아니라 활성 판정이다.
		assertThat(missionIds(result)).contains(active);
		assertThat(missionIds(result)).doesNotContain(ended);
	}

	// 검증: FR-MISSION-14
	@Test
	@DisplayName("목록 항목에 카드가 쓰는 메타데이터가 실려 온다 — 목록 DTO 축소 회귀 방지")
	void 목록_항목에_카드가_쓰는_메타데이터가_실려_온다() {
		LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
		long mission = insertMission("EVENT", null, nowUtc.minusDays(1), nowUtc.plusDays(1));
		insertMissionGrid(mission, gid(GY0, GX0), null);
		em.createNativeQuery("""
			UPDATE missions SET description = '광안리 앞바다 불꽃 축제', place_name = '부산 수영구',
				source_url = 'https://festival.example.kr', image_url = 'https://cdn.fillmap.kr/missions/1.jpg'
			WHERE id = :id
			""")
			.setParameter("id", mission)
			.executeUpdate();

		MissionResponseDto dto = newService()
			.getMissionsInViewport(viewport(GY0 - 2, GX0 - 2, GY0 + 3, GX0 + 3), MissionType.EVENT).stream()
			.filter(item -> item.missionId() == mission)
			.findFirst()
			.orElseThrow();

		// 카드가 그리는 값: 제목·기간·장소·소개문·대표 이미지 (PRD FR-2 / SRS FR-MISSION-14).
		assertThat(dto.title()).isNotBlank();
		assertThat(dto.startAt()).isNotNull();
		assertThat(dto.endAt()).isNotNull();
		assertThat(dto.placeName()).isEqualTo("부산 수영구");
		assertThat(dto.description()).isEqualTo("광안리 앞바다 불꽃 축제");
		assertThat(dto.imageUrl()).isEqualTo("https://cdn.fillmap.kr/missions/1.jpg");
	}

	@Test
	@DisplayName("목록 순서는 미션 id 오름차순으로 고정된다 — 결정성 확보 (D12)")
	void 목록_순서는_미션_id_오름차순으로_고정된다() {
		long first = insertMission("EVENT", null);
		insertMissionGrid(first, gid(GY0, GX0), null);
		long second = insertMission("EVENT", null);
		insertMissionGrid(second, gid(GY0 + 1, GX0), null);
		long third = insertMission("EVENT", null);
		insertMissionGrid(third, gid(GY0 + 2, GX0), null);

		List<Long> mine = newService()
			.getMissionsInViewport(viewport(GY0 - 2, GX0 - 2, GY0 + 3, GX0 + 3), MissionType.EVENT).stream()
			.map(MissionResponseDto::missionId)
			.filter(id -> id == first || id == second || id == third)
			.toList();

		assertThat(mine).containsExactly(first, second, third);
	}
}
