package com.msg.fillmap.usergrid.repository;

import static com.msg.fillmap.region.RegionTestFixtures.CELL_AREA_M2;
import static com.msg.fillmap.region.RegionTestFixtures.cellBlockPolygonJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.region.repository.RegionRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 동 단위 내 영상 native 조회 (getRegionVideos videos⨝grids equi-join, 실 PostGIS · MSG-167 §D3 · Owner B).
 * 격자 축 귀속(grids.region_code)으로 그 행정동 격자들에 올린 내 ACTIVE 영상을 created_at 내림차순으로 잡는지,
 * 영상 좌표가 옆 동이어도 격자 소속 동으로 포함되는지(DoD 핵심), 개인 도감 격리·ACTIVE 필터·PRIVATE/인코딩 중
 * 포함·gridId 포함(Open Q2)·빈 결과·geospatial 0 을 검증한다. HTTP 200/400/401 은 CollectionControllerTest 담당.
 *
 * 격리(공유 로컬 DB): 99953 대역 합성 region_code + grid_y 39700 대역 합성 grid + @Transactional 롤백.
 * regions/grids/videos/user_grids truncate 금지, 단언은 전부 자기 유저 기준.
 */
@SpringBootTest
@Transactional
@DisplayName("UserGridRepository 동 단위 내 영상 (getRegionVideos, 실 PostGIS)")
class RegionVideosRepositoryTest {

	private static final long GY0 = 17511L;
	private static final long GX0 = 7743L;
	private static final String REGION_A = "9995300001";
	private static final String REGION_B = "9995300002";
	private static final LocalDateTime BASE = LocalDateTime.of(2026, 7, 20, 12, 0, 0);

	@Autowired
	private UserGridRepository userGridRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RegionRepository regionRepository;

	@Autowired
	private EntityManager em;

	/** 격자 (GY0,GX0) 중심점을 덮는 합성 행정동 A(3×3 격자 블록)를 시드한다. */
	private void seedRegionA() {
		String polygon = cellBlockPolygonJson(GY0, GY0 + 3, GX0, GX0 + 3);
		regionRepository.upsert(REGION_A, "m167v합성동A", REGION_A.substring(0, 5), polygon, CELL_AREA_M2);
	}

	/** A 에서 북쪽으로 떨어진 합성 행정동 B(3×3 블록) — "옆 동" 영상 좌표가 여기 든다. */
	private void seedRegionB() {
		String polygon = cellBlockPolygonJson(GY0 + 10, GY0 + 13, GX0, GX0 + 3);
		regionRepository.upsert(REGION_B, "m167v합성동B", REGION_B.substring(0, 5), polygon, CELL_AREA_M2);
	}

	private long newUser() {
		String email = "m167v-" + System.nanoTime() + "@example.com";
		return userRepository.save(User.createLocalUser(email, "hash", "m167v도감")).getId();
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

	/** 격자 (GY0,GX0) 을 시드하고 region A 로 라벨한다. */
	private String seedGridA() {
		String grid = GridFixtures.seedGrid(em, GY0, GX0);
		label(grid);
		return grid;
	}

	/**
	 * 영상 한 건을 지정 좌표·상태·공개범위·created_at 로 삽입하고 id 를 돌려준다. geom NOT NULL 이라 좌표를 넣되,
	 * 격자 귀속은 grid_id 로만 정해진다(geom 은 격자 축 조회에 무관 — DoD 검증용으로 옆 동 좌표를 넣을 수 있다).
	 */
	private long seedVideo(long userId, String gridId, String thumbnailKey, String processingStatus,
		String visibility, String status, GridPoint point, LocalDateTime createdAt) {
		String originalKey = "videos/original/m167v-" + System.nanoTime() + ".mp4";
		em.createNativeQuery("""
			INSERT INTO videos (
				user_id, grid_id, original_s3_key, thumbnail_url, geom, duration_sec,
				recorded_at, created_at, processing_status, visibility, status
			)
			VALUES (
				:userId, :gridId, :originalKey, CAST(:thumbKey AS text),
				ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography,
				10, :createdAt, :createdAt, :processingStatus, :visibility, :status
			)
			""")
			.setParameter("userId", userId)
			.setParameter("gridId", gridId)
			.setParameter("originalKey", originalKey)
			.setParameter("thumbKey", thumbnailKey)
			.setParameter("lon", point.lon())
			.setParameter("lat", point.lat())
			.setParameter("createdAt", createdAt)
			.setParameter("processingStatus", processingStatus)
			.setParameter("visibility", visibility)
			.setParameter("status", status)
			.executeUpdate();
		return ((Number) em.createNativeQuery("SELECT id FROM videos WHERE original_s3_key = :originalKey")
			.setParameter("originalKey", originalKey)
			.getSingleResult()).longValue();
	}

	/** READY·PUBLIC·ACTIVE 영상을 격자 중심점 좌표로 삽입한다 (기본 케이스). */
	private long seedReadyVideo(long userId, String gridId, String thumbnailKey, LocalDateTime createdAt) {
		return seedVideo(userId, gridId, thumbnailKey, "READY", "PUBLIC", "ACTIVE",
			GridFixtures.pointAt(GY0 + 0.5, GX0 + 0.5), createdAt);
	}

	// 검증: FR-COLLECT-10
	@Test
	@DisplayName("그_행정동_격자들에_올린_내_영상을_created_at_내림차순으로_반환한다")
	void 그_행정동_격자들에_올린_내_영상을_created_at_내림차순으로_반환한다() {
		long me = newUser();
		seedRegionA();
		String grid = seedGridA();
		long older = seedReadyVideo(me, grid, "thumbs/older.jpg", BASE.minusHours(2));
		long newer = seedReadyVideo(me, grid, "thumbs/newer.jpg", BASE);
		long middle = seedReadyVideo(me, grid, "thumbs/middle.jpg", BASE.minusHours(1));

		List<RegionVideoProjection> result = userGridRepository.getRegionVideos(me, REGION_A);

		assertThat(result).extracting(RegionVideoProjection::getVideoId)
			.containsExactly(newer, middle, older);
		assertThat(result.get(0).getGridId()).isEqualTo(grid);
		assertThat(result.get(0).getDurationSec()).isEqualTo(10);
	}

	// 검증: FR-COLLECT-10
	@Test
	@DisplayName("같은_created_at_영상들은_id_내림차순_타이브레이크로_결정적으로_정렬된다")
	void 같은_created_at_영상들은_id_내림차순_타이브레이크로_결정적으로_정렬된다() {
		long me = newUser();
		seedRegionA();
		String grid = seedGridA();
		// 동일 초 업로드 3건 — created_at 이 전부 같아 타이브레이크(v.id DESC)만이 순서를 결정한다.
		long first = seedReadyVideo(me, grid, "thumbs/tie1.jpg", BASE);
		long second = seedReadyVideo(me, grid, "thumbs/tie2.jpg", BASE);
		long third = seedReadyVideo(me, grid, "thumbs/tie3.jpg", BASE);

		List<RegionVideoProjection> result = userGridRepository.getRegionVideos(me, REGION_A);

		// id 는 시퀀스라 나중 삽입이 항상 크다 — id DESC = 최근 삽입 우선, 요청마다 동일한 순서.
		assertThat(result).extracting(RegionVideoProjection::getVideoId)
			.containsExactly(third, second, first);
	}

	// 검증: FR-COLLECT-10
	@Test
	@DisplayName("영상_좌표가_옆_행정동이어도_격자_소속_행정동_기준으로_포함된다")
	void 영상_좌표가_옆_행정동이어도_격자_소속_행정동_기준으로_포함된다() {
		long me = newUser();
		seedRegionA();
		seedRegionB();
		String grid = seedGridA();
		// 영상 좌표는 옆 동(B) 안이지만 grid_id 는 A 로 라벨된 격자다 — 격자 축이라 A 로 잡혀야 한다(DoD 핵심).
		long video = seedVideo(me, grid, "thumbs/nextdoor.jpg", "READY", "PUBLIC", "ACTIVE",
			GridFixtures.pointAt(GY0 + 11.5, GX0 + 1.5), BASE);

		assertThat(userGridRepository.getRegionVideos(me, REGION_A))
			.extracting(RegionVideoProjection::getVideoId).containsExactly(video);
		// 영상 좌표가 든 B 로 조회하면 그 격자는 B 소속이 아니라 안 잡힌다(이중 축 혼선 없음).
		assertThat(userGridRepository.getRegionVideos(me, REGION_B)).isEmpty();
	}

	// 검증: FR-COLLECT-10, FR-COLLECT-12
	@Test
	@DisplayName("다른_사용자의_영상은_포함되지_않는다")
	void 다른_사용자의_영상은_포함되지_않는다() {
		long me = newUser();
		long other = newUser();
		seedRegionA();
		String grid = seedGridA();
		long mine = seedReadyVideo(me, grid, "thumbs/mine.jpg", BASE);
		seedReadyVideo(other, grid, "thumbs/theirs.jpg", BASE);

		List<RegionVideoProjection> result = userGridRepository.getRegionVideos(me, REGION_A);

		assertThat(result).extracting(RegionVideoProjection::getVideoId).containsExactly(mine);
	}

	// 검증: FR-COLLECT-10
	@Test
	@DisplayName("DELETED_영상은_포함되지_않는다")
	void DELETED_영상은_포함되지_않는다() {
		long me = newUser();
		seedRegionA();
		String grid = seedGridA();
		long active = seedReadyVideo(me, grid, "thumbs/active.jpg", BASE);
		seedVideo(me, grid, "thumbs/deleted.jpg", "READY", "PUBLIC", "DELETED",
			GridFixtures.pointAt(GY0 + 0.5, GX0 + 0.5), BASE);

		List<RegionVideoProjection> result = userGridRepository.getRegionVideos(me, REGION_A);

		assertThat(result).extracting(RegionVideoProjection::getVideoId).containsExactly(active);
	}

	// 검증: FR-COLLECT-10
	@Test
	@DisplayName("PRIVATE_영상도_내_도감이라_포함된다")
	void PRIVATE_영상도_내_도감이라_포함된다() {
		long me = newUser();
		seedRegionA();
		String grid = seedGridA();
		long privateVideo = seedVideo(me, grid, "thumbs/private.jpg", "READY", "PRIVATE", "ACTIVE",
			GridFixtures.pointAt(GY0 + 0.5, GX0 + 0.5), BASE);

		List<RegionVideoProjection> result = userGridRepository.getRegionVideos(me, REGION_A);

		assertThat(result).extracting(RegionVideoProjection::getVideoId).containsExactly(privateVideo);
	}

	// 검증: FR-COLLECT-10
	@Test
	@DisplayName("인코딩_중_영상은_thumbnailUrl이_null로_포함된다")
	void 인코딩_중_영상은_thumbnailUrl이_null로_포함된다() {
		long me = newUser();
		seedRegionA();
		String grid = seedGridA();
		// 인코딩 중이면 썸네일 key 가 없다 — 무필터라 포함되고 thumbnailKey null → 서비스 presign 이 thumbnailUrl null 로 흡수.
		long encoding = seedVideo(me, grid, null, "ENCODING", "PRIVATE", "ACTIVE",
			GridFixtures.pointAt(GY0 + 0.5, GX0 + 0.5), BASE);

		RegionVideoProjection row = userGridRepository.getRegionVideos(me, REGION_A).get(0);

		assertThat(row.getVideoId()).isEqualTo(encoding);
		assertThat(row.getProcessingStatus()).isEqualTo("ENCODING");
		assertThat(row.getThumbnailKey()).isNull();
	}

	// 검증: FR-COLLECT-10
	@Test
	@DisplayName("항목에_gridId가_포함된다")
	void 항목에_gridId가_포함된다() {
		long me = newUser();
		seedRegionA();
		String grid = seedGridA();
		seedReadyVideo(me, grid, "thumbs/g.jpg", BASE);

		RegionVideoProjection row = userGridRepository.getRegionVideos(me, REGION_A).get(0);

		assertThat(row.getGridId()).isEqualTo(grid);
	}

	// 검증: FR-COLLECT-10
	@Test
	@DisplayName("그_행정동에_내_영상이_없으면_빈_리스트다")
	void 그_행정동에_내_영상이_없으면_빈_리스트다() {
		long me = newUser();
		seedRegionA();
		seedGridA();

		assertThat(userGridRepository.getRegionVideos(me, REGION_A)).isEmpty();
	}

	// 검증: FR-COLLECT-10
	@Test
	@DisplayName("존재하지_않는_regionCode는_빈_리스트다")
	void 존재하지_않는_regionCode는_빈_리스트다() {
		long me = newUser();
		seedRegionA();
		String grid = seedGridA();
		seedReadyVideo(me, grid, "thumbs/g.jpg", BASE);

		assertThat(userGridRepository.getRegionVideos(me, "9995399999")).isEmpty();
	}

	@Test
	@DisplayName("동_단위_조회_쿼리에_ST__geospatial_연산이_없다")
	void 동_단위_조회_쿼리에_ST__geospatial_연산이_없다() throws Exception {
		Method method = UserGridRepository.class.getMethod("getRegionVideos", long.class, String.class);
		String sql = method.getAnnotation(Query.class).value();

		// 저장된 라벨(grids.region_code)을 equi 로 소비 — point-in-polygon 없음(성공 기준 8).
		assertThat(sql).doesNotContain("ST_");
		assertThat(sql).doesNotContainIgnoringCase("geom");
	}
}
