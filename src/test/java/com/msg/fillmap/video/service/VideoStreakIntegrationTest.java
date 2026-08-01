package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

import com.msg.fillmap.badge.dto.EarnedBadgeResponseDto;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.dto.VideoReplaceRequestDto;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.dto.VideoUploadResponseDto;

/**
 * 업로드 훅의 스트릭 배선 통합 검증 (실 PostGIS, MSG-200 모듈 3). 공유 로컬 DB — 합성 유저만 만들고
 * @Transactional 롤백. 인정 이벤트는 업로드뿐(§D1) — 삭제·교체는 훅 미호출이 스펙이라 회귀 테스트로
 * 고정한다(§D4). "어제" 시나리오는 fixture 행의 last_recorded_date 를 직접 물려 넣어 등가 검증한다.
 */
@SpringBootTest
@Transactional
@DisplayName("업로드 훅 스트릭 배선 (실 PostGIS)")
class VideoStreakIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	// 다른 테스트(성수·잠실·뚝섬)와 겹치지 않는 좌표 (망원).
	private static final double 망원_LAT = 37.5497;
	private static final double 망원_LON = 126.9105;

	@Autowired
	private VideoService videoService;

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
		String email = "video-streak-" + System.nanoTime() + "@example.com";
		userId = userRepository.save(User.createLocalUser(email, "hash", "스트릭훅테스터")).getId();
	}

	@Test
	@DisplayName("재방문 업로드도 스트릭을 갱신한다 — 훅은 점령 분기 바깥(§D1)")
	void 재방문_업로드도_스트릭을_갱신한다() {
		videoService.saveVideo(userId, uploadRequest());
		// 어제 기록으로 물려 두면, 재방문 훅이 실제로 돌 때만 +1 이 관찰된다.
		rewindStreakToYesterday(1, 1);

		VideoUploadResponseDto revisit = videoService.saveVideo(userId, uploadRequest());

		assertThat(revisit.occupied()).isFalse();
		assertThat(currentCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("업로드 응답에 스트릭 뱃지가 동봉된다 — 기존 뱃지와 공존(FR-9)")
	void 업로드_응답에_스트릭_뱃지가_동봉된다() {
		seedStreakYesterday(2, 2);

		VideoUploadResponseDto response = videoService.saveVideo(userId, uploadRequest());

		// 첫 업로드라 첫 발자국(EXPLORER_1)과 3일 도달(STREAK_3)이 한 응답에 실린다.
		assertThat(response.newBadges()).extracting(EarnedBadgeResponseDto::code)
			.contains("EXPLORER_1", "STREAK_3");
	}

	@Test
	@DisplayName("영상 삭제는 스트릭을 변경하지 않는다 — 소급 차감 없음(§D4)")
	void 영상_삭제는_스트릭을_변경하지_않는다() {
		long videoId = videoService.saveVideo(userId, uploadRequest()).videoId();
		Object[] before = streakRow();

		videoService.deleteVideo(userId, videoId);

		assertThat(streakRow()).isEqualTo(before);
	}

	@Test
	@DisplayName("영상 교체는 스트릭을 갱신하지 않는다 — 비인정 이벤트(§D1)")
	void 영상_교체는_스트릭을_갱신하지_않는다() {
		long videoId = videoService.saveVideo(userId, uploadRequest()).videoId();
		// 어제 기록으로 물려 두면, 교체 경로에 훅이 섞여 들어올 경우 +1 로 드러난다 (회귀 방지).
		rewindStreakToYesterday(1, 1);

		videoService.replaceVideo(userId, videoId,
			new VideoReplaceRequestDto(pendingKey(), null, null, (short) 7, LocalDateTime.now()));

		assertThat(currentCount()).isEqualTo(1);
		assertThat(lastRecordedDate()).isEqualTo(LocalDate.now(KST).minusDays(1));
	}

	private VideoUploadRequestDto uploadRequest() {
		return new VideoUploadRequestDto(pendingKey(), 망원_LAT, 망원_LON, (short) 10, LocalDateTime.now(), "PRIVATE");
	}

	private String pendingKey() {
		return "videos/pending/" + userId + "/" + UUID.randomUUID() + ".mp4";
	}

	private int currentCount() {
		return ((Number) streakRow()[0]).intValue();
	}

	private LocalDate lastRecordedDate() {
		return (LocalDate) streakRow()[2];
	}

	private Object[] streakRow() {
		return (Object[]) em.createNativeQuery("""
				SELECT current_count, max_count, last_recorded_date FROM streaks
				WHERE user_id = :userId
				""")
			.setParameter("userId", userId)
			.getSingleResult();
	}

	/** 이미 생성된 스트릭 행을 어제 기록 상태로 되돌린다 — "훅이 돌면 +1" 관찰용. */
	private void rewindStreakToYesterday(int currentCount, int maxCount) {
		em.createNativeQuery("""
				UPDATE streaks SET current_count = :current, max_count = :max,
					last_recorded_date = CAST(:lastDate AS date)
				WHERE user_id = :userId
				""")
			.setParameter("userId", userId)
			.setParameter("current", currentCount)
			.setParameter("max", maxCount)
			.setParameter("lastDate", LocalDate.now(KST).minusDays(1).toString())
			.executeUpdate();
	}

	/** 업로드 전 fixture — 어제까지 currentCount 일 연속인 스트릭 행을 시딩한다. */
	private void seedStreakYesterday(int currentCount, int maxCount) {
		em.createNativeQuery("""
				INSERT INTO streaks (user_id, current_count, max_count, last_recorded_date)
				VALUES (:userId, :current, :max, CAST(:lastDate AS date))
				""")
			.setParameter("userId", userId)
			.setParameter("current", currentCount)
			.setParameter("max", maxCount)
			.setParameter("lastDate", LocalDate.now(KST).minusDays(1).toString())
			.executeUpdate();
	}
}
