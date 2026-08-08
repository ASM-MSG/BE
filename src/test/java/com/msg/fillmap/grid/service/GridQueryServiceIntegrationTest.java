package com.msg.fillmap.grid.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.zone.entity.Zone;
import com.msg.fillmap.zone.repository.ZoneRepository;
import com.msg.fillmap.zone.service.ZoneNameQueryService;

@SpringBootTest
@Transactional
@DisplayName("GridQueryService 통합 (실 PostGIS)")
class GridQueryServiceIntegrationTest {

	private static final double 성수_LAT = 37.5445;
	private static final double 성수_LON = 127.0560;

	/**
	 * 표시명 검증용 합성 구역 (MSG-341). 테스트 블록을 덮는 3행×3열 사각형이고 priority 100 이라
	 * 같은 좌표를 덮는 실 시드 구역(priority 0)을 타이브레이크로 이긴다 — 시딩 상태와 무관하게 기대값이 고정된다.
	 */
	private static final String ZONE_NAME = "m341성수합성";

	@Autowired
	private GridQueryService gridQueryService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ZoneRepository zoneRepository;

	@MockitoSpyBean
	private ZoneNameQueryService zoneNameQueryService;

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

		GridIndex base = GridEncoder.decode(GridEncoder.encode(성수_LAT, 성수_LON));
		baseY = base.gridY();
		baseX = base.gridX();

		occupiedGridId = GridFixtures.seedGrid(em, baseY, baseX);
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
		GridPoint southWest = GridFixtures.pointAt(baseY + 0.5, baseX + 0.5);
		GridPoint northEast = GridFixtures.pointAt(baseY + 2.5, baseX + 2.5);
		return new ViewportBounds(southWest.lat(), southWest.lon(), northEast.lat(), northEast.lon());
	}

	/**
	 * 블록 안 (baseY + dy, baseX + dx) 셀을 내 점령으로 시드한다 — 페이지 시나리오용 볼륨.
	 */
	private String seedOccupied(long dy, long dx) {
		String gridId = GridFixtures.seedGrid(em, baseY + dy, baseX + dx);
		GridFixtures.seedUserGrid(em, me, gridId, 1);
		return gridId;
	}

	private static String base64Url(String raw) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	private static List<String> gridIds(OccupiedGridPage page) {
		return page.items().stream().map(OccupiedGridView::gridId).toList();
	}

	@Test
	@DisplayName("미점령 격자 조회는 occupied 가 거짓이고 videoCount 가 0 이다")
	void 미점령_격자_조회는_occupied가_거짓이고_videoCount가_0이다() {
		GridCellView view = gridQueryService.getCell(me, unoccupiedGridId);

		assertThat(view.gridId()).isEqualTo(unoccupiedGridId);
		assertThat(view.occupied()).isFalse();
		assertThat(view.videoCount()).isZero();
	}

	@Test
	@DisplayName("점령 격자 조회는 occupied 가 참이고 videoCount 를 반환한다")
	void 점령_격자_조회는_occupied가_참이고_videoCount를_반환한다() {
		GridCellView view = gridQueryService.getCell(me, occupiedGridId);

		assertThat(view.occupied()).isTrue();
		assertThat(view.videoCount()).isEqualTo(3);
	}

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

	@Test
	@DisplayName("구역 안 단일 격자 조회는 구역 이름과 위치 코드를 함께 담는다 (MSG-341 FR-1)")
	void 단일_격자_조회는_구역_안이면_zoneName과_zoneCell을_담는다() {
		GridCellView view = gridQueryService.getCell(me, occupiedGridId);

		// 사각형 북단이 A 라 baseY 는 3행 중 남단 C, 서단이 1 열이라 baseX 는 1
		assertThat(view.zoneName()).isEqualTo(ZONE_NAME);
		assertThat(view.zoneCell()).isEqualTo("C-1");
	}

	@Test
	@DisplayName("미점령 격자도 구역 안이면 이름이 계산된다 (격자는 논리 개념 — grids row·점령 무관, FR-4)")
	void 미점령_격자도_구역_안이면_이름이_계산된다() {
		GridCellView view = gridQueryService.getCell(me, unoccupiedGridId);

		assertThat(view.occupied()).isFalse();
		assertThat(view.zoneName()).isEqualTo(ZONE_NAME);
		assertThat(view.zoneCell()).isEqualTo("B-2");
	}

	@Test
	@DisplayName("구역 밖 격자는 zoneName·zoneCell 이 모두 null 이다 (폴백 조립은 클라이언트 몫, FR-3)")
	void 구역_밖_격자는_두_필드가_모두_null이다() {
		// 시드 구역 전체가 위도 37180~42305 대역이라 9999 행은 어느 사각형에도 들지 않는다
		GridCellView view = gridQueryService.getCell(me, "9999_9999");

		assertThat(view.zoneName()).isNull();
		assertThat(view.zoneCell()).isNull();
	}

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

	@Test
	@DisplayName("size 가 0 이하거나 상한을 초과하면 INVALID_PAGE_SIZE 를 던진다")
	void size가_0이하거나_상한초과면_INVALID_PAGE_SIZE를_던진다() {
		for (int badSize : new int[] {0, -1, 5001}) {
			assertThatThrownBy(() -> gridQueryService.getOccupiedInViewport(me, blockBounds(), null, badSize))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", GridErrorCode.INVALID_PAGE_SIZE);
		}
	}

	@Test
	@DisplayName("남서 좌표가 북동보다 크면 INVALID_VIEWPORT 를 던진다")
	void 남서_좌표가_북동보다_크면_INVALID_VIEWPORT를_던진다() {
		ViewportBounds inverted = new ViewportBounds(37.60, 127.10, 37.50, 127.05);

		assertThatThrownBy(() -> gridQueryService.getOccupiedInViewport(me, inverted))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", GridErrorCode.INVALID_VIEWPORT);
	}

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
