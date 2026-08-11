package com.msg.fillmap.grid.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
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
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.grid.exception.GridErrorCode;
import com.msg.fillmap.grid.repository.GridRepository;
import com.msg.fillmap.region.RegionTestFixtures;
import com.msg.fillmap.region.repository.RegionRepository;
import com.msg.fillmap.region.service.RegionQueryService;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.zone.entity.Zone;
import com.msg.fillmap.zone.repository.ZoneRepository;
import com.msg.fillmap.zone.service.ZoneNameQueryService;

@SpringBootTest
@Transactional
@DisplayName("GridQueryService 통합 (실 PostGIS)")
class GridQueryServiceIntegrationTest {

	/**
	 * 서해 공해상 기준점 — 실 행정동이 덮지 않는 좌표라 이 테스트가 심는 합성 행정동만 판정에 잡힌다.
	 * regions 시딩은 기본 off(CI 는 regions 가 빈 상태)라 실데이터에 기대면 환경마다 답이 갈린다 (MSG-349).
	 */
	private static final double 공해상_LAT = 36.5;
	private static final double 공해상_LON = 124.5;

	/**
	 * 표시명 검증용 합성 구역 (MSG-341). 테스트 블록을 덮는 3행×3열 사각형이고 priority 100 이라
	 * 같은 좌표를 덮는 실 시드 구역(priority 0)을 타이브레이크로 이긴다 — 시딩 상태와 무관하게 기대값이 고정된다.
	 */
	private static final String ZONE_NAME = "m341공해상합성";

	/**
	 * 행정동 이름 검증용 합성 행정동 (MSG-349). 블록 [base, base+5) 을 덮어 구역 사각형(3×3)보다 넓다 —
	 * "구역 밖인데 행정동 안" 격자를 만들 수 있다. 실존하지 않는 sido 999 대역이라 실데이터와 충돌하지 않는다.
	 */
	private static final String REGION_CODE = "9996000002";
	private static final String REGION_NAME = "합성시 합성구 합성349동";

	@Autowired
	private GridQueryService gridQueryService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ZoneRepository zoneRepository;

	@Autowired
	private RegionRepository regionRepository;

	@MockitoSpyBean
	private ZoneNameQueryService zoneNameQueryService;

	@MockitoSpyBean
	private GridRepository gridRepository;

	@MockitoSpyBean
	private RegionQueryService regionQueryService;

	@Autowired
	private EntityManager em;

	private long me;
	private long baseY;
	private long baseX;
	private String occupiedGridId;
	private String unoccupiedGridId;

	@BeforeEach
	void setUp() {
		me = userRepository.save(User.createLocalUser("grid-svc@example.com", "hash", "나")).getId();

		GridIndex base = GridEncoder.decode(GridEncoder.encode(공해상_LAT, 공해상_LON));
		baseY = base.gridY();
		baseX = base.gridX();

		// 격자보다 먼저 심어야 seedLabeledGrid 의 중심점 판정이 라벨을 건다 (프로덕션 upsertGrid 와 같은 규칙).
		regionRepository.upsert(REGION_CODE, REGION_NAME, REGION_CODE.substring(0, 5),
			RegionTestFixtures.cellBlockPolygonJson(baseY, baseY + 5, baseX, baseX + 5),
			RegionTestFixtures.CELL_AREA_M2);

		occupiedGridId = GridFixtures.seedLabeledGrid(em, baseY, baseX);
		unoccupiedGridId = GridFixtures.seedGrid(em, baseY + 1, baseX + 1);
		GridFixtures.seedUserGrid(em, me, occupiedGridId, 3);
		em.flush();

		zoneRepository.saveAndFlush(Zone.builder()
			.zoneKey("m341-grid-svc")
			.name(ZONE_NAME)
			.minGridY((int) baseY)
			.maxGridY((int) baseY + 2)
			.minGridX((int) baseX)
			.maxGridX((int) baseX + 2)
			.priority(100)
			.build());
	}

	private ViewportBounds blockBounds() {
		return bounds(2, 2);
	}

	/** (base, base) 셀 중심 ~ (base + dyTo, base + dxTo) 셀 중심을 감싸는 bbox. */
	private ViewportBounds bounds(long dyTo, long dxTo) {
		GridPoint southWest = GridFixtures.pointAt(baseY + 0.5, baseX + 0.5);
		GridPoint northEast = GridFixtures.pointAt(baseY + dyTo + 0.5, baseX + dxTo + 0.5);
		return new ViewportBounds(southWest.lat(), southWest.lon(), northEast.lat(), northEast.lon());
	}

	/**
	 * 블록 안 (baseY + dy, baseX + dx) 셀을 내 점령으로 시드한다 — 페이지 시나리오용 볼륨.
	 * 합성 행정동 라벨까지 붙여 프로덕션 격자(탄생 시 라벨)와 같은 상태로 만든다.
	 */
	private String seedOccupied(long dy, long dx) {
		String gridId = GridFixtures.seedLabeledGrid(em, baseY + dy, baseX + dx);
		GridFixtures.seedUserGrid(em, me, gridId, 1);
		return gridId;
	}

	private static String base64Url(String raw) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	private static List<String> gridIds(OccupiedGridPage page) {
		return page.items().stream().map(OccupiedGridView::gridId).toList();
	}

	// 검증: FR-GRID-06
	@Test
	@DisplayName("미점령 격자 조회는 occupied 가 거짓이고 videoCount 가 0 이다")
	void 미점령_격자_조회는_occupied가_거짓이고_videoCount가_0이다() {
		GridCellView view = gridQueryService.getCell(me, unoccupiedGridId);

		assertThat(view.gridId()).isEqualTo(unoccupiedGridId);
		assertThat(view.occupied()).isFalse();
		assertThat(view.videoCount()).isZero();
	}

	// 검증: FR-GRID-06
	@Test
	@DisplayName("점령 격자 조회는 occupied 가 참이고 videoCount 를 반환한다")
	void 점령_격자_조회는_occupied가_참이고_videoCount를_반환한다() {
		GridCellView view = gridQueryService.getCell(me, occupiedGridId);

		assertThat(view.occupied()).isTrue();
		assertThat(view.videoCount()).isEqualTo(3);
	}

	// 검증: FR-GRID-07
	@Test
	@DisplayName("뷰포트 조회는 내가 점령한 격자 목록을 최소 필드로 반환한다")
	void 뷰포트_조회는_내가_점령한_격자_목록을_최소필드로_반환한다() {
		List<OccupiedGridView> result = gridQueryService.getOccupiedInViewport(me, blockBounds());

		assertThat(result).hasSize(1);
		OccupiedGridView view = result.get(0);
		assertThat(view.gridId()).isEqualTo(occupiedGridId);
		assertThat(view.gridY()).isEqualTo((int) baseY);
		assertThat(view.gridX()).isEqualTo((int) baseX);
	}

	// 검증: FR-ZONE-03, FR-ZONE-05
	@Test
	@DisplayName("구역 안 단일 격자 조회는 구역 이름과 위치 코드를 함께 담는다 (MSG-341 FR-1)")
	void 단일_격자_조회는_구역_안이면_zoneName과_zoneCell을_담는다() {
		GridCellView view = gridQueryService.getCell(me, occupiedGridId);

		// 사각형 북단이 A 라 baseY 는 3행 중 남단 C, 서단이 1 열이라 baseX 는 1
		assertThat(view.zoneName()).isEqualTo(ZONE_NAME);
		assertThat(view.zoneCell()).isEqualTo("C-1");
	}

	// 검증: FR-ZONE-06
	@Test
	@DisplayName("미점령 격자도 구역 안이면 이름이 계산된다 (격자는 논리 개념 — grids row·점령 무관, FR-4)")
	void 미점령_격자도_구역_안이면_이름이_계산된다() {
		GridCellView view = gridQueryService.getCell(me, unoccupiedGridId);

		assertThat(view.occupied()).isFalse();
		assertThat(view.zoneName()).isEqualTo(ZONE_NAME);
		assertThat(view.zoneCell()).isEqualTo("B-2");
	}

	// 검증: FR-ZONE-04
	@Test
	@DisplayName("구역 밖 격자는 zoneName·zoneCell 이 모두 null 이다 (폴백 조립은 클라이언트 몫, FR-3)")
	void 구역_밖_격자는_두_필드가_모두_null이다() {
		// 시드 구역 전체가 위도 37180~42305 대역이라 9999 행은 어느 사각형에도 들지 않는다
		GridCellView view = gridQueryService.getCell(me, "9999_9999");

		assertThat(view.zoneName()).isNull();
		assertThat(view.zoneCell()).isNull();
	}

	// 검증: FR-REGION-09
	@Test
	@DisplayName("미점령 격자 단일 조회도 regionName 을 준다 (MSG-349 FR-3)")
	void 미점령_격자_단일_조회도_regionName을_준다() {
		// 지도에서 빈 칸을 누르는 경우 — grids 저장 라벨이 없어도 중심점 재판정으로 이름이 나온다.
		GridCellView view = gridQueryService.getCell(me, unoccupiedGridId);

		assertThat(view.occupied()).isFalse();
		assertThat(view.regionName()).isEqualTo(REGION_NAME);
	}

	// 검증: FR-REGION-11
	@Test
	@DisplayName("점령 격자 단일 조회의 regionName 이 저장 라벨과 같다 (MSG-349 FR-6 술어 동일성)")
	void 점령_격자_단일_조회의_regionName이_저장_라벨과_같다() {
		// 단일 조회는 중심점 재판정, 뷰포트는 grids 저장 라벨 — 술어가 같아 같은 격자의 이름이 갈리지 않는다.
		String fromCenter = gridQueryService.getCell(me, occupiedGridId).regionName();
		String fromStoredLabel = gridQueryService.getOccupiedInViewport(me, blockBounds()).get(0).regionName();

		assertThat(fromCenter).isEqualTo(REGION_NAME).isEqualTo(fromStoredLabel);
	}

	// 검증: FR-REGION-10
	@Test
	@DisplayName("무귀속 격자 단일 조회는 regionName 이 null 이다 (MSG-349 FR-5)")
	void 무귀속_격자_단일_조회는_regionName이_null이다() {
		// 합성 행정동 블록에서 멀리 떨어진 서해 공해상 셀 — 어느 행정동도 덮지 않는다.
		String openSeaGridId = GridEncoder.encode(35.0, 125.0);

		GridCellView view = gridQueryService.getCell(me, openSeaGridId);

		assertThat(view.regionName()).isNull();
	}

	// 검증: FR-REGION-10
	@Test
	@DisplayName("서비스 범위 밖 격자 단일 조회는 에러 없이 regionName 이 null 이다 (MSG-349 FR-8 · 6400 흡수)")
	void 서비스_범위_밖_격자_단일_조회는_에러_없이_regionName_null이다() {
		// "9999_9999" 의 중심은 위도 약 29도라 서비스 범위(33~39) 밖이다. 역지오코딩을 그대로 부르면
		// INVALID_COORDINATE(6400)가 나가 지금까지 200 이던 응답이 깨진다 — 가드가 흡수해야 한다.
		GridCellView view = gridQueryService.getCell(me, "9999_9999");

		assertThat(view.regionName()).isNull();
		assertThat(view.occupied()).isFalse();
		verifyNoInteractions(regionQueryService);
	}

	// 검증: FR-ZONE-05, FR-ZONE-09
	@Test
	@DisplayName("뷰포트 페이지 항목마다 구역 이름이 붙고 zones 로드는 요청당 1회다 (N+1 금지, FR-8)")
	void 뷰포트_페이지_항목마다_구역_이름이_붙고_zones_조회는_1회다() {
		String b = seedOccupied(0, 1);

		OccupiedGridPage page = gridQueryService.getOccupiedInViewport(me, blockBounds(), null, 2);

		assertThat(gridIds(page)).containsExactly(occupiedGridId, b);
		assertThat(page.items()).extracting(OccupiedGridView::zoneName).containsOnly(ZONE_NAME);
		assertThat(page.items()).extracting(OccupiedGridView::zoneCell).containsExactly("C-1", "C-2");
		// 항목 수와 무관하게 리졸버(=zones 로드)는 매핑 진입 전 1회뿐이다
		verify(zoneNameQueryService, times(1)).resolver();
	}

	// 검증: FR-ZONE-05, FR-REGION-08
	@Test
	@DisplayName("구역 안 격자는 zoneName 과 regionName 이 함께 실린다 (MSG-349 FR-9)")
	void 구역_안_격자는_zoneName과_regionName이_함께_실린다() {
		// 위치줄("부산 부산진구 서면")의 시·구 출처가 행정동이라 구역 안에서도 regionName 을 뺄 수 없다.
		List<OccupiedGridView> result = gridQueryService.getOccupiedInViewport(me, blockBounds());

		assertThat(result).hasSize(1);
		assertThat(result.get(0).zoneName()).isEqualTo(ZONE_NAME);
		assertThat(result.get(0).zoneCell()).isEqualTo("C-1");
		assertThat(result.get(0).regionName()).isEqualTo(REGION_NAME);
	}

	// 검증: FR-ZONE-04, FR-REGION-08
	@Test
	@DisplayName("구역 밖 격자는 zoneName 없이 regionName 만 실린다 (MSG-349 FR-1)")
	void 구역_밖_격자는_zoneName_없이_regionName만_실린다() {
		// 합성 구역은 3행(base..base+2)까지고 합성 행정동은 5행(base..base+4)까지라 (3, 0) 은 구역 밖·행정동 안이다.
		String outsideZone = seedOccupied(3, 0);

		OccupiedGridPage page = gridQueryService.getOccupiedInViewport(me, bounds(3, 3), null, 10);

		OccupiedGridView view = page.items().stream()
			.filter(item -> item.gridId().equals(outsideZone))
			.findFirst()
			.orElseThrow();
		assertThat(view.zoneName()).isNull();
		assertThat(view.zoneCell()).isNull();
		assertThat(view.regionName()).isEqualTo(REGION_NAME);
	}

	// 검증: FR-REGION-08, FR-REGION-12
	@Test
	@DisplayName("뷰포트 조회는 행정동 이름을 추가 쿼리 없이 같은 쿼리로 읽는다 (MSG-349 비기능 성능)")
	void 뷰포트_조회는_행정동_이름을_추가_쿼리_없이_같은_쿼리로_읽는다() {
		seedOccupied(0, 1);
		seedOccupied(1, 0);

		OccupiedGridPage page = gridQueryService.getOccupiedInViewport(me, blockBounds(), null, 10);

		assertThat(page.items()).extracting(OccupiedGridView::regionName).containsOnly(REGION_NAME);
		// 항목 3개에 페이지 쿼리 1회뿐 — 항목당 단건 조회(N+1)도, 역지오코딩 왕복도 없다.
		verify(gridRepository, times(1))
			.findOccupiedPage(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyInt());
		verifyNoMoreInteractions(gridRepository);
		verifyNoInteractions(regionQueryService);
	}

	// 검증: FR-GRID-08, FR-REGION-10
	@Test
	@DisplayName("커서 페이지네이션 순서는 조인 후에도 불변이다 (MSG-349 회귀)")
	void 커서_페이지네이션_순서는_조인_후에도_불변이다() {
		// 라벨 없는 격자(region_code NULL)를 섞어도 LEFT JOIN 이라 행이 빠지지 않고 (grid_y, grid_x) 순서도 그대로다.
		String labeled = seedOccupied(0, 1);
		String unlabeled = GridFixtures.seedGrid(em, baseY + 1, baseX);
		GridFixtures.seedUserGrid(em, me, unlabeled, 1);

		OccupiedGridPage page1 = gridQueryService.getOccupiedInViewport(me, blockBounds(), null, 2);
		OccupiedGridPage page2 = gridQueryService.getOccupiedInViewport(me, blockBounds(), page1.nextCursor(), 2);

		assertThat(gridIds(page1)).containsExactly(occupiedGridId, labeled);
		assertThat(gridIds(page2)).containsExactly(unlabeled);
		assertThat(page2.items().get(0).regionName()).isNull();
	}

	// 검증: FR-GRID-08
	@Test
	@DisplayName("첫 페이지 nextCursor 를 다음 요청에 넣으면 다음 페이지가 이어진다 (keyset 왕복)")
	void 첫페이지_nextCursor를_다음요청에_넣으면_다음페이지가_이어진다() {
		// 점령 4개, (grid_y, grid_x) 정렬: occupied(0,0) → b(0,1) → c(0,2) → d(1,0)
		String b = seedOccupied(0, 1);
		String c = seedOccupied(0, 2);
		String d = seedOccupied(1, 0);

		OccupiedGridPage page1 = gridQueryService.getOccupiedInViewport(me, blockBounds(), null, 2);
		assertThat(gridIds(page1)).containsExactly(occupiedGridId, b);
		assertThat(page1.nextCursor()).isNotNull();

		OccupiedGridPage page2 = gridQueryService.getOccupiedInViewport(me, blockBounds(), page1.nextCursor(), 2);
		assertThat(gridIds(page2)).containsExactly(c, d);
	}

	// 검증: FR-GRID-08
	@Test
	@DisplayName("마지막 페이지의 nextCursor 는 null 이다")
	void 마지막페이지의_nextCursor는_null이다() {
		// 점령 3개, size 2 → 두 번째 페이지(항목 1개)가 마지막이다.
		seedOccupied(0, 1);
		String last = seedOccupied(1, 0);

		OccupiedGridPage page1 = gridQueryService.getOccupiedInViewport(me, blockBounds(), null, 2);
		OccupiedGridPage page2 = gridQueryService.getOccupiedInViewport(me, blockBounds(), page1.nextCursor(), 2);

		assertThat(gridIds(page2)).containsExactly(last);
		assertThat(page2.nextCursor()).isNull();
	}

	// 검증: FR-GRID-08
	@Test
	@DisplayName("정확히 size 로 나눠떨어져도 빈 마지막 페이지 없이 nextCursor 가 null 이 된다 (lookahead +1)")
	void 정확히_size로_나눠떨어져도_빈_마지막페이지없이_nextCursor가_null이_된다() {
		// 점령 4개 = size 2 × 2페이지. lookahead 덕에 두 번째 페이지에서 바로 null 이어야 한다.
		seedOccupied(0, 1);
		seedOccupied(0, 2);
		seedOccupied(1, 0);

		OccupiedGridPage page1 = gridQueryService.getOccupiedInViewport(me, blockBounds(), null, 2);
		OccupiedGridPage page2 = gridQueryService.getOccupiedInViewport(me, blockBounds(), page1.nextCursor(), 2);

		assertThat(page2.items()).hasSize(2);
		assertThat(page2.nextCursor()).isNull();
	}

	// 검증: FR-GRID-08
	@Test
	@DisplayName("전체 페이지 순회 결과는 비페이지 조회 결과와 동일 집합이다 (누락·중복 없음)")
	void 전체페이지_순회결과는_비페이지_조회결과와_동일_집합이다() {
		seedOccupied(0, 1);
		seedOccupied(1, 0);
		seedOccupied(1, 2);
		seedOccupied(2, 2);

		List<String> collected = new ArrayList<>();
		String cursor = null;
		do {
			OccupiedGridPage page = gridQueryService.getOccupiedInViewport(me, blockBounds(), cursor, 2);
			collected.addAll(gridIds(page));
			cursor = page.nextCursor();
		} while (cursor != null);

		List<String> full = gridQueryService.getOccupiedInViewport(me, blockBounds())
			.stream().map(OccupiedGridView::gridId).toList();
		assertThat(collected).doesNotHaveDuplicates();
		assertThat(collected).containsExactlyInAnyOrderElementsOf(full);
	}

	// 검증: FR-GRID-09
	@Test
	@DisplayName("잘못된 커서는 INVALID_CURSOR 를 던진다 (Base64 불량·정수 아님·구분자 없음)")
	void 잘못된_커서는_INVALID_CURSOR를_던진다() {
		List<String> badCursors = List.of(
			"!!!not-base64!!!",
			base64Url("41643110460"),
			base64Url("abc_def"));

		for (String bad : badCursors) {
			assertThatThrownBy(() -> gridQueryService.getOccupiedInViewport(me, blockBounds(), bad, 10))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", GridErrorCode.INVALID_CURSOR);
		}
	}

	// 검증: FR-GRID-09
	@Test
	@DisplayName("size 가 0 이하거나 상한을 초과하면 INVALID_PAGE_SIZE 를 던진다")
	void size가_0이하거나_상한초과면_INVALID_PAGE_SIZE를_던진다() {
		for (int badSize : new int[] {0, -1, 5001}) {
			assertThatThrownBy(() -> gridQueryService.getOccupiedInViewport(me, blockBounds(), null, badSize))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", GridErrorCode.INVALID_PAGE_SIZE);
		}
	}

	// 검증: FR-GRID-09
	@Test
	@DisplayName("남서 좌표가 북동보다 크면 INVALID_VIEWPORT 를 던진다")
	void 남서_좌표가_북동보다_크면_INVALID_VIEWPORT를_던진다() {
		ViewportBounds inverted = new ViewportBounds(37.60, 127.10, 37.50, 127.05);

		assertThatThrownBy(() -> gridQueryService.getOccupiedInViewport(me, inverted))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", GridErrorCode.INVALID_VIEWPORT);
	}

	// 검증: FR-GRID-09
	@Test
	@DisplayName("WGS84 범위 밖 유한 좌표는 INVALID_VIEWPORT 를 던진다")
	void WGS84_범위_밖_유한_좌표는_INVALID_VIEWPORT를_던진다() {
		// 1e308 은 유한이라 뒤집힘·면적(폭 0) 검사를 다 통과하고 Proj4J 정규화에서 스레드가 안 돌아온다 (Codex 지적)
		ViewportBounds outOfRange = new ViewportBounds(37.50, 1.0e308, 37.55, 1.0e308);

		assertThatThrownBy(() -> gridQueryService.getOccupiedInViewport(me, outOfRange))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", GridErrorCode.INVALID_VIEWPORT);
	}

	// 검증: FR-GRID-09
	@Test
	@DisplayName("NaN 좌표는 INVALID_VIEWPORT 를 던진다")
	void NaN_좌표는_INVALID_VIEWPORT를_던진다() {
		ViewportBounds nanBounds = new ViewportBounds(Double.NaN, 127.00, 37.55, 127.05);

		assertThatThrownBy(() -> gridQueryService.getOccupiedInViewport(me, nanBounds))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", GridErrorCode.INVALID_VIEWPORT);
	}

	// 검증: FR-GRID-09
	@Test
	@DisplayName("면적 상한을 초과하면 VIEWPORT_TOO_LARGE 를 던진다")
	void 면적_상한을_초과하면_VIEWPORT_TOO_LARGE를_던진다() {
		ViewportBounds huge = new ViewportBounds(37.0, 127.0, 38.0, 128.0);

		assertThatThrownBy(() -> gridQueryService.getOccupiedInViewport(me, huge))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", GridErrorCode.VIEWPORT_TOO_LARGE);
	}

	@Test
	@DisplayName("포맷이 틀린 gridId 는 INVALID_GRID_ID 를 던진다")
	void 포맷이_틀린_gridId는_INVALID_GRID_ID를_던진다() {
		assertThatThrownBy(() -> gridQueryService.getCell(me, "not-a-grid"))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", GridErrorCode.INVALID_GRID_ID);
	}
}
