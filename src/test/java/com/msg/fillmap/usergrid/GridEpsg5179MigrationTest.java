package com.msg.fillmap.usergrid;

import static com.msg.fillmap.region.RegionTestFixtures.CELL_AREA_M2;
import static com.msg.fillmap.region.RegionTestFixtures.cellBlockPolygonJson;
import static com.msg.fillmap.region.RegionTestFixtures.syntheticCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.grid.GridConstants;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.region.repository.RegionRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * V28 격자 이행 검증 (MSG-347 FR-5·FR-6·FR-7, 실 PostGIS).
 *
 * <p>이행이 끝난 DB 를 사후 조회해도 얻는 게 없다 — 로컬은 영상이 0건이라 단언이 공허하고, 이행 전 상태는
 * 이미 사라져 "전후 비교"가 성립하지 않는다. 그래서 구 체계 행을 직접 심고 V28 본문을 그 자리에서 실행한 뒤
 * 불변식을 확인하고 롤백한다. 마이그레이션의 모든 재계산이 보존된 원본 좌표에서 출발하는 멱등 설계라
 * (V28 머리주석) 이렇게 다시 돌려도 배포 때와 같은 결과가 나온다.
 *
 * <p>격리(공유 로컬 DB): 합성 유저·999 대역 합성 region·서해 공해상 좌표 + {@code @Transactional} 롤백.
 * 파괴적 사전 정리는 하지 않고 트랜잭션 롤백이 원상복구한다. 단 합계·전수 단언 일부가 전역 스코프 고정값이라
 * 빈 로컬 DB 를 전제한다 — 실데이터가 있으면 검증이 아니라 실패한다(레포 통합 테스트 관례와 동일).
 *
 * <p>시나리오는 셀 재편의 두 경계 사례를 포함한다. 구 셀 108608·108609 는 인접한 두 셀이지만 그 안의 두
 * 좌표가 새 체계에서는 같은 셀(17413_7646)로 합쳐지고(FR-6 "점령 격자 수는 달라질 수 있다"), DELETED 영상은
 * 행은 살아남되(FR-5 유실 0건) video_count 에는 안 잡힌다.
 */
@SpringBootTest
@Transactional
@DisplayName("V28 격자 이행 (EPSG:5179 재구축, 실 PostGIS)")
class GridEpsg5179MigrationTest {

	private static final Path MIGRATION = Path.of("src/main/resources/db/migration/V28__grid_epsg5179_rebuild.sql");

	/** 구 격자 계산 규칙 — 이행 대상 데이터를 만들려면 구 규칙이 필요하다. 정본은 이제 V28 SQL 안에만 남아 있다. */
	private static final double OLD_LAT_STEP = 0.0009;
	private static final double OLD_LNG_STEP = 0.00115;

	// 서해 공해상. A·B 는 구 x 경계(124.90035)를 사이에 두고 3.6m 떨어져 있어 구 셀은 다르고 새 셀은 같다.
	private static final double LAT = 35.6405;
	private static final double LON_A = 124.90033;
	private static final double LON_B = 124.90037;
	private static final double LON_C = 124.90385;

	private static final LocalDateTime T09 = LocalDateTime.of(2026, 8, 1, 9, 0, 0);
	private static final LocalDateTime T10 = LocalDateTime.of(2026, 8, 1, 10, 0, 0);
	private static final LocalDateTime T11 = LocalDateTime.of(2026, 8, 1, 11, 0, 0);
	private static final LocalDateTime T12 = LocalDateTime.of(2026, 8, 1, 12, 0, 0);

	private static final CoordinateTransform TO_METERS = toMetersTransform();

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RegionRepository regionRepository;

	@Autowired
	private EntityManager em;

	private long userId;
	private String regionCode;
	private long videoA;
	private long videoB;
	private long videoC;
	private long videoDeleted;
	private String mergedCell;
	private String separateCell;

	@BeforeEach
	void seedOldWorld() {
		userId = userRepository.save(
			User.createLocalUser("m347-" + UUID.randomUUID() + "@example.com", "hash", "이행대상")).getId();
		mergedCell = GridEncoder.encode(LAT, LON_A);
		separateCell = GridEncoder.encode(LAT, LON_C);
		seedRegionCovering();

		String oldCellA = oldGridId(LAT, LON_A);
		String oldCellB = oldGridId(LAT, LON_B);
		String oldCellC = oldGridId(LAT, LON_C);
		seedOldGrid(oldCellA);
		seedOldGrid(oldCellB);
		seedOldGrid(oldCellC);

		videoA = seedVideo(oldCellA, LON_A, "ACTIVE", T10);
		videoB = seedVideo(oldCellB, LON_B, "ACTIVE", T11);
		videoC = seedVideo(oldCellC, LON_C, "ACTIVE", T09);
		videoDeleted = seedVideo(oldCellC, LON_C, "DELETED", T12);

		// 구 도감 — 셀마다 1행. videoA 가 대표라 새 셀에서도 승계돼야 한다.
		seedUserGrid(oldCellA, 1, videoA, T10);
		seedUserGrid(oldCellB, 1, null, T11);
		seedUserGrid(oldCellC, 2, null, T09);

		// 구 체계로 계산됐던 탐험률 — 5b 단계가 갈아엎어야 하는 낡은 캐시다.
		em.createNativeQuery("""
			INSERT INTO region_stats (user_id, region_code, collected_count, total_count, progress_rate, updated_at)
			VALUES (:userId, :regionCode, 99, 99, 99.99, :at)
			""")
			.setParameter("userId", userId)
			.setParameter("regionCode", regionCode)
			.setParameter("at", T09)
			.executeUpdate();
		em.flush();
	}

	@Test
	@DisplayName("이행 후 영상 총수가 이행 전과 같다 — DELETED 행도 새 셀을 받고 살아남는다")
	void 이행_후_영상_총수가_이행_전과_같다() {
		long before = countVideos();

		runMigration();

		assertThat(countVideos()).isEqualTo(before);
		assertThat(gridIdOfVideo(videoDeleted)).isEqualTo(separateCell);
	}

	@Test
	@DisplayName("이행 후 모든 영상의 grid_id 는 원본 좌표 Proj4J 재계산과 일치한다")
	void 이행_후_모든_영상의_grid_id는_원본_좌표_Proj4J_재계산과_일치한다() {
		runMigration();

		List<String> mismatches = new ArrayList<>();
		for (Object[] row : videoCoordinates()) {
			long id = ((Number) row[0]).longValue();
			String stored = (String) row[1];
			double lat = ((Number) row[2]).doubleValue();
			double lon = ((Number) row[3]).doubleValue();
			String recomputed = GridEncoder.encode(lat, lon);
			if (!recomputed.equals(stored)) {
				mismatches.add(describeMismatch(id, stored, recomputed, lat, lon));
			}
		}

		assertThat(mismatches).as("PostGIS ST_Transform 과 Proj4J 의 격자 판정 불일치").isEmpty();
	}

	@Test
	@DisplayName("이행 후 user_grids 의 video_count 합이 삭제 제외 영상 수와 같다")
	void 이행_후_user_grids의_video_count_합이_삭제_제외_영상_수와_같다() {
		runMigration();

		assertThat(scalar("SELECT COALESCE(SUM(video_count), 0) FROM user_grids"))
			.isEqualTo(scalar("SELECT COUNT(*) FROM videos WHERE status <> 'DELETED'"))
			.isEqualTo(3L);
		// 인접 두 구 셀이 한 새 셀로 합쳐져 점령 격자 수가 3 에서 2 로 줄었다 (FR-6).
		assertThat(scalar("SELECT COUNT(*) FROM user_grids WHERE user_id = " + userId)).isEqualTo(2L);
	}

	@Test
	@DisplayName("이행 후 first_collected_at 은 새 셀 소속 영상들의 최소 업로드 시각이다")
	void 이행_후_first_collected_at은_새_셀_소속_영상들의_최소_업로드_시각이다() {
		runMigration();

		Object[] merged = occupation(mergedCell);
		assertThat((LocalDateTime) merged[0]).isEqualTo(T10);   // videoA(10시)·videoB(11시) 중 최소
		assertThat((LocalDateTime) merged[1]).isEqualTo(T11);
		assertThat(((Number) merged[2]).intValue()).isEqualTo(2);
		assertThat(((Number) merged[3]).longValue()).isEqualTo(videoA);   // 구 대표 영상 승계

		Object[] separate = occupation(separateCell);
		// DELETED(12시)는 모집단 밖이라 last_uploaded_at 을 끌어올리지 않는다.
		assertThat((LocalDateTime) separate[0]).isEqualTo(T09);
		assertThat((LocalDateTime) separate[1]).isEqualTo(T09);
		assertThat(((Number) separate[2]).intValue()).isEqualTo(1);
		assertThat(separate[3]).isNull();
	}

	@Test
	@DisplayName("이행 후 구 체계 격자 행이 남아있지 않다")
	void 이행_후_구_체계_격자_행이_남아있지_않다() {
		runMigration();

		assertThat(gridIds()).containsExactlyInAnyOrder(mergedCell, separateCell);
		assertThat(scalar("SELECT COUNT(*) FROM videos v LEFT JOIN grids g ON g.grid_id = v.grid_id"
			+ " WHERE g.grid_id IS NULL")).isZero();
	}

	@Test
	@DisplayName("이행 후 grids 의 region_code 는 새 중심점 기준으로 판정돼 있다")
	void 이행_후_grids의_region_code는_새_중심점_기준으로_판정돼_있다() {
		runMigration();

		for (String gridId : gridIds()) {
			Object[] row = (Object[]) em.createNativeQuery("""
				SELECT region_code, ST_Y(center_geom::geometry), ST_X(center_geom::geometry)
				FROM grids WHERE grid_id = :gridId
				""")
				.setParameter("gridId", gridId)
				.getSingleResult();
			GridPoint expected = GridEncoder.center(gridId);

			// 저장된 중심점이 새 셀 중심이고, 라벨은 그 점을 덮는 합성 행정동이다.
			assertThat(((Number) row[1]).doubleValue()).isCloseTo(expected.lat(), offset(1e-9));
			assertThat(((Number) row[2]).doubleValue()).isCloseTo(expected.lon(), offset(1e-9));
			assertThat((String) row[0]).isEqualTo(regionCode);
		}
	}

	@Test
	@DisplayName("이행 후 region_stats 가 재구성된 user_grids 집계와 일치한다")
	void 이행_후_region_stats가_재구성된_user_grids_집계와_일치한다() {
		long totalGridCount = scalar(
			"SELECT total_grid_count FROM regions WHERE region_code = '" + regionCode + "'");

		runMigration();

		Object[] row = (Object[]) em.createNativeQuery("""
			SELECT collected_count, total_count, progress_rate FROM region_stats
			WHERE user_id = :userId AND region_code = :regionCode
			""")
			.setParameter("userId", userId)
			.setParameter("regionCode", regionCode)
			.getSingleResult();

		// 낡은 값(99·99·99.99)이 새 도감(격자 2개)으로 갈아엎였다.
		assertThat(((Number) row[0]).intValue()).isEqualTo(2);
		assertThat(((Number) row[1]).longValue()).isEqualTo(totalGridCount);
		assertThat(((Number) row[2]).doubleValue())
			.isCloseTo(Math.round(2 * 100.0 / totalGridCount * 100) / 100.0, offset(0.01));
		assertThat(scalar("SELECT COUNT(*) FROM region_stats WHERE user_id = " + userId)).isEqualTo(1L);
	}

	/**
	 * V28 본문을 지금 트랜잭션에서 실행한다. 파일을 그대로 읽어 구문 단위로 쪼개므로 마이그레이션이 바뀌면
	 * 이 테스트도 자동으로 바뀐 내용을 검증한다.
	 */
	private void runMigration() {
		em.flush();
		assertThat(missionGridsWithoutGrid())
			.as("grids 행이 없는 mission_grids 가 있으면 이 재실행이 그 행의 새 ID 를 구 규칙으로 다시 파싱해 "
				+ "엉뚱한 값을 만든다(예 17413_7646 → 위도 15.7·경도 8.8, 유효 범위라 V28 가드에도 안 걸린다). "
				+ "배포 때 V28 은 Flyway 로 한 번만 돌아 무관하지만, 여기서는 이행 결과를 신뢰할 수 없다")
			.isZero();
		for (String statement : statements(readMigration())) {
			em.createNativeQuery(statement).executeUpdate();
		}
		em.clear();
	}

	private long missionGridsWithoutGrid() {
		return scalar("""
			SELECT COUNT(*) FROM mission_grids mg
			WHERE NOT EXISTS (SELECT 1 FROM grids g WHERE g.grid_id = mg.grid_id)
			""");
	}

	private static String readMigration() {
		try {
			// pg_temp 함수는 세션 수명이라 한 커넥션에서 본문이 두 번 돌면 이름이 충돌한다(테스트 메서드마다 실행).
			// 실제 마이그레이션은 한 번만 돌아 무관한 차이이고, 임시 테이블은 원문이 이미 DROP IF EXISTS 로 막아 뒀다.
			return Files.readString(MIGRATION, StandardCharsets.UTF_8)
				.replace("CREATE FUNCTION pg_temp.", "CREATE OR REPLACE FUNCTION pg_temp.");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** 세미콜론 분리 — {@code $$} 로 감싼 DO 블록·함수 본문 안의 세미콜론은 구문 끝이 아니라 건너뛴다. */
	private static List<String> statements(String sql) {
		List<String> statements = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean inDollarQuote = false;
		for (int i = 0; i < sql.length(); i++) {
			if (sql.startsWith("$$", i)) {
				inDollarQuote = !inDollarQuote;
				current.append("$$");
				i++;
				continue;
			}
			char c = sql.charAt(i);
			if (c == ';' && !inDollarQuote) {
				addIfNotBlank(statements, current);
				current.setLength(0);
			} else {
				current.append(c);
			}
		}
		addIfNotBlank(statements, current);
		return statements;
	}

	private static void addIfNotBlank(List<String> statements, StringBuilder current) {
		String statement = current.toString().trim();
		if (!statement.isBlank()) {
			statements.add(statement);
		}
	}

	/** 불일치 진단 — 셀 경계까지 거리를 함께 낸다. 서브밀리미터면 PROJ 구현 간 수치 오차, 크면 규칙 불일치다. */
	private static String describeMismatch(long videoId, String stored, String recomputed, double lat, double lon) {
		ProjCoordinate meters = TO_METERS.transform(new ProjCoordinate(lon, lat), new ProjCoordinate());
		double toBoundary = Math.min(
			Math.min(meters.x % 100, 100 - meters.x % 100),
			Math.min(meters.y % 100, 100 - meters.y % 100));
		return "video=%d 저장=%s Proj4J=%s (lat=%s lon=%s, 5179 x=%.4f y=%.4f, 셀 경계까지 %.4fm)"
			.formatted(videoId, stored, recomputed, lat, lon, meters.x, meters.y, toBoundary);
	}

	private static CoordinateTransform toMetersTransform() {
		CRSFactory factory = new CRSFactory();
		return new CoordinateTransformFactory().createTransform(
			factory.createFromParameters("WGS84", "+proj=longlat +datum=WGS84 +no_defs"),
			factory.createFromParameters("EPSG:5179", GridConstants.CRS_DEF_EPSG5179));
	}

	private static String oldGridId(double lat, double lon) {
		return (long) Math.floor(lat / OLD_LAT_STEP) + "_" + (long) Math.floor(lon / OLD_LNG_STEP);
	}

	/** 새 셀 두 개의 중심점을 덮는 합성 행정동 — 재판정된 라벨이 붙을 자리다. */
	private void seedRegionCovering() {
		regionCode = syntheticCode("347");
		long gridY = GridEncoder.decode(mergedCell).gridY();
		long minGridX = GridEncoder.decode(mergedCell).gridX();
		long maxGridX = GridEncoder.decode(separateCell).gridX();
		regionRepository.upsert(regionCode, "m347합성동", regionCode.substring(0, 5),
			cellBlockPolygonJson(gridY, gridY + 1, minGridX, maxGridX + 1), CELL_AREA_M2);
	}

	/** 구 규칙(등간격 위경도)으로 만든 격자 행 — 이행이 걷어내야 할 대상이다. */
	private void seedOldGrid(String gridId) {
		long gridY = Long.parseLong(gridId.split("_")[0]);
		long gridX = Long.parseLong(gridId.split("_")[1]);
		em.createNativeQuery("""
			INSERT INTO grids (grid_id, grid_y, grid_x, center_geom, bbox_geom)
			VALUES (
				:gridId, :gridY, :gridX,
				ST_SetSRID(ST_MakePoint(:centerLon, :centerLat), 4326)::geography,
				ST_MakeEnvelope(:west, :south, :east, :north, 4326)::geography
			)
			ON CONFLICT (grid_id) DO NOTHING
			""")
			.setParameter("gridId", gridId)
			.setParameter("gridY", gridY)
			.setParameter("gridX", gridX)
			.setParameter("centerLat", (gridY + 0.5) * OLD_LAT_STEP)
			.setParameter("centerLon", (gridX + 0.5) * OLD_LNG_STEP)
			.setParameter("south", gridY * OLD_LAT_STEP)
			.setParameter("north", (gridY + 1) * OLD_LAT_STEP)
			.setParameter("west", gridX * OLD_LNG_STEP)
			.setParameter("east", (gridX + 1) * OLD_LNG_STEP)
			.executeUpdate();
	}

	private long seedVideo(String gridId, double lon, String status, LocalDateTime createdAt) {
		String originalKey = "videos/original/m347-" + UUID.randomUUID() + ".mp4";
		em.createNativeQuery("""
			INSERT INTO videos (user_id, grid_id, original_s3_key, geom, duration_sec,
				recorded_at, created_at, processing_status, visibility, status)
			VALUES (:userId, :gridId, :originalKey,
				ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography,
				10, :createdAt, :createdAt, 'READY', 'PUBLIC', :status)
			""")
			.setParameter("userId", userId)
			.setParameter("gridId", gridId)
			.setParameter("originalKey", originalKey)
			.setParameter("lon", lon)
			.setParameter("lat", LAT)
			.setParameter("createdAt", createdAt)
			.setParameter("status", status)
			.executeUpdate();
		return ((Number) em.createNativeQuery("SELECT id FROM videos WHERE original_s3_key = :originalKey")
			.setParameter("originalKey", originalKey)
			.getSingleResult()).longValue();
	}

	private void seedUserGrid(String gridId, int videoCount, Long coverVideoId, LocalDateTime at) {
		em.createNativeQuery("""
			INSERT INTO user_grids (user_id, grid_id, first_collected_at, last_uploaded_at, video_count, cover_video_id)
			VALUES (:userId, :gridId, :at, :at, :videoCount, :coverVideoId)
			""")
			.setParameter("userId", userId)
			.setParameter("gridId", gridId)
			.setParameter("at", at)
			.setParameter("videoCount", videoCount)
			.setParameter("coverVideoId", coverVideoId)
			.executeUpdate();
	}

	private long countVideos() {
		return scalar("SELECT COUNT(*) FROM videos");
	}

	private String gridIdOfVideo(long videoId) {
		return (String) em.createNativeQuery("SELECT grid_id FROM videos WHERE id = :id")
			.setParameter("id", videoId)
			.getSingleResult();
	}

	@SuppressWarnings("unchecked")
	private List<Object[]> videoCoordinates() {
		return em.createNativeQuery("""
			SELECT id, grid_id, ST_Y(geom::geometry), ST_X(geom::geometry) FROM videos ORDER BY id
			""").getResultList();
	}

	@SuppressWarnings("unchecked")
	private List<String> gridIds() {
		return em.createNativeQuery("SELECT grid_id FROM grids ORDER BY grid_id").getResultList();
	}

	private Object[] occupation(String gridId) {
		return (Object[]) em.createNativeQuery("""
			SELECT first_collected_at, last_uploaded_at, video_count, cover_video_id
			FROM user_grids WHERE user_id = :userId AND grid_id = :gridId
			""")
			.setParameter("userId", userId)
			.setParameter("gridId", gridId)
			.getSingleResult();
	}

	private long scalar(String sql) {
		return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
	}
}
