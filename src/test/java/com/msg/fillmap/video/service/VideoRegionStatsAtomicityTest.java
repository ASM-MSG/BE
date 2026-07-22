package com.msg.fillmap.video.service;

import static com.msg.fillmap.grid.GridConstants.GRID_LAT_STEP;
import static com.msg.fillmap.grid.GridConstants.GRID_LNG_STEP;
import static com.msg.fillmap.region.RegionTestFixtures.CELL_AREA_M2;
import static com.msg.fillmap.region.RegionTestFixtures.rectanglePolygonJson;
import static com.msg.fillmap.region.RegionTestFixtures.syntheticCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.region.repository.RegionRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;

/**
 * region_stats 갱신 원자성 (MSG-155 모듈 3 §D1, 실 PostGIS). refresh 가 점령과 같은 트랜잭션(REQUIRED)에서
 * 돌아 함께 커밋/롤백되는지를 실제 커밋 경계로 검증한다. 트랜잭션 안에서는 refresh 가 동기로 이미 썼고
 * (afterCommit·REQUIRES_NEW 가 아님), 그 트랜잭션을 롤백하면 region_stats 도 점령과 함께 사라진다.
 *
 * 롤백 관찰이 목적이라 @Transactional 롤백 격리를 못 쓴다 — region·user 는 커밋해 두고 saveVideo 만
 * TransactionTemplate 으로 롤백시킨다. 커밋한 합성 행(999 대역 region + 이 유저)은 @AfterEach 에서
 * 대상 지정 삭제로 걷어낸다(truncate 아님 — 실데이터 불가침).
 */
@SpringBootTest
@DisplayName("region_stats 갱신 원자성 (점령 트랜잭션과 동일 커밋 경계, 실 PostGIS)")
class VideoRegionStatsAtomicityTest {

	// VideoRegionStatsIntegrationTest 와 안 겹치는 별도 서해 공해상 격자(중심 ≈ 위도 36.0·경도 125.0).
	private static final long GY = 40000L;
	private static final long GX = 108700L;
	private static final double LAT = (GY + 0.5) * GRID_LAT_STEP;
	private static final double LON = (GX + 0.5) * GRID_LNG_STEP;

	@Autowired
	private VideoService videoService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RegionRepository regionRepository;

	@Autowired
	private EntityManager em;

	@Autowired
	private PlatformTransactionManager txManager;

	@MockitoBean
	private S3Client s3Client;

	private TransactionTemplate tx;
	private long userId;
	private String regionCode;
	private String gridId;

	@BeforeEach
	void setUp() {
		given(s3Client.headObject(any(HeadObjectRequest.class))).willReturn(HeadObjectResponse.builder().build());
		tx = new TransactionTemplate(txManager);
		regionCode = syntheticCode("156");
		gridId = GridEncoder.encode(LAT, LON);
		// region·user 를 먼저 커밋해 둔다 — saveVideo 롤백을 별도 트랜잭션에서 관찰하기 위해서다.
		tx.executeWithoutResult(status -> {
			userId = userRepository.save(User.createLocalUser("stats-atomic@example.com", "hash", "원자")).getId();
			String polygon = rectanglePolygonJson(
				GX * GRID_LNG_STEP, GY * GRID_LAT_STEP,
				(GX + 3) * GRID_LNG_STEP, (GY + 3) * GRID_LAT_STEP);
			regionRepository.upsert(regionCode, "합성동", regionCode.substring(0, 5), polygon, CELL_AREA_M2);
		});
	}

	@AfterEach
	void tearDown() {
		// 커밋해 둔 합성 행만 FK 역순으로 대상 지정 삭제한다(실데이터 불가침).
		tx.executeWithoutResult(status -> {
			exec("DELETE FROM region_stats WHERE user_id = :u", "u", userId);
			exec("DELETE FROM user_grids WHERE user_id = :u", "u", userId);
			exec("DELETE FROM videos WHERE user_id = :u", "u", userId);
			exec("DELETE FROM grids WHERE grid_id = :v", "v", gridId);
			exec("DELETE FROM regions WHERE region_code = :v", "v", regionCode);
			exec("DELETE FROM users WHERE id = :u", "u", userId);
		});
	}

	private void exec(String sql, String param, Object value) {
		em.createNativeQuery(sql).setParameter(param, value).executeUpdate();
	}

	private VideoUploadRequestDto request() {
		return new VideoUploadRequestDto(
			"videos/pending/" + userId + "/" + UUID.randomUUID() + ".mp4",
			LAT, LON, (short) 10, LocalDateTime.now());
	}

	private Integer collectedCount() {
		List<?> rows = em.createNativeQuery(
				"SELECT collected_count FROM region_stats WHERE user_id = :u AND region_code = :c")
			.setParameter("u", userId).setParameter("c", regionCode).getResultList();
		return rows.isEmpty() ? null : ((Number) rows.get(0)).intValue();
	}

	private boolean userGridExists() {
		Number count = (Number) em.createNativeQuery(
				"SELECT count(*) FROM user_grids WHERE user_id = :u AND grid_id = :g")
			.setParameter("u", userId).setParameter("g", gridId).getSingleResult();
		return count.longValue() > 0;
	}

	@Test
	@DisplayName("region_stats 갱신은 점령 트랜잭션과 같은 트랜잭션에서 커밋된다")
	void region_stats_갱신은_점령_트랜잭션과_같은_트랜잭션에서_커밋된다() {
		tx.executeWithoutResult(status -> {
			videoService.saveVideo(userId, request());
			// 같은 트랜잭션 안: refresh 가 동기로 region_stats 를 이미 썼다(afterCommit/REQUIRES_NEW 아님).
			assertThat(collectedCount()).isEqualTo(1);
			status.setRollbackOnly();   // 점령 트랜잭션을 실패시킨다.
		});

		// 롤백 후 새 트랜잭션: region_stats 도 점령(user_grids)과 함께 사라졌다 — 원자적.
		tx.executeWithoutResult(status -> {
			assertThat(collectedCount()).isNull();
			assertThat(userGridExists()).isFalse();
		});
	}
}
