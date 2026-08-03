package com.msg.fillmap.badge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import com.msg.fillmap.badge.dto.EarnedBadgeResponseDto;
import com.msg.fillmap.badge.entity.BadgeConditionType;
import com.msg.fillmap.badge.repository.UserBadgeRepository;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.dto.VideoUploadResponseDto;
import com.msg.fillmap.video.service.VideoService;

/**
 * 지급 엔진·업로드 훅·응답 동봉 통합 검증 (실 PostGIS, MSG-239 모듈 2). 공유 로컬 DB — 합성 유저만
 * 만들고 @Transactional 롤백, badges 마스터(V9)는 code 기준 단언만 한다. 지역 뱃지 시나리오는 실제
 * 행정동과 겹치지 않는 남해 해상 좌표에 합성 region 을 세워 임계(10%)를 결정적으로 재현한다.
 */
@SpringBootTest
@Transactional
@DisplayName("BadgeAwardService 지급 엔진·업로드 훅 (실 PostGIS)")
class BadgeAwardServiceIntegrationTest {

	// 다른 테스트와 겹치지 않는 임의 기준점 (성수 남동쪽). 재방문·비회수 시나리오용 육지 좌표.
	private static final double 육지_LAT = 37.5391;
	private static final double 육지_LON = 127.0623;

	// 실제 행정동 폴리곤이 덮지 않는 남해 해상 — 합성 region 라벨(아래) / 무라벨 시나리오용.
	private static final double 합성지역_LAT = 34.15;
	private static final double 합성지역_LON = 128.45;
	private static final double 무라벨_LAT = 34.35;
	private static final double 무라벨_LON = 128.75;

	@Autowired
	private VideoService videoService;

	@Autowired
	private UserBadgeRepository userBadgeRepository;

	@MockitoSpyBean
	private BadgeAwardService badgeAwardService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@MockitoBean
	private S3Client s3Client;

	private long userId;

	@BeforeEach
	void setUp() {
		given(s3Client.headObject(any(HeadObjectRequest.class))).willReturn(HeadObjectResponse.builder().build());
		String email = "badge-award-" + System.nanoTime() + "@example.com";
		userId = userRepository.save(User.createLocalUser(email, "hash", "뱃지테스터")).getId();
	}

	@Nested
	@DisplayName("지급 엔진 (§D5)")
	class 지급_엔진 {

		@Test
		@DisplayName("이미 획득한 뱃지는 같은 행동을 반복해도 다시 지급되지 않는다 (FR-3)")
		void 이미_획득한_뱃지는_같은_행동을_반복해도_다시_지급되지_않는다() {
			List<EarnedBadgeResponseDto> first =
				badgeAwardService.award(userId, BadgeConditionType.TOTAL_GRIDS, BigDecimal.ONE);
			List<EarnedBadgeResponseDto> second =
				badgeAwardService.award(userId, BadgeConditionType.TOTAL_GRIDS, BigDecimal.ONE);

			assertThat(first).extracting(EarnedBadgeResponseDto::code).containsExactly("EXPLORER_1");
			assertThat(second).isEmpty();
			assertThat(earnedCodes()).containsExactly("EXPLORER_1");
		}

		@Test
		@DisplayName("하나의 행동으로 여러 티어를 동시에 충족하면 전부 지급된다 (FR-4)")
		void 하나의_행동으로_여러_티어를_동시에_충족하면_전부_지급된다() {
			List<EarnedBadgeResponseDto> earned =
				badgeAwardService.award(userId, BadgeConditionType.TOTAL_GRIDS, BigDecimal.valueOf(12));

			// findEligible 이 condition_value 오름차순이라 하위 티어부터 실린다.
			assertThat(earned).extracting(EarnedBadgeResponseDto::code)
				.containsExactly("EXPLORER_1", "EXPLORER_10");
			assertThat(earnedCodes()).containsExactlyInAnyOrder("EXPLORER_1", "EXPLORER_10");
		}

		@Test
		@DisplayName("동시 업로드에서도 user_badges 에 중복 row 가 생기지 않는다 — ON CONFLICT(FR-3)")
		void 동시_업로드에서도_user_badges에_중복_row가_생기지_않는다() {
			long explorerId = badgeId("EXPLORER_1");

			// 동시 요청이 같은 후보를 계산한 경합의 재현 — 2번째 INSERT 는 PK 최후 방어선에 흡수된다.
			int firstInsert = userBadgeRepository.insertIgnoreConflict(userId, explorerId);
			int secondInsert = userBadgeRepository.insertIgnoreConflict(userId, explorerId);

			assertThat(firstInsert).isEqualTo(1);
			assertThat(secondInsert).isZero();
			assertThat(earnedCodes()).containsExactly("EXPLORER_1");
		}

		@Test
		@DisplayName("업로드 수는 삭제된 영상을 포함해 센다 — 생애 카운트(§D3)")
		void 업로드_수는_삭제된_영상을_포함해_센다() {
			String gridId = seedMyGrid();
			seedVideo(gridId, "ACTIVE");
			seedVideo(gridId, "DELETED");

			assertThat(userBadgeRepository.countMyVideos(userId)).isEqualByComparingTo(BigDecimal.valueOf(2));
		}
	}

	@Nested
	@DisplayName("업로드 훅·응답 동봉 (§D3·FR-9)")
	class 업로드_훅 {

		@Test
		@DisplayName("첫 업로드는 첫 발자국 뱃지를 지급하고 응답에 동봉한다 (EXPLORER_1)")
		void 첫_업로드는_첫_발자국_뱃지를_지급하고_응답에_동봉한다() {
			VideoUploadResponseDto response = videoService.saveVideo(userId, request(육지_LAT, 육지_LON));

			assertThat(response.occupied()).isTrue();
			assertThat(response.newBadges()).extracting(EarnedBadgeResponseDto::code).contains("EXPLORER_1");
			assertThat(earnedCodes()).contains("EXPLORER_1");
		}

		@Test
		@DisplayName("동기 지급분은 notified_at 이 기록된다 (§D8)")
		void 동기_지급분은_notified_at이_기록된다() {
			videoService.saveVideo(userId, request(육지_LAT, 육지_LON));

			Number unnotified = (Number) em.createNativeQuery(
					"SELECT COUNT(*) FROM user_badges WHERE user_id = :me AND notified_at IS NULL")
				.setParameter("me", userId)
				.getSingleResult();
			assertThat(earnedCodes()).isNotEmpty();
			assertThat(unnotified.longValue()).isZero();
		}

		@Test
		@DisplayName("재방문 업로드는 업로드 수 뱃지만 판정한다 — 축 한정(FR-2)")
		void 재방문_업로드는_업로드_수_뱃지만_판정한다() {
			videoService.saveVideo(userId, request(육지_LAT, 육지_LON));
			clearInvocations(badgeAwardService);

			videoService.saveVideo(userId, request(육지_LAT, 육지_LON));

			// 훅은 행동 단위 호출 — 재방문은 업로드 축만 호출되고 수집 축(총 격자·수집률)은 호출 자체가 없다.
			then(badgeAwardService).should().awardUploadBadges(userId);
			then(badgeAwardService).should(never()).awardCollectionBadges(eq(userId), any());
		}

		@Test
		@DisplayName("수집률이 임계를 넘으면 지역 마스터 뱃지가 지급된다 (10% → REGION_MASTER_10)")
		void 수집률이_임계를_넘으면_지역_마스터_뱃지가_지급된다() {
			// 합성 region(총 격자 10)을 세우면 upsertGrid 가 신규 격자를 이 region 으로 라벨하고,
			// refresh 가 1/10 = 10.00% 를 물질화한다 — 판정은 그 값을 읽기만 한다(§D4).
			seedSeaRegion();

			VideoUploadResponseDto response = videoService.saveVideo(userId, request(합성지역_LAT, 합성지역_LON));

			assertThat(response.newBadges()).extracting(EarnedBadgeResponseDto::code)
				.containsExactlyInAnyOrder("EXPLORER_1", "REGION_MASTER_10");
		}

		@Test
		@DisplayName("무라벨 격자 수집은 지역 뱃지 판정을 건너뛴다 (§D4 no-op)")
		void 무라벨_격자_수집은_지역_뱃지_판정을_건너뛴다() {
			VideoUploadResponseDto response = videoService.saveVideo(userId, request(무라벨_LAT, 무라벨_LON));

			// refresh no-op → region_stats 행 없음 → 수집률 판정 자체가 건너뛰어져(에러 아님) 지역 뱃지가 없다.
			// (skip 분기 자체의 단위 검증은 BadgeAwardServiceTest 쪽 — 여기서는 응답으로 관찰한다.)
			assertThat(response.newBadges()).extracting(EarnedBadgeResponseDto::code)
				.containsExactly("EXPLORER_1");
		}

		@Test
		@DisplayName("조건 미충족이면 응답의 newBadges 는 빈 배열이다")
		void 조건_미충족이면_응답의_newBadges는_빈_배열이다() {
			videoService.saveVideo(userId, request(육지_LAT, 육지_LON));

			// 재방문: 업로드 수 2(<10)뿐이라 도달한 뱃지가 없다.
			VideoUploadResponseDto second = videoService.saveVideo(userId, request(육지_LAT, 육지_LON));

			assertThat(second.newBadges()).isEmpty();
		}
	}

	@Nested
	@DisplayName("비회수 (FR-5)")
	class 비회수 {

		@Test
		@DisplayName("영상을 모두 삭제해 수집이 롤백되어도 획득한 뱃지는 유지된다")
		void 영상을_모두_삭제해_수집이_롤백되어도_획득한_뱃지는_유지된다() {
			VideoUploadResponseDto response = videoService.saveVideo(userId, request(육지_LAT, 육지_LON));
			assertThat(earnedCodes()).contains("EXPLORER_1");

			videoService.deleteVideo(userId, response.videoId());

			Number remainingGrids = (Number) em.createNativeQuery(
					"SELECT COUNT(*) FROM user_grids WHERE user_id = :me")
				.setParameter("me", userId)
				.getSingleResult();
			assertThat(remainingGrids.longValue()).isZero();          // 수집 롤백은 일어났고
			assertThat(earnedCodes()).contains("EXPLORER_1");          // 뱃지는 불변이다
		}
	}

	private VideoUploadRequestDto request(double lat, double lon) {
		return new VideoUploadRequestDto(
			"videos/pending/" + userId + "/" + UUID.randomUUID() + ".mp4",
			lat, lon, (short) 10, LocalDateTime.now(ZoneOffset.UTC), "PRIVATE");
	}

	@SuppressWarnings("unchecked")
	private List<String> earnedCodes() {
		return em.createNativeQuery("""
				SELECT b.code FROM user_badges ub JOIN badges b ON b.id = ub.badge_id
				WHERE ub.user_id = :userId
				""")
			.setParameter("userId", userId)
			.getResultList();
	}

	private long badgeId(String code) {
		return ((Number) em.createNativeQuery("SELECT id FROM badges WHERE code = :code")
			.setParameter("code", code)
			.getSingleResult()).longValue();
	}

	private String seedMyGrid() {
		var index = GridEncoder.decode(GridEncoder.encode(육지_LAT, 육지_LON));
		String gridId = GridFixtures.seedGrid(em, index.gridY(), index.gridX());
		GridFixtures.seedUserGrid(em, userId, gridId, 1);
		return gridId;
	}

	private void seedVideo(String gridId, String status) {
		em.createNativeQuery("""
				INSERT INTO videos (user_id, grid_id, geom, duration_sec, recorded_at, status)
				VALUES (
					:userId, :gridId,
					ST_SetSRID(ST_MakePoint(127.06, 37.53), 4326)::geography,
					10, now(), :status
				)
				""")
			.setParameter("userId", userId)
			.setParameter("gridId", gridId)
			.setParameter("status", status)
			.executeUpdate();
	}

	/** 합성지역 좌표를 덮는 총 격자 10짜리 해상 행정동 — 첫 수집이 정확히 10% 가 되도록 한다. */
	private void seedSeaRegion() {
		String regionCode = "TB" + (System.nanoTime() % 100_000_000L);
		em.createNativeQuery("""
				INSERT INTO regions (region_code, region_name, boundary_geom, total_grid_count)
				VALUES (
					:code, :code,
					ST_GeomFromText('MULTIPOLYGON(((128.4 34.1,128.4 34.2,128.5 34.2,128.5 34.1,128.4 34.1)))', 4326),
					10
				)
				""")
			.setParameter("code", regionCode)
			.executeUpdate();
	}
}
