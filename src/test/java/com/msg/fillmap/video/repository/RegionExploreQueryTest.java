package com.msg.fillmap.video.repository;

import static com.msg.fillmap.region.RegionTestFixtures.CELL_AREA_M2;
import static com.msg.fillmap.region.RegionTestFixtures.cellBlockPolygonJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

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
import com.msg.fillmap.video.entity.Video;

/**
 * 전역 탐색 native 3종 — 격자 카드(인기/최신)·헤더 카운트·전체 지역 (실 PostGIS · MSG-238 · Owner B).
 * 카운트·카드 포함 기준이 전역 노출 게이트(ACTIVE·PUBLIC·READY) 단일 정의인지(§D1), 카드 커버가
 * findGlobalCover(87) 3키 규칙과 같은 영상인지(§D7), 정렬 2종이 결정적인지(§D3), 격자 축 귀속(167 계승)·
 * 무라벨 제외·limit·geospatial 0 을 검증한다. HTTP 200/400/401·presign 은 서비스·컨트롤러 테스트 담당.
 *
 * 격리(공유 로컬 DB): 99954 대역 합성 region_code + grid_y 39800 대역 합성 grid(직접 지정 라벨 —
 * 판정 경로는 167 이 고정, 본 티켓은 소비만) + @Transactional 롤백. regions/grids/videos truncate 금지,
 * 전체 지역 단언은 합성 region_code 행만 필터한다(타 데이터 혼입 무관).
 */
@SpringBootTest
@Transactional
@DisplayName("VideoRepository 전역 탐색 조회 (격자 카드·헤더 카운트·전체 지역, 실 PostGIS)")
class RegionExploreQueryTest {

	private static final long GY0 = 17608L;
	private static final long GX0 = 7850L;
	private static final String REGION_A = "9995400001";
	private static final String REGION_B = "9995400002";
	private static final String REGION_C = "9995400003";
	private static final LocalDateTime BASE = LocalDateTime.of(2026, 7, 20, 12, 0, 0);

	@Autowired
	private VideoRepository videoRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RegionRepository regionRepository;

	@Autowired
	private EntityManager em;

	/** 격자 (GY0+gyOffset, GX0) 대역을 덮는 합성 행정동(3×3 블록)을 upsert 한다. */
	private void seedRegion(String code, String name, long gyOffset) {
		String polygon = cellBlockPolygonJson(GY0 + gyOffset, GY0 + gyOffset + 3, GX0, GX0 + 3);
		regionRepository.upsert(code, name, code.substring(0, 5), polygon, CELL_AREA_M2);
	}

	private void seedRegionA() {
		seedRegion(REGION_A, "m238합성동A", 0);
	}

	private void seedRegionB() {
		seedRegion(REGION_B, "m238합성동B", 10);
	}

	private void seedRegionC() {
		seedRegion(REGION_C, "m238합성동C", 20);
	}

	/**
	 * (gy,gx) 격자를 시드하고 region_code 를 직접 지정해 라벨한다 — 본 티켓은 라벨 소비 전용이라
	 * ST_Covers 판정 픽스처가 불요하다(판정 경로는 GridRegionCodeLabelTest·167 이 고정).
	 */
	private String seedLabeledGrid(long gy, long gx, String regionCode) {
		String gridId = GridFixtures.seedGrid(em, gy, gx);
		em.createNativeQuery("UPDATE grids SET region_code = :code WHERE grid_id = :g")
			.setParameter("code", regionCode)
			.setParameter("g", gridId)
			.executeUpdate();
		return gridId;
	}

	private long newUser() {
		String email = "m238-" + System.nanoTime() + "@example.com";
		return userRepository.save(User.createLocalUser(email, "hash", "m238탐색")).getId();
	}

	/**
	 * 영상 한 건을 지정 상태·조회수·created_at 로 삽입하고 id 를 돌려준다. geom NOT NULL 이라 좌표를 넣되,
	 * 격자 귀속은 grid_id 로만 정해진다(geom 은 탐색 쿼리에 무관 — 격자 축 귀속 검증용으로 옆 동 좌표를 넣을 수 있다).
	 */
	private long seedVideo(long userId, String gridId, String thumbnailKey, String processingStatus,
		String visibility, String status, long viewCount, GridPoint point, LocalDateTime createdAt) {
		String originalKey = "videos/original/m238-" + UUID.randomUUID() + ".mp4";
		em.createNativeQuery("""
			INSERT INTO videos (
				user_id, grid_id, original_s3_key, thumbnail_url, geom, duration_sec,
				recorded_at, created_at, processing_status, visibility, status, view_count
			)
			VALUES (
				:userId, :gridId, :originalKey, CAST(:thumbKey AS text),
				ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography,
				10, :createdAt, :createdAt, :processingStatus, :visibility, :status, :viewCount
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
			.setParameter("viewCount", viewCount)
			.executeUpdate();
		return ((Number) em.createNativeQuery("SELECT id FROM videos WHERE original_s3_key = :originalKey")
			.setParameter("originalKey", originalKey)
			.getSingleResult()).longValue();
	}

	/** 전역 노출 게이트(READY·PUBLIC·ACTIVE)를 통과하는 영상 — 좌표는 region A 격자 중심점. */
	private long gated(long userId, String gridId, String thumbnailKey, long viewCount, LocalDateTime createdAt) {
		return seedVideo(userId, gridId, thumbnailKey, "READY", "PUBLIC", "ACTIVE", viewCount,
			GridFixtures.pointAt(GY0 + 0.5, GX0 + 0.5), createdAt);
	}

	private static LocalDateTime at(int hour) {
		return LocalDateTime.of(2026, 7, 20, hour, 0, 0);
	}

	private void occupy(long userId, String gridId, LocalDateTime lastUploadedAt) {
		em.createNativeQuery("""
			INSERT INTO user_grids (
				user_id, grid_id, first_collected_at, last_uploaded_at, video_count)
			VALUES (:userId, :gridId, :uploadedAt, :uploadedAt, 1)
			""")
			.setParameter("userId", userId)
			.setParameter("gridId", gridId)
			.setParameter("uploadedAt", lastUploadedAt)
			.executeUpdate();
	}

	/* ---------- 모듈 1: 격자 카드 ---------- */

	@Test
	@DisplayName("공개_READY_영상이_있는_격자만_카드로_반환한다")
	void 공개_READY_영상이_있는_격자만_카드로_반환한다() {
		long me = newUser();
		seedRegionA();
		String withVideo = seedLabeledGrid(GY0, GX0, REGION_A);
		seedLabeledGrid(GY0, GX0 + 1, REGION_A);   // 영상 없는 격자 — 카드 제외
		gated(me, withVideo, "thumbs/m238-a.jpg", 5L, BASE);

		List<ExploreGridProjection> cards = videoRepository.findExploreGridsPopular(REGION_A, null);

		assertThat(cards).hasSize(1);
		ExploreGridProjection card = cards.get(0);
		assertThat(card.getGridId()).isEqualTo(withVideo);
		assertThat(card.getGridY()).isEqualTo(GY0);
		assertThat(card.getGridX()).isEqualTo(GX0);
		assertThat(card.getVideoCount()).isEqualTo(1);
		assertThat(card.getCoverThumbnailKey()).isEqualTo("thumbs/m238-a.jpg");
		assertThat(card.getCoverDurationSec()).isEqualTo((short) 10);
	}

	// 검증: FR-VIDEO-17
	@Test
	@DisplayName("PRIVATE_영상만_있는_격자는_카드에_나오지_않는다")
	void PRIVATE_영상만_있는_격자는_카드에_나오지_않는다() {
		long me = newUser();
		seedRegionA();
		String grid = seedLabeledGrid(GY0, GX0, REGION_A);
		// 내 것이어도 전역 게이트 — PRIVATE 는 전역 탐색에 안 잡힌다(87/237 전역 축 동일).
		seedVideo(me, grid, "thumbs/m238-private.jpg", "READY", "PRIVATE", "ACTIVE", 99L,
			GridFixtures.pointAt(GY0 + 0.5, GX0 + 0.5), BASE);

		assertThat(videoRepository.findExploreGridsPopular(REGION_A, null)).isEmpty();
	}

	@Test
	@DisplayName("인코딩_중_영상만_있는_격자는_카드에_나오지_않는다")
	void 인코딩_중_영상만_있는_격자는_카드에_나오지_않는다() {
		long me = newUser();
		seedRegionA();
		String grid = seedLabeledGrid(GY0, GX0, REGION_A);
		// READY MUST — 162 §도메인3. 인코딩 중(PUBLIC 이어도)은 노출 불가.
		seedVideo(me, grid, null, "ENCODING", "PUBLIC", "ACTIVE", 99L,
			GridFixtures.pointAt(GY0 + 0.5, GX0 + 0.5), BASE);

		assertThat(videoRepository.findExploreGridsPopular(REGION_A, null)).isEmpty();
	}

	@Test
	@DisplayName("삭제된_영상은_격자_영상수에_집계되지_않는다")
	void 삭제된_영상은_격자_영상수에_집계되지_않는다() {
		long me = newUser();
		seedRegionA();
		String grid = seedLabeledGrid(GY0, GX0, REGION_A);
		gated(me, grid, "thumbs/m238-alive.jpg", 5L, BASE);
		seedVideo(me, grid, "thumbs/m238-deleted.jpg", "READY", "PUBLIC", "DELETED", 99L,
			GridFixtures.pointAt(GY0 + 0.5, GX0 + 0.5), BASE);

		List<ExploreGridProjection> cards = videoRepository.findExploreGridsPopular(REGION_A, null);

		assertThat(cards).hasSize(1);
		assertThat(cards.get(0).getVideoCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("카드의_커버는_그_격자_findGlobalCover와_같은_영상이다")
	void 카드의_커버는_그_격자_findGlobalCover와_같은_영상이다() {
		long me = newUser();
		seedRegionA();
		String grid = seedLabeledGrid(GY0, GX0, REGION_A);
		gated(me, grid, "thumbs/m238-low.jpg", 10L, at(9));
		gated(me, grid, "thumbs/m238-tie1.jpg", 99L, at(8));
		long tieLater = gated(me, grid, "thumbs/m238-tie2.jpg", 99L, at(8));   // (조회수·시각) 완전 동률 — id 3키만이 가른다

		Video cover = videoRepository.findGlobalCover(grid).orElseThrow();
		ExploreGridProjection card = videoRepository.findExploreGridsPopular(REGION_A, null).get(0);

		// §D7: 동률 그룹에서도 카드 커버 = findGlobalCover — 둘 다 id DESC 3키라 항상 같은 영상이다.
		assertThat(cover.getId()).isEqualTo(tieLater);
		assertThat(card.getCoverThumbnailKey()).isEqualTo(cover.getThumbnailUrl());
		assertThat(card.getCoverDurationSec()).isEqualTo(cover.getDurationSec());
	}

	@Test
	@DisplayName("인기순은_조회수_합_내림차순이고_동률이면_영상수_격자ID_내림차순으로_결정적이다")
	void 인기순은_조회수_합_내림차순이고_동률이면_영상수_격자ID_내림차순으로_결정적이다() {
		long me = newUser();
		seedRegionA();
		String top = seedLabeledGrid(GY0, GX0, REGION_A);           // 합 100
		String moreVideos = seedLabeledGrid(GY0, GX0 + 1, REGION_A); // 합 50 · 2건
		String tieLow = seedLabeledGrid(GY0, GX0 + 2, REGION_A);     // 합 50 · 1건 · grid_id 낮음
		String tieHigh = seedLabeledGrid(GY0, GX0 + 3, REGION_A);    // 합 50 · 1건 · grid_id 높음
		gated(me, top, "thumbs/m238-p1.jpg", 100L, BASE);
		gated(me, moreVideos, "thumbs/m238-p2.jpg", 25L, BASE);
		gated(me, moreVideos, "thumbs/m238-p3.jpg", 25L, BASE);
		gated(me, tieLow, "thumbs/m238-p4.jpg", 50L, BASE);
		gated(me, tieHigh, "thumbs/m238-p5.jpg", 50L, BASE);

		List<ExploreGridProjection> cards = videoRepository.findExploreGridsPopular(REGION_A, null);

		// 합 DESC → 동률은 영상 수 DESC → 다시 동률은 grid_id DESC — 요청마다 같은 순서.
		assertThat(cards).extracting(ExploreGridProjection::getGridId)
			.containsExactly(top, moreVideos, tieHigh, tieLow);
	}

	@Test
	@DisplayName("최신순은_격자의_최신_공개_영상_시각_내림차순이다")
	void 최신순은_격자의_최신_공개_영상_시각_내림차순이다() {
		long me = newUser();
		seedRegionA();
		String recent = seedLabeledGrid(GY0, GX0, REGION_A);
		String stale = seedLabeledGrid(GY0, GX0 + 1, REGION_A);
		gated(me, recent, "thumbs/m238-l1.jpg", 0L, at(8));
		gated(me, recent, "thumbs/m238-l2.jpg", 0L, at(12));   // recent 의 최신 공개 = 12시
		gated(me, stale, "thumbs/m238-l3.jpg", 99L, at(11));   // stale 의 최신 공개 = 11시 (조회수 무관)
		// 게이트 밖(PRIVATE) 최신 영상은 MAX 에 안 잡힌다 — stale 이 앞서지 않는다.
		seedVideo(me, stale, "thumbs/m238-l4.jpg", "READY", "PRIVATE", "ACTIVE", 0L,
			GridFixtures.pointAt(GY0 + 0.5, GX0 + 0.5), at(13));

		List<ExploreGridProjection> cards = videoRepository.findExploreGridsLatest(REGION_A, null);

		assertThat(cards).extracting(ExploreGridProjection::getGridId).containsExactly(recent, stale);
	}

	@Test
	@DisplayName("영상_좌표가_옆_행정동이어도_격자_소속_행정동_탐색에_포함된다")
	void 영상_좌표가_옆_행정동이어도_격자_소속_행정동_탐색에_포함된다() {
		long me = newUser();
		seedRegionA();
		seedRegionB();
		String grid = seedLabeledGrid(GY0, GX0, REGION_A);
		// 영상 좌표는 옆 동(B) 안이지만 격자 라벨은 A — 격자 축이라 A 탐색에 잡혀야 한다(167 DoD 계승).
		seedVideo(me, grid, "thumbs/m238-nextdoor.jpg", "READY", "PUBLIC", "ACTIVE", 5L,
			GridFixtures.pointAt(GY0 + 11.5, GX0 + 1.5), BASE);

		assertThat(videoRepository.findExploreGridsPopular(REGION_A, null))
			.extracting(ExploreGridProjection::getGridId).containsExactly(grid);
		assertThat(videoRepository.findExploreGridsPopular(REGION_B, null)).isEmpty();
	}

	@Test
	@DisplayName("무라벨_격자는_어떤_행정동_탐색에도_포함되지_않는다")
	void 무라벨_격자는_어떤_행정동_탐색에도_포함되지_않는다() {
		long me = newUser();
		seedRegionA();
		String labeled = seedLabeledGrid(GY0, GX0, REGION_A);
		String coastal = GridFixtures.seedGrid(em, GY0, GX0 + 1);   // region_code NULL — 해안
		gated(me, labeled, "thumbs/m238-labeled.jpg", 5L, BASE);
		gated(me, coastal, "thumbs/m238-coastal.jpg", 99L, BASE);

		assertThat(videoRepository.findExploreGridsPopular(REGION_A, null))
			.extracting(ExploreGridProjection::getGridId).containsExactly(labeled);
	}

	@Test
	@DisplayName("limit을_지정하면_정렬_순서_앞에서_그만큼만_반환한다")
	void limit을_지정하면_정렬_순서_앞에서_그만큼만_반환한다() {
		long me = newUser();
		seedRegionA();
		String first = seedLabeledGrid(GY0, GX0, REGION_A);
		String second = seedLabeledGrid(GY0, GX0 + 1, REGION_A);
		String third = seedLabeledGrid(GY0, GX0 + 2, REGION_A);
		gated(me, first, "thumbs/m238-t1.jpg", 30L, BASE);
		gated(me, second, "thumbs/m238-t2.jpg", 20L, BASE);
		gated(me, third, "thumbs/m238-t3.jpg", 10L, BASE);

		List<ExploreGridProjection> cards = videoRepository.findExploreGridsPopular(REGION_A, 2);

		assertThat(cards).extracting(ExploreGridProjection::getGridId).containsExactly(first, second);
	}

	@Test
	@DisplayName("카드_조회_쿼리에_ST__geospatial_연산이_없다")
	void 카드_조회_쿼리에_ST__geospatial_연산이_없다() throws Exception {
		for (String name : List.of("findExploreGridsPopular", "findExploreGridsLatest")) {
			Method method = VideoRepository.class.getMethod(name, String.class, Integer.class);
			String sql = method.getAnnotation(Query.class).value();

			// 저장 라벨(grids.region_code) equi + 게이트 equi 만 — point-in-polygon 없음(성공 기준 9).
			assertThat(sql).doesNotContain("ST_");
			assertThat(sql).doesNotContainIgnoringCase("geom");
		}
	}

	/* ---------- 모듈 2: 헤더 카운트 (repository 절) ---------- */

	@Test
	@DisplayName("격자수는_카드_집합_크기와_같고_영상수는_카드_영상수_합과_같다")
	void 격자수는_카드_집합_크기와_같고_영상수는_카드_영상수_합과_같다() {
		long me = newUser();
		seedRegionA();
		String grid1 = seedLabeledGrid(GY0, GX0, REGION_A);
		String grid2 = seedLabeledGrid(GY0, GX0 + 1, REGION_A);
		String gatedOut = seedLabeledGrid(GY0, GX0 + 2, REGION_A);   // ENCODING 만 — 카드·카운트 둘 다 제외
		gated(me, grid1, "thumbs/m238-c1.jpg", 5L, BASE);
		gated(me, grid1, "thumbs/m238-c2.jpg", 3L, BASE);
		gated(me, grid2, "thumbs/m238-c3.jpg", 1L, BASE);
		seedVideo(me, grid1, "thumbs/m238-c4.jpg", "READY", "PRIVATE", "ACTIVE", 9L,
			GridFixtures.pointAt(GY0 + 0.5, GX0 + 0.5), BASE);
		seedVideo(me, gatedOut, null, "ENCODING", "PUBLIC", "ACTIVE", 0L,
			GridFixtures.pointAt(GY0 + 0.5, GX0 + 0.5), BASE);

		RegionExploreSummaryProjection summary = videoRepository.getRegionExploreSummary(REGION_A).orElseThrow();
		List<ExploreGridProjection> cards = videoRepository.findExploreGridsPopular(REGION_A, null);

		// §D1 단일 정의 — 패널 카운트 = 썸네일 뷰 카드 집합. 어긋나면 화면 간 숫자가 갈라진다(DoD).
		assertThat(summary.getGridCount()).isEqualTo(cards.size()).isEqualTo(2);
		assertThat(summary.getVideoCount())
			.isEqualTo(cards.stream().mapToLong(ExploreGridProjection::getVideoCount).sum())
			.isEqualTo(3L);
	}

	@Test
	@DisplayName("limit을_지정해도_격자수와_영상수는_전체_기준이다")
	void limit을_지정해도_격자수와_영상수는_전체_기준이다() {
		long me = newUser();
		seedRegionA();
		String grid1 = seedLabeledGrid(GY0, GX0, REGION_A);
		String grid2 = seedLabeledGrid(GY0, GX0 + 1, REGION_A);
		gated(me, grid1, "thumbs/m238-f1.jpg", 5L, BASE);
		gated(me, grid2, "thumbs/m238-f2.jpg", 3L, BASE);
		gated(me, grid2, "thumbs/m238-f3.jpg", 1L, BASE);

		RegionExploreSummaryProjection summary = videoRepository.getRegionExploreSummary(REGION_A).orElseThrow();

		// 패널(limit=1)이 카드 1장 + "격자 2 · 영상 3" 을 같은 정의로 얻는다(§D2 — 카운트는 limit 무관).
		assertThat(videoRepository.findExploreGridsPopular(REGION_A, 1)).hasSize(1);
		assertThat(summary.getGridCount()).isEqualTo(2);
		assertThat(summary.getVideoCount()).isEqualTo(3L);
	}

	@Test
	@DisplayName("실존_행정동에_콘텐츠가_없으면_regionName과_카운트_0_빈_배열이다")
	void 실존_행정동에_콘텐츠가_없으면_regionName과_카운트_0_빈_배열이다() {
		long me = newUser();
		seedRegionA();
		String grid = seedLabeledGrid(GY0, GX0, REGION_A);
		seedVideo(me, grid, "thumbs/m238-e1.jpg", "READY", "PRIVATE", "ACTIVE", 5L,
			GridFixtures.pointAt(GY0 + 0.5, GX0 + 0.5), BASE);

		RegionExploreSummaryProjection summary = videoRepository.getRegionExploreSummary(REGION_A).orElseThrow();

		// LEFT JOIN 이라 실존 region 은 콘텐츠 0 이어도 1행 — regionName 유지 + 0 합성(§D2, 미존재는 0행).
		assertThat(summary.getRegionName()).isEqualTo("m238합성동A");
		assertThat(summary.getGridCount()).isZero();
		assertThat(summary.getVideoCount()).isZero();
		assertThat(videoRepository.findExploreGridsPopular(REGION_A, null)).isEmpty();
	}

	/* ---------- 모듈 3: 전체 지역 ---------- */

	/** 공유 DB 의 타 데이터가 섞일 수 있어 합성(99954) region 행만 걸러 단언한다. */
	private List<RegionExplorePageProjection> syntheticRows(long userId) {
		return videoRepository.getRegionExplorePage(userId, false, false, null, null, null, 21).stream()
			.filter(row -> row.getRegionCode().startsWith("99954"))
			.toList();
	}

	@Test
	@DisplayName("게이트_통과_격자가_있는_행정동만_격자수_내림차순으로_반환한다")
	void 게이트_통과_격자가_있는_행정동만_격자수_내림차순으로_반환한다() {
		long me = newUser();
		seedRegionA();
		seedRegionB();
		seedRegionC();
		gated(me, seedLabeledGrid(GY0, GX0, REGION_A), "thumbs/m238-x1.jpg", 5L, BASE);
		gated(me, seedLabeledGrid(GY0, GX0 + 1, REGION_A), "thumbs/m238-x2.jpg", 3L, BASE);
		gated(me, seedLabeledGrid(GY0 + 10, GX0, REGION_B), "thumbs/m238-x3.jpg", 1L, BASE);
		seedVideo(me, seedLabeledGrid(GY0 + 20, GX0, REGION_C), "thumbs/m238-x4.jpg", "READY", "PRIVATE",
			"ACTIVE", 9L, GridFixtures.pointAt(GY0 + 0.5, GX0 + 0.5), BASE);

		List<RegionExplorePageProjection> rows = syntheticRows(me);

		// A(2) → B(1) 내림차순, C(비공개만)는 부재 — gridCount DESC 는 전역 정렬이라 상대 순서로 검증한다.
		assertThat(rows).extracting(RegionGridCountProjection::getRegionCode)
			.containsExactly(REGION_A, REGION_B);
		assertThat(rows).extracting(RegionGridCountProjection::getRegionName)
			.containsExactly("m238합성동A", "m238합성동B");
		assertThat(rows).extracting(RegionGridCountProjection::getGridCount).containsExactly(2, 1);
	}

	@Test
	@DisplayName("행정동별_격자수는_그_행정동_카드_집합_크기와_일치한다")
	void 행정동별_격자수는_그_행정동_카드_집합_크기와_일치한다() {
		long me = newUser();
		seedRegionA();
		gated(me, seedLabeledGrid(GY0, GX0, REGION_A), "thumbs/m238-y1.jpg", 5L, BASE);
		gated(me, seedLabeledGrid(GY0, GX0 + 1, REGION_A), "thumbs/m238-y2.jpg", 3L, BASE);
		seedVideo(me, seedLabeledGrid(GY0, GX0 + 2, REGION_A), "thumbs/m238-y3.jpg", "READY", "PRIVATE",
			"ACTIVE", 9L, GridFixtures.pointAt(GY0 + 0.5, GX0 + 0.5), BASE);

		RegionExplorePageProjection row = syntheticRows(me).get(0);

		// ①↔② 일관(DoD) — 전체 지역의 격자 수 = 그 행정동 카드 집합 크기(같은 게이트 정의 §D1).
		assertThat(row.getRegionCode()).isEqualTo(REGION_A);
		assertThat(row.getGridCount()).isEqualTo(videoRepository.findExploreGridsPopular(REGION_A, null).size());
	}

	@Test
	// 검증: FR-SEARCH-15
	@DisplayName("직접_최근_업로드한_지역이_격자수가_많은_미업로드_지역보다_먼저다")
	void 직접_최근_업로드한_지역이_격자수가_많은_미업로드_지역보다_먼저다() {
		long me = newUser();
		long other = newUser();
		seedRegionA();
		seedRegionB();
		String a1 = seedLabeledGrid(GY0, GX0, REGION_A);
		String a2 = seedLabeledGrid(GY0, GX0 + 1, REGION_A);
		String b1 = seedLabeledGrid(GY0 + 10, GX0, REGION_B);
		gated(other, a1, "thumbs/m460-a1.jpg", 1L, BASE);
		gated(other, a2, "thumbs/m460-a2.jpg", 1L, BASE);
		gated(other, b1, "thumbs/m460-b1.jpg", 1L, BASE);
		occupy(me, b1, BASE.plusDays(1));

		List<RegionExplorePageProjection> rows = syntheticRows(me);

		assertThat(rows).extracting(RegionExplorePageProjection::getRegionCode)
			.containsExactly(REGION_B, REGION_A);
		assertThat(rows.get(0).getPersonalLastUploadedAt()).isEqualTo(BASE.plusDays(1));
		assertThat(rows.get(1).getPersonalLastUploadedAt()).isNull();
	}

	@Test
	// 검증: FR-SEARCH-15
	@DisplayName("개인_지역_커서_다음에는_남은_비개인_지역을_격자수순으로_조회한다")
	void 개인_지역_커서_다음에는_남은_비개인_지역을_격자수순으로_조회한다() {
		long me = newUser();
		long other = newUser();
		seedRegionA();
		seedRegionB();
		seedRegionC();
		String a1 = seedLabeledGrid(GY0, GX0, REGION_A);
		String b1 = seedLabeledGrid(GY0 + 10, GX0, REGION_B);
		String c1 = seedLabeledGrid(GY0 + 20, GX0, REGION_C);
		gated(other, a1, "thumbs/m460-ca.jpg", 1L, BASE);
		gated(other, b1, "thumbs/m460-cb.jpg", 1L, BASE);
		gated(other, c1, "thumbs/m460-cc.jpg", 1L, BASE);
		occupy(me, a1, BASE.plusDays(1));

		RegionExplorePageProjection first = videoRepository.getRegionExplorePage(
			me, false, false, null, null, null, 1).get(0);
		List<RegionExplorePageProjection> next = videoRepository.getRegionExplorePage(
			me, true, true, first.getPersonalLastUploadedAt(), first.getGridCount(), first.getRegionCode(), 21)
			.stream().filter(row -> row.getRegionCode().startsWith("99954")).toList();

		assertThat(first.getRegionCode()).isEqualTo(REGION_A);
		assertThat(next).extracting(RegionExplorePageProjection::getRegionCode)
			.containsExactly(REGION_B, REGION_C);
	}

	@Test
	// 검증: FR-SEARCH-15
	@DisplayName("개인_지역끼리는_마지막_업로드_시각_내림차순이다")
	void 개인_지역끼리는_마지막_업로드_시각_내림차순이다() {
		long me = newUser();
		seedRegionA();
		seedRegionB();
		String a1 = seedLabeledGrid(GY0, GX0, REGION_A);
		String b1 = seedLabeledGrid(GY0 + 10, GX0, REGION_B);
		gated(me, a1, "thumbs/m460-ra.jpg", 1L, BASE);
		gated(me, b1, "thumbs/m460-rb.jpg", 1L, BASE);
		occupy(me, a1, BASE);
		occupy(me, b1, BASE.plusHours(1));

		assertThat(syntheticRows(me)).extracting(RegionExplorePageProjection::getRegionCode)
			.containsExactly(REGION_B, REGION_A);
	}

	@Test
	// 검증: FR-SEARCH-15
	@DisplayName("개인_업로드_시각_동률은_격자수와_행정동_코드로_고정한다")
	void 개인_업로드_시각_동률은_격자수와_행정동_코드로_고정한다() {
		long me = newUser();
		seedRegionA();
		seedRegionB();
		seedRegionC();
		String a1 = seedLabeledGrid(GY0, GX0, REGION_A);
		String a2 = seedLabeledGrid(GY0, GX0 + 1, REGION_A);
		String b1 = seedLabeledGrid(GY0 + 10, GX0, REGION_B);
		String c1 = seedLabeledGrid(GY0 + 20, GX0, REGION_C);
		for (String gridId : List.of(a1, a2, b1, c1)) {
			gated(me, gridId, "thumbs/m460-t-" + gridId + ".jpg", 1L, BASE);
		}
		occupy(me, a1, BASE);
		occupy(me, a2, BASE);
		occupy(me, b1, BASE);
		occupy(me, c1, BASE);

		assertThat(syntheticRows(me)).extracting(RegionExplorePageProjection::getRegionCode)
			.containsExactly(REGION_A, REGION_B, REGION_C);
	}

	@Test
	// 검증: FR-SEARCH-15
	@DisplayName("비개인_지역_커서_뒤는_격자수와_행정동_코드_경계로_이어진다")
	void 비개인_지역_커서_뒤는_격자수와_행정동_코드_경계로_이어진다() {
		long me = newUser();
		long other = newUser();
		seedRegionA();
		seedRegionB();
		seedRegionC();
		String a1 = seedLabeledGrid(GY0, GX0, REGION_A);
		String a2 = seedLabeledGrid(GY0, GX0 + 1, REGION_A);
		String b1 = seedLabeledGrid(GY0 + 10, GX0, REGION_B);
		String c1 = seedLabeledGrid(GY0 + 20, GX0, REGION_C);
		gated(other, a1, "thumbs/m460-na1.jpg", 1L, BASE);
		gated(other, a2, "thumbs/m460-na2.jpg", 1L, BASE);
		gated(other, b1, "thumbs/m460-nb.jpg", 1L, BASE);
		gated(other, c1, "thumbs/m460-nc.jpg", 1L, BASE);

		RegionExplorePageProjection first = videoRepository.getRegionExplorePage(
			me, false, false, null, null, null, 1).get(0);
		List<RegionExplorePageProjection> next = videoRepository.getRegionExplorePage(
			me, true, false, null, first.getGridCount(), first.getRegionCode(), 21)
			.stream().filter(row -> row.getRegionCode().startsWith("99954")).toList();

		assertThat(first.getRegionCode()).isEqualTo(REGION_A);
		assertThat(next).extracting(RegionExplorePageProjection::getRegionCode)
			.containsExactly(REGION_B, REGION_C);
	}

	// 검증: FR-VIDEO-17
	@Test
	@DisplayName("비공개_영상만_있는_행정동은_리스트에_나오지_않는다")
	void 비공개_영상만_있는_행정동은_리스트에_나오지_않는다() {
		long me = newUser();
		seedRegionC();
		seedVideo(me, seedLabeledGrid(GY0 + 20, GX0, REGION_C), "thumbs/m238-z1.jpg", "READY", "PRIVATE",
			"ACTIVE", 9L, GridFixtures.pointAt(GY0 + 0.5, GX0 + 0.5), BASE);

		assertThat(syntheticRows(me)).isEmpty();
	}

	@Test
	@DisplayName("region_code가_NULL인_격자는_전체_지역_목록에_영향을_주지_않는다")
	void region_code가_NULL인_격자는_전체_지역_목록에_영향을_주지_않는다() {
		long me = newUser();
		seedRegionA();
		String labeled = seedLabeledGrid(GY0, GX0, REGION_A);
		String coastal = GridFixtures.seedGrid(em, GY0, GX0 + 1);   // region_code NULL — 해안
		gated(me, labeled, "thumbs/m461-labeled.jpg", 5L, BASE);
		gated(me, coastal, "thumbs/m461-coastal.jpg", 9L, BASE);

		List<RegionExplorePageProjection> rows = syntheticRows(me);

		// MSG-461 이 regions 조인을 집계 뒤로 옮겼지만 INNER 조인이라 무라벨 격자는 그대로 탈락한다.
		// 행으로도 안 나오고(무라벨은 귀속 행정동이 없다), A 의 gridCount 도 안 올린다(1 이지 2 가 아니다).
		// grids.region_code 에 regions FK 가 걸려 있어(V5__grids_region_code.sql:10) regions 에 없는
		// 코드를 가진 격자는 애초에 만들 수 없다 — NULL 이 이 성질을 검증할 수 있는 유일한 입력이다.
		assertThat(rows).extracting(RegionExplorePageProjection::getRegionCode).containsExactly(REGION_A);
		assertThat(rows).extracting(RegionExplorePageProjection::getGridCount).containsExactly(1);
	}

	@Test
	@DisplayName("전체_지역_쿼리에_ST__geospatial_연산이_없다")
	void 전체_지역_쿼리에_ST__geospatial_연산이_없다() throws Exception {
		String listSql = VideoRepository.class.getMethod("getRegionExplorePage", long.class, boolean.class,
			boolean.class, LocalDateTime.class, Integer.class, String.class, int.class)
			.getAnnotation(Query.class).value();
		String summarySql = VideoRepository.class.getMethod("getRegionExploreSummary", String.class)
			.getAnnotation(Query.class).value();

		// 헤더 카운트까지 — 세 쿼리 전부 equi/btree 만(성공 기준 9).
		assertThat(listSql).doesNotContain("ST_");
		assertThat(listSql).doesNotContainIgnoringCase("geom");
		assertThat(summarySql).doesNotContain("ST_");
		assertThat(summarySql).doesNotContainIgnoringCase("geom");
	}
}
