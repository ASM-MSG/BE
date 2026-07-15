package com.msg.fillmap.grid.service;

import static com.msg.fillmap.grid.GridConstants.GRID_LAT_STEP;
import static com.msg.fillmap.grid.GridConstants.GRID_LNG_STEP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
	@DisplayName("접근 A 와 B 는 서비스 경유에서도 동일한 격자 집합을 반환한다")
	void 접근_A와_B는_동일한_격자_집합을_반환한다() {
		List<OccupiedGridView> a = gridQueryService.getOccupiedInViewport(me, blockBounds(), ViewportStrategy.A);
		List<OccupiedGridView> b = gridQueryService.getOccupiedInViewport(me, blockBounds(), ViewportStrategy.B);

		assertThat(a).containsExactlyInAnyOrderElementsOf(b);
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
