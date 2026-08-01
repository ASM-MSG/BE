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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.region.repository.RegionRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;

/**
 * region_stats 트리거 배선 (MSG-155 모듈 3, 실 PostGIS). VideoServiceImpl 의 첫 점령/롤백 훅이
 * RegionStatsCommandService.refresh 를 정확히 한 번씩만 태우는지 업로드/삭제 경로로 검증한다.
 * 격리: 실데이터 region 과 안 겹치는 서해 공해상 격자 + 999 대역 합성 폴리곤 + @Transactional 롤백.
 */
@SpringBootTest
@Transactional
@DisplayName("region_stats 트리거 배선 (첫 점령/롤백 훅, 실 PostGIS)")
class VideoRegionStatsIntegrationTest {

	// 실데이터 행정동과 겹치지 않는 서해 공해상 격자(중심 ≈ 위도 36.0·경도 125.0), 서비스 범위 안.
	private static final long GY = 40000L;
	private static final long GX = 108695L;
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

	/** saveVideo 가 s3Key 실존을 headObject 로 확인한다(MSG-132) — 실제 S3 호출을 막는 스텁. */
	@MockitoBean
	private S3Client s3Client;

	private long userId;
	private String regionCode;
	private String gridId;

	@BeforeEach
	void setUp() {
		given(s3Client.headObject(any(HeadObjectRequest.class))).willReturn(HeadObjectResponse.builder().build());
		userId = userRepository.save(User.createLocalUser("region-stats@example.com", "hash", "수집자")).getId();
		regionCode = syntheticCode("155");
		seedRegion(regionCode);
		gridId = GridEncoder.encode(LAT, LON);
	}

	/** 테스트 격자 중심점을 덮는 합성 행정동(3×3 격자 블록)을 시드한다. */
	private void seedRegion(String code) {
		String polygon = rectanglePolygonJson(
			GX * GRID_LNG_STEP, GY * GRID_LAT_STEP,
			(GX + 3) * GRID_LNG_STEP, (GY + 3) * GRID_LAT_STEP);
		regionRepository.upsert(code, "합성동", code.substring(0, 5), polygon, CELL_AREA_M2);
	}

	private VideoUploadRequestDto request() {
		return new VideoUploadRequestDto(
			"videos/pending/" + userId + "/" + UUID.randomUUID() + ".mp4",
			LAT, LON, (short) 10, LocalDateTime.now(), "PRIVATE");
	}

	private long upload() {
		return videoService.saveVideo(userId, request()).videoId();
	}

	/** region_stats.collected_count — row 가 없으면 null. */
	private Integer collectedCount() {
		List<?> rows = em.createNativeQuery(
				"SELECT collected_count FROM region_stats WHERE user_id = :u AND region_code = :c")
			.setParameter("u", userId).setParameter("c", regionCode).getResultList();
		return rows.isEmpty() ? null : ((Number) rows.get(0)).intValue();
	}

	@Test
	@DisplayName("첫 영상 업로드로 점령하면 중심 행정동 region_stats 가 갱신된다")
	void 첫_영상_업로드로_점령하면_중심_행정동_region_stats가_갱신된다() {
		upload();

		assertThat(collectedCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("이미 점령한 격자에 재업로드하면 collected_count 가 증가하지 않는다")
	void 이미_점령한_격자에_재업로드하면_collected_count가_증가하지_않는다() {
		upload();
		upload();   // 재방문 — alreadyOccupied 라 refresh 훅이 안 돈다.

		assertThat(collectedCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("영상을 모두 삭제해 롤백되면 collected_count 가 감소한다")
	void 영상을_모두_삭제해_롤백되면_collected_count가_감소한다() {
		long videoId = upload();
		assertThat(collectedCount()).isEqualTo(1);

		videoService.deleteVideo(userId, videoId);

		// 마지막 점령 격자 롤백 → 0 으로 UPSERT (row 유지, §D5).
		assertThat(collectedCount()).isEqualTo(0);
	}

	@Test
	@DisplayName("격자에 영상이 남는 삭제는 collected_count 를 바꾸지 않는다")
	void 격자에_영상이_남는_삭제는_collected_count를_바꾸지_않는다() {
		long first = upload();
		upload();
		assertThat(collectedCount()).isEqualTo(1);

		videoService.deleteVideo(userId, first);   // 롤백 아님 — refresh 훅이 안 돈다.

		assertThat(collectedCount()).isEqualTo(1);
	}
}
