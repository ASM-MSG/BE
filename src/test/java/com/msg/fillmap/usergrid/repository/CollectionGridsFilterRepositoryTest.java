package com.msg.fillmap.usergrid.repository;

import static com.msg.fillmap.region.RegionTestFixtures.CELL_AREA_M2;
import static com.msg.fillmap.region.RegionTestFixtures.cellBlockPolygonJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.region.repository.RegionRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 갤러리 격자 목록 확장 파라미터 (getCollectionGrids regionCode·sort·limit, 실 PostGIS · MSG-388 · Owner B).
 * 행정동 필터가 격자 축(grids.region_code)으로 걸리는지, UPLOADED 정렬과 타이브레이크가 결정적인지,
 * limit 생략이 regionCode 유무에 따라 30/무제한으로 갈리는지, 커버 duration 이 READY 게이트 밖인지를 검증한다.
 * 무파라미터 조합(전국·수집 시각순·30)의 회귀 고정(FR-7)도 여기서 한 번 더 못박는다 —
 * 나머지 153 계약(필드 매핑·geospatial 0)은 CollectionGridsRepositoryTest 담당이다.
 *
 * 격리(공유 로컬 DB): 99955 대역 합성 region_code + 서해 공해상 grid(GY 17521) + @Transactional 롤백.
 * regions/grids/videos/user_grids truncate 금지, 단언은 전부 자기 유저 기준.
 */
// 검증: FR-COLLECT-08, FR-MAP-10
@SpringBootTest
@Transactional
@DisplayName("UserGridRepository 갤러리 격자 목록 확장 파라미터 (실 PostGIS)")
class CollectionGridsFilterRepositoryTest {

	private static final long GY0 = 17521L;
	private static final long GX0 = 7911L;
	private static final String REGION_A = "9995500001";
	private static final String REGION_B = "9995500002";
	private static final String REGION_MISSING = "9995599999";
	private static final LocalDateTime BASE = LocalDateTime.of(2026, 7, 20, 12, 0, 0);

	@Autowired
	private UserGridRepository userGridRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RegionRepository regionRepository;

	@Autowired
	private EntityManager em;

	// 검증: FR-COLLECT-08
	@Test
	@DisplayName("무파라미터 조합은 기존과 같이 수집 시각순 최대 30개를 반환한다")
	void 무파라미터_조합은_기존과_같이_수집_시각순_최대_30개를_반환한다() {
		long me = newUser();
		// i=0 이 가장 최근(BASE), i=30 이 가장 오래됨. 31개 중 상위 30만, 그것도 수집 시각순으로 나와야 한다.
		String newest = null;
		String second = null;
		String oldest = null;
		for (int i = 0; i <= 30; i++) {
			String gridId = grid(i);
			occupy(me, gridId, BASE.minusMinutes(i), BASE.minusMinutes(i), 1, null);
			if (i == 0) {
				newest = gridId;
			}
			if (i == 1) {
				second = gridId;
			}
			if (i == 30) {
				oldest = gridId;
			}
		}

		List<CollectionGridProjection> result = collect(me, null, "COLLECTED", 30);

		assertThat(result).hasSize(30);
		assertThat(result).extracting(CollectionGridProjection::getGridId)
			.startsWith(newest, second)
			.doesNotContain(oldest);
	}

	@Test
	@DisplayName("regionCode를 지정하면 그 행정동 격자만 반환한다")
	void regionCode를_지정하면_그_행정동_격자만_반환한다() {
		long me = newUser();
		seedRegions();
		String inA = labeledGrid(0);
		String inB = labeledGrid(50);
		occupy(me, inA, BASE, BASE, 1, null);
		occupy(me, inB, BASE, BASE, 1, null);

		assertThat(collect(me, REGION_A, "COLLECTED", null))
			.extracting(CollectionGridProjection::getGridId).containsExactly(inA);
		assertThat(collect(me, REGION_B, "COLLECTED", null))
			.extracting(CollectionGridProjection::getGridId).containsExactly(inB);
	}

	@Test
	@DisplayName("귀속은 격자 소속 행정동 기준이다")
	void 귀속은_격자_소속_행정동_기준이다() {
		long me = newUser();
		seedRegions();
		String inA = labeledGrid(0);
		// 커버 영상 좌표는 옆 동(B) 안이지만 격자는 A 로 라벨돼 있다 — 격자 축이라 A 로 잡혀야 한다(FR-5).
		long cover = seedVideo(me, inA, "thumbs/m388-nextdoor.jpg", "READY", 11, center(50));
		occupy(me, inA, BASE, BASE, 1, cover);

		assertThat(collect(me, REGION_A, "COLLECTED", null))
			.extracting(CollectionGridProjection::getGridId).containsExactly(inA);
		assertThat(collect(me, REGION_B, "COLLECTED", null)).isEmpty();
	}

	@Test
	@DisplayName("UPLOADED 정렬은 최신 업로드순이고 동률은 grid_id 내림차순이다")
	void UPLOADED_정렬은_최신_업로드순이고_동률은_grid_id_내림차순이다() {
		long me = newUser();
		String first = grid(0);
		String second = grid(1);
		String third = grid(2);
		// 업로드 축과 수집 축을 어긋나게 둔다 — third 는 가장 최근에 수집됐지만 마지막 업로드는 가장 오래됐다.
		occupy(me, first, BASE.minusHours(2), BASE, 1, null);
		occupy(me, second, BASE.minusHours(1), BASE, 1, null);
		occupy(me, third, BASE, BASE.minusHours(1), 1, null);

		assertThat(collect(me, null, "UPLOADED", null))
			.extracting(CollectionGridProjection::getGridId)
			.containsExactly(second, first, third);   // 동률(first·second)은 grid_id 내림차순
		assertThat(collect(me, null, "COLLECTED", null))
			.extracting(CollectionGridProjection::getGridId)
			.containsExactly(third, second, first);
	}

	@Test
	@DisplayName("limit을 지정하면 그 수만 반환한다")
	void limit을_지정하면_그_수만_반환한다() {
		long me = newUser();
		occupy(me, grid(0), BASE.minusHours(2), BASE.minusHours(2), 1, null);
		occupy(me, grid(1), BASE.minusHours(1), BASE.minusHours(1), 1, null);
		String newest = grid(2);
		occupy(me, newest, BASE, BASE, 1, null);

		assertThat(collect(me, null, "COLLECTED", 1))
			.extracting(CollectionGridProjection::getGridId).containsExactly(newest);
	}

	@Test
	@DisplayName("regionCode 지정에 limit 생략이면 그 동네 전부를 반환한다")
	void regionCode_지정에_limit_생략이면_그_동네_전부를_반환한다() {
		long me = newUser();
		seedRegions();
		// 31개 — 기존 갤러리 상한 30을 넘겨야 "전부"가 상한 미적용임이 드러난다.
		for (int i = 0; i <= 30; i++) {
			occupy(me, labeledGrid(i), BASE.minusMinutes(i), BASE.minusMinutes(i), 1, null);
		}

		assertThat(collect(me, REGION_A, "COLLECTED", null)).hasSize(31);
	}

	@Test
	@DisplayName("미존재 regionCode는 빈 리스트다")
	void 미존재_regionCode는_빈_리스트다() {
		long me = newUser();
		seedRegions();
		occupy(me, labeledGrid(0), BASE, BASE, 1, null);

		assertThat(collect(me, REGION_MISSING, "COLLECTED", null)).isEmpty();
	}

	@Test
	@DisplayName("타인 격자는 regionCode가 같아도 반환되지 않는다")
	void 타인_격자는_regionCode가_같아도_반환되지_않는다() {
		long me = newUser();
		long other = newUser();
		seedRegions();
		String mine = labeledGrid(0);
		String theirs = labeledGrid(1);
		occupy(me, mine, BASE, BASE, 1, null);
		occupy(other, theirs, BASE, BASE, 9, null);
		occupy(other, mine, BASE, BASE, 9, null);

		assertThat(collect(me, REGION_A, "COLLECTED", null))
			.extracting(CollectionGridProjection::getGridId).containsExactly(mine);
	}

	@Test
	@DisplayName("커버가 READY 이전인 격자는 썸네일만 null이고 duration은 실린다")
	void 커버가_READY_이전인_격자는_썸네일만_null이고_duration은_실린다() {
		long me = newUser();
		String gridId = grid(0);
		// 교체·재인코딩 경계 재현 — pre-READY 행에 stale 썸네일이 남아 있어도 READY 게이트가 막는다.
		// duration 은 게이트 밖이라 인코딩 완료 전에도 뱃지 재료로 실린다(FR-8).
		long cover = seedVideo(me, gridId, "thumbs/m388-stale.jpg", "ENCODING", 17, center(0));
		occupy(me, gridId, BASE, BASE, 1, cover);

		CollectionGridProjection row = collect(me, null, "COLLECTED", 30).get(0);

		assertThat(row.getCoverVideoId()).isEqualTo(cover);
		assertThat(row.getCoverThumbnailKey()).isNull();
		assertThat(row.getCoverDurationSec()).isEqualTo(17);
	}

	@Test
	@DisplayName("커버가 없는 격자도 카드가 유지되고 커버 3필드만 null이다")
	void 커버가_없는_격자도_카드가_유지되고_커버_3필드만_null이다() {
		long me = newUser();
		String gridId = grid(0);
		// 블라인드 재선정에서 남은 ACTIVE 영상이 없어 cover 가 비워진 상태 — video_count 는 블라인드 포함이라
		// 0이 아니고, 점령 롤백이 일어나지 않아 카드는 남는다.
		occupy(me, gridId, BASE, BASE, 2, null);

		CollectionGridProjection row = collect(me, null, "COLLECTED", 30).get(0);

		assertThat(row.getGridId()).isEqualTo(gridId);
		assertThat(row.getVideoCount()).isEqualTo(2);
		assertThat(row.getCoverVideoId()).isNull();
		assertThat(row.getCoverThumbnailKey()).isNull();
		assertThat(row.getCoverDurationSec()).isNull();
	}

	private List<CollectionGridProjection> collect(long userId, String regionCode, String sort, Integer limit) {
		return userGridRepository.getCollectionGrids(userId, regionCode, sort, limit);
	}

	private long newUser() {
		String email = "m388-" + System.nanoTime() + "@example.com";
		return userRepository.save(User.createLocalUser(email, "hash", "m388도감")).getId();
	}

	/** 합성 행정동 둘 — A 는 세로로 긴 블록(31개 격자를 담아야 한다), B 는 그 북쪽에 떨어진 별개 동. */
	private void seedRegions() {
		regionRepository.upsert(REGION_A, "m388합성동A", REGION_A.substring(0, 5),
			cellBlockPolygonJson(GY0, GY0 + 40, GX0, GX0 + 2), CELL_AREA_M2);
		regionRepository.upsert(REGION_B, "m388합성동B", REGION_B.substring(0, 5),
			cellBlockPolygonJson(GY0 + 50, GY0 + 53, GX0, GX0 + 2), CELL_AREA_M2);
	}

	/** 라벨 없는 격자 — regionCode 무관 테스트용. dy 만큼 북쪽으로 옮겨 격자를 분리한다. */
	private String grid(long dy) {
		return GridFixtures.seedGrid(em, GY0 + dy, GX0);
	}

	/** 격자를 시드하고 중심점 판정(93/155/V5 동일 규칙)으로 라벨한다 — 저장 라벨 = by-grid 귀속. */
	private String labeledGrid(long dy) {
		String gridId = grid(dy);
		em.createNativeQuery("""
			UPDATE grids g SET region_code = (
				SELECT r.region_code FROM regions r
				WHERE ST_Covers(r.boundary_geom, g.center_geom)
				ORDER BY r.region_code
				LIMIT 1)
			WHERE grid_id = :g
			""").setParameter("g", gridId).executeUpdate();
		return gridId;
	}

	private GridPoint center(long dy) {
		return GridFixtures.pointAt(GY0 + dy + 0.5, GX0 + 0.5);
	}

	/** (userId, gridId) 점령 row — cover 는 nullable 이라 CAST 로 null 타입을 지정한다. */
	private void occupy(long userId, String gridId, LocalDateTime firstCollectedAt,
		LocalDateTime lastUploadedAt, int videoCount, Long coverVideoId) {
		em.createNativeQuery("""
			INSERT INTO user_grids (user_id, grid_id, first_collected_at, last_uploaded_at, video_count, cover_video_id)
			VALUES (:userId, :gridId, :firstCollectedAt, :lastUploadedAt, :videoCount, CAST(:coverVideoId AS bigint))
			""")
			.setParameter("userId", userId)
			.setParameter("gridId", gridId)
			.setParameter("firstCollectedAt", firstCollectedAt)
			.setParameter("lastUploadedAt", lastUploadedAt)
			.setParameter("videoCount", videoCount)
			.setParameter("coverVideoId", coverVideoId)
			.executeUpdate();
	}

	/**
	 * cover 후보 영상 하나를 삽입하고 id 를 돌려준다. geom NOT NULL 이라 좌표를 넣되 격자 귀속은 grid_id 로만
	 * 정해진다 — 옆 동 좌표를 넣어 격자 축 귀속(FR-5)을 검증할 수 있다.
	 */
	private long seedVideo(long userId, String gridId, String thumbnailKey, String processingStatus,
		int durationSec, GridPoint point) {
		String originalKey = "videos/original/m388-" + System.nanoTime() + ".mp4";
		em.createNativeQuery("""
			INSERT INTO videos (
				user_id, grid_id, original_s3_key, thumbnail_url, geom, duration_sec, recorded_at,
				processing_status, status
			)
			VALUES (
				:userId, :gridId, :originalKey, CAST(:thumbKey AS text),
				ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography,
				:durationSec, :recordedAt, :processingStatus, 'ACTIVE'
			)
			""")
			.setParameter("userId", userId)
			.setParameter("gridId", gridId)
			.setParameter("originalKey", originalKey)
			.setParameter("thumbKey", thumbnailKey)
			.setParameter("lon", point.lon())
			.setParameter("lat", point.lat())
			.setParameter("durationSec", durationSec)
			.setParameter("recordedAt", BASE)
			.setParameter("processingStatus", processingStatus)
			.executeUpdate();
		return ((Number) em.createNativeQuery("SELECT id FROM videos WHERE original_s3_key = :originalKey")
			.setParameter("originalKey", originalKey)
			.getSingleResult()).longValue();
	}
}
