package com.msg.fillmap.grid.service;

import static com.msg.fillmap.grid.GridConstants.GRID_LAT_STEP;
import static com.msg.fillmap.grid.GridConstants.GRID_LNG_STEP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.grid.exception.GridErrorCode;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

@SpringBootTest
@Transactional
@DisplayName("GridQueryService 통합 (실 PostGIS)")
class GridQueryServiceIntegrationTest {

	private static final double 성수_LAT = 37.5445;
	private static final double 성수_LON = 127.0560;

	@Autowired
	private GridQueryService gridQueryService;

	@Autowired
	private UserRepository userRepository;

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
	}

	private ViewportBounds blockBounds() {
		return new ViewportBounds(
			(baseY + 0.5) * GRID_LAT_STEP, (baseX + 0.5) * GRID_LNG_STEP,
			(baseY + 2.5) * GRID_LAT_STEP, (baseX + 2.5) * GRID_LNG_STEP);
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
