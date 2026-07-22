package com.msg.fillmap.usergrid.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 갤러리 격자 목록 native 조회 (실 PostGIS, MSG-153 모듈 1). 각 테스트는 자기 유저를 새로 만들고
 * @Transactional 로 롤백한다 — 공유 로컬 DB 오염 방지. 합성 격자는 서해 공해상 기준점(region 무귀속)이라
 * regions·region_stats 를 건드리지 않는다. 단언은 전부 자기 유저 기준으로만 한다.
 */
@SpringBootTest
@Transactional
@DisplayName("UserGridRepository 갤러리 격자 목록 (실 PostGIS)")
class CollectionGridsRepositoryTest {

	// 서해 공해상 — 어떤 행정동에도 안 속하는 기준점. 실제 grid_y/grid_x 는 GridEncoder 로 산출한다.
	private static final double SEA_LAT = 36.0;
	private static final double SEA_LON = 124.0;

	private static final LocalDateTime BASE = LocalDateTime.of(2026, 7, 20, 12, 0, 0);

	@Autowired
	private UserGridRepository userGridRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	private long baseY;
	private long baseX;

	@BeforeEach
	void setUp() {
		GridIndex base = GridEncoder.decode(GridEncoder.encode(SEA_LAT, SEA_LON));
		baseY = base.gridY();
		baseX = base.gridX();
	}

	@Test
	@DisplayName("점령 격자를 first_collected_at 내림차순으로 반환한다")
	void 점령_격자를_first_collected_at_내림차순으로_반환한다() {
		long me = newUser();
		String older = grid(0, 0);
		String middle = grid(0, 1);
		String newer = grid(0, 2);
		occupy(me, older, BASE.minusHours(2), BASE.minusHours(2), 1, null);
		occupy(me, newer, BASE, BASE, 1, null);
		occupy(me, middle, BASE.minusHours(1), BASE.minusHours(1), 1, null);

		List<CollectionGridProjection> result = userGridRepository.getCollectionGrids(me);

		assertThat(result).extracting(CollectionGridProjection::getGridId)
			.containsExactly(newer, middle, older);
	}

	@Test
	@DisplayName("점령이 30개를 넘으면 최근 수집 30개만 반환한다")
	void 점령이_30개를_넘으면_최근_수집_30개만_반환한다() {
		long me = newUser();
		// i=0 이 가장 최근(BASE), i=30 이 가장 오래됨. 31개 중 상위 30만 나와야 한다.
		String oldest = null;
		String newest = null;
		for (int i = 0; i <= 30; i++) {
			String gridId = grid(0, i);
			occupy(me, gridId, BASE.minusMinutes(i), BASE.minusMinutes(i), 1, null);
			if (i == 0) {
				newest = gridId;
			}
			if (i == 30) {
				oldest = gridId;
			}
		}

		List<CollectionGridProjection> result = userGridRepository.getCollectionGrids(me);

		assertThat(result).hasSize(30);
		assertThat(result.get(0).getGridId()).isEqualTo(newest);
		assertThat(result).extracting(CollectionGridProjection::getGridId).doesNotContain(oldest);
	}

	@Test
	@DisplayName("cover_video_id가 null이면 coverVideoId와 coverThumbnailKey가 null이다")
	void cover_video_id가_null이면_coverVideoId와_coverThumbnailKey가_null이다() {
		long me = newUser();
		occupy(me, grid(0, 0), BASE, BASE, 1, null);

		CollectionGridProjection row = userGridRepository.getCollectionGrids(me).get(0);

		assertThat(row.getCoverVideoId()).isNull();
		assertThat(row.getCoverThumbnailKey()).isNull();
	}

	@Test
	@DisplayName("cover영상이 READY 이전이면 coverVideoId와 coverThumbnailKey가 둘 다 null이다")
	void cover영상이_READY_이전이면_coverVideoId와_coverThumbnailKey가_둘_다_null이다() {
		long me = newUser();
		String gridId = grid(0, 0);
		long coverId = seedVideo(me, gridId, null, "ENCODING");   // 인코딩 중 — READY 이전
		occupy(me, gridId, BASE, BASE, 1, coverId);

		CollectionGridProjection row = userGridRepository.getCollectionGrids(me).get(0);

		assertThat(row.getCoverVideoId()).isNull();
		assertThat(row.getCoverThumbnailKey()).isNull();
	}

	@Test
	@DisplayName("점령이 없으면 빈 리스트를 반환한다")
	void 점령이_없으면_빈_리스트를_반환한다() {
		long me = newUser();

		List<CollectionGridProjection> result = userGridRepository.getCollectionGrids(me);

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("다른 사용자의 점령은 섞이지 않는다")
	void 다른_사용자의_점령은_섞이지_않는다() {
		long me = newUser();
		long other = newUser();
		String mine = grid(0, 0);
		String theirs = grid(0, 1);
		occupy(me, mine, BASE, BASE, 1, null);
		occupy(other, mine, BASE, BASE, 9, null);
		occupy(other, theirs, BASE, BASE, 9, null);

		List<CollectionGridProjection> result = userGridRepository.getCollectionGrids(me);

		assertThat(result).extracting(CollectionGridProjection::getGridId).containsExactly(mine);
	}

	@Test
	@DisplayName("videoCount와 lastUploadedAt이 user_grids 값과 일치한다")
	void videoCount와_lastUploadedAt이_user_grids_값과_일치한다() {
		long me = newUser();
		LocalDateTime lastUploaded = BASE.plusDays(1);
		occupy(me, grid(0, 0), BASE, lastUploaded, 7, null);

		CollectionGridProjection row = userGridRepository.getCollectionGrids(me).get(0);

		assertThat(row.getVideoCount()).isEqualTo(7);
		assertThat(row.getLastUploadedAt()).isEqualTo(lastUploaded);
	}

	@Test
	@DisplayName("목록 조회에 geospatial 연산이 없다")
	void 목록_조회에_geospatial_연산이_없다() throws Exception {
		Method method = UserGridRepository.class.getMethod("getCollectionGrids", long.class);
		String sql = method.getAnnotation(Query.class).value();

		// grids 미조인·point-in-polygon 없음(성공 기준 8) — ST_* 함수도 geom 컬럼도 쓰지 않는다.
		assertThat(sql).doesNotContain("ST_");
		assertThat(sql).doesNotContainIgnoringCase("geom");
	}

	private long newUser() {
		String email = "m153b-" + System.nanoTime() + "@example.com";
		return userRepository.save(User.createLocalUser(email, "hash", "m153b도감")).getId();
	}

	private String grid(long dy, long dx) {
		return GridFixtures.seedGrid(em, baseY + dy, baseX + dx);
	}

	/**
	 * (userId, gridId) 점령 row 를 first_collected_at·last_uploaded_at·cover_video_id 지정으로 삽입한다.
	 * cover 는 nullable 이라 CAST 로 null 타입을 지정한다.
	 */
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
	 * cover 후보 영상 하나를 삽입하고 id 를 돌려준다 (기본 READY — cover 는 READY 게이트를 지나야 노출).
	 * geom NOT NULL 이라 서해 공해상 좌표를 넣는다. 삽입 후 유니크한 original_s3_key 로 id 를 되읽는다.
	 */
	private long seedVideo(long userId, String gridId, String thumbnailKey) {
		return seedVideo(userId, gridId, thumbnailKey, "READY");
	}

	private long seedVideo(long userId, String gridId, String thumbnailKey, String processingStatus) {
		String originalKey = "videos/original/m153b-" + System.nanoTime() + ".mp4";
		em.createNativeQuery("""
			INSERT INTO videos (
				user_id, grid_id, original_s3_key, thumbnail_url, geom, duration_sec, recorded_at, processing_status, status
			)
			VALUES (
				:userId, :gridId, :originalKey, CAST(:thumbKey AS text),
				ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography,
				10, now(), :processingStatus, 'ACTIVE'
			)
			""")
			.setParameter("userId", userId)
			.setParameter("gridId", gridId)
			.setParameter("originalKey", originalKey)
			.setParameter("thumbKey", thumbnailKey)
			.setParameter("processingStatus", processingStatus)
			.setParameter("lon", SEA_LON)
			.setParameter("lat", SEA_LAT)
			.executeUpdate();
		return ((Number) em.createNativeQuery("SELECT id FROM videos WHERE original_s3_key = :originalKey")
			.setParameter("originalKey", originalKey)
			.getSingleResult()).longValue();
	}
}
