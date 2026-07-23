package com.msg.fillmap.usergrid.repository;

import static com.msg.fillmap.grid.GridConstants.GRID_LAT_STEP;
import static com.msg.fillmap.grid.GridConstants.GRID_LNG_STEP;
import static com.msg.fillmap.region.RegionTestFixtures.CELL_AREA_M2;
import static com.msg.fillmap.region.RegionTestFixtures.rectanglePolygonJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.region.repository.RegionRepository;
import com.msg.fillmap.region.service.RegionQueryService;
import com.msg.fillmap.region.service.RegionView;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 갤러리 목록 regionName (getCollectionGrids grids·regions equi-join, 실 PostGIS · MSG-167 §D4 · Owner B).
 * 저장된 격자 라벨(grids.region_code)을 순수 equi 로 소비해 항목에 regionName(격자 중심점 행정동 이름)을 붙이는지,
 * 무귀속(라벨 NULL)이면 null 인지, 그 이름이 by-grid 귀속(resolveByPoint)과 같은 행정동인지 검증한다.
 * 153 계약(나머지 필드·정렬·30 상한·geospatial 0)의 회귀는 기존 CollectionGridsRepositoryTest 가 담당한다.
 *
 * 격리(공유 로컬 DB): 99952 대역 합성 region_code + 서해 공해상 grid(GY 39700) + @Transactional 롤백.
 */
@SpringBootTest
@Transactional
@DisplayName("갤러리 목록 regionName (grids·regions equi-join, 실 PostGIS)")
class CollectionGridsRegionNameTest {

	private static final long GY0 = 39700L;
	private static final long GX0 = 108500L;
	private static final String REGION = "9995200001";
	private static final LocalDateTime BASE = LocalDateTime.of(2026, 7, 20, 12, 0, 0);

	@Autowired
	private UserGridRepository userGridRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RegionRepository regionRepository;

	@Autowired
	private RegionQueryService regionQueryService;

	@Autowired
	private EntityManager em;

	/** 격자 (GY0,GX0) 중심점을 덮는 합성 행정동(3×3 격자 블록)을 시드한다. */
	private void seedRegion() {
		String polygon = rectanglePolygonJson(
			GX0 * GRID_LNG_STEP, GY0 * GRID_LAT_STEP,
			(GX0 + 3) * GRID_LNG_STEP, (GY0 + 3) * GRID_LAT_STEP);
		regionRepository.upsert(REGION, "m167b합성동", REGION.substring(0, 5), polygon, CELL_AREA_M2);
	}

	private long newUser() {
		String email = "m167b-" + System.nanoTime() + "@example.com";
		return userRepository.save(User.createLocalUser(email, "hash", "m167b도감")).getId();
	}

	/** grids.region_code 를 중심점 판정(93/155/V5 동일 규칙)으로 라벨한다 — 저장 라벨 = by-grid 귀속. */
	private void label(String gridId) {
		em.createNativeQuery("""
			UPDATE grids g SET region_code = (
				SELECT r.region_code FROM regions r
				WHERE ST_Covers(r.boundary_geom, g.center_geom)
				ORDER BY r.region_code
				LIMIT 1)
			WHERE grid_id = :g
			""").setParameter("g", gridId).executeUpdate();
	}

	private void occupy(long userId, String gridId) {
		em.createNativeQuery("""
			INSERT INTO user_grids (user_id, grid_id, first_collected_at, last_uploaded_at, video_count)
			VALUES (:u, :g, :t, :t, 1)
			""")
			.setParameter("u", userId)
			.setParameter("g", gridId)
			.setParameter("t", BASE)
			.executeUpdate();
	}

	private String byGridName(long gy, long gx) {
		return regionQueryService.resolveByPoint((gy + 0.5) * GRID_LAT_STEP, (gx + 0.5) * GRID_LNG_STEP)
			.map(RegionView::regionName).orElse(null);
	}

	@Test
	@DisplayName("목록_항목에_격자_중심점_행정동_이름이_붙는다")
	void 목록_항목에_격자_중심점_행정동_이름이_붙는다() {
		long me = newUser();
		seedRegion();
		String grid = GridFixtures.seedGrid(em, GY0, GX0);
		label(grid);
		occupy(me, grid);

		CollectionGridProjection row = userGridRepository.getCollectionGrids(me).get(0);

		assertThat(row.getRegionName()).isEqualTo("m167b합성동");
	}

	@Test
	@DisplayName("region_code가_null인_격자의_regionName은_null이다")
	void region_code가_null인_격자의_regionName은_null이다() {
		long me = newUser();
		// 라벨하지 않은(무귀속/미판정) 격자 — LEFT JOIN 이라 목록 행은 유지되고 regionName 만 null.
		String grid = GridFixtures.seedGrid(em, GY0, GX0);
		occupy(me, grid);

		CollectionGridProjection row = userGridRepository.getCollectionGrids(me).get(0);

		assertThat(row.getGridId()).isEqualTo(grid);
		assertThat(row.getRegionName()).isNull();
	}

	@Test
	@DisplayName("regionName은_그_격자_by_grid_라벨과_같은_행정동_이름이다")
	void regionName은_그_격자_by_grid_라벨과_같은_행정동_이름이다() {
		long me = newUser();
		seedRegion();
		String grid = GridFixtures.seedGrid(em, GY0, GX0);
		label(grid);
		occupy(me, grid);

		CollectionGridProjection row = userGridRepository.getCollectionGrids(me).get(0);

		assertThat(row.getRegionName()).isEqualTo(byGridName(GY0, GX0)).isEqualTo("m167b합성동");
	}
}
