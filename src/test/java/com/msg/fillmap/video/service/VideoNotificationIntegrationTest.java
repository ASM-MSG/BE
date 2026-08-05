package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.GeoSupport;

/**
 * 영상 처리 종결 전이 → VIDEO 알림 배선 통합 검증 (실 PostGIS, MSG-313). 전이와 같은 REQUIRES_NEW
 * 커밋에 남는 원자성과 ON CONFLICT 멱등을 실제 DB 로 관찰해야 하므로 @Transactional 격리를 못 쓴다 —
 * 합성 유저·격자·영상을 커밋해 두고 @AfterEach 에서 users 지정 삭제(videos·notifications CASCADE) 후
 * grids 를 걷어낸다 (BadgeNotificationIntegrationTest 선례). 셀 (40313, 108670)은 이 테스트 전용이다.
 */
@SpringBootTest
@DisplayName("VideoStatusWriter → VIDEO 알림 배선 (실 PostGIS)")
class VideoNotificationIntegrationTest {

	private static final long GRID_Y = 40313L;
	private static final long GRID_X = 108670L;
	private static final String ENCODED_KEY = "videos/encoded/1/n.mp4";
	private static final String THUMBNAIL_KEY = "videos/thumb/1/n.jpg";

	@Autowired
	private VideoStatusWriter statusWriter;

	@Autowired
	private VideoRepository videoRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@Autowired
	private PlatformTransactionManager txManager;

	private TransactionTemplate tx;
	private long userId;
	private long videoId;
	private String fileName;
	private String originalKey;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(txManager);
		fileName = System.nanoTime() + ".mp4";
		tx.executeWithoutResult(status -> {
			userId = userRepository.save(User.createLocalUser(
				"video-notif-" + System.nanoTime() + "@example.com", "hash", "영상알림테스터")).getId();
			String gridId = GridFixtures.seedGrid(em, GRID_Y, GRID_X);
			originalKey = "videos/original/" + userId + "/" + fileName;
			videoId = videoRepository.save(Video.create(userId, gridId, originalKey,
				GeoSupport.toPoint(36.28215, 124.971075), (short) 10, LocalDateTime.now(),
				Visibility.PRIVATE)).getId();
		});
	}

	@AfterEach
	void tearDown() {
		tx.executeWithoutResult(status -> {
			em.createNativeQuery("DELETE FROM users WHERE id = :userId")
				.setParameter("userId", userId)
				.executeUpdate();
			em.createNativeQuery("DELETE FROM grids WHERE grid_id = :gridId")
				.setParameter("gridId", GridFixtures.gridId(GRID_Y, GRID_X))
				.executeUpdate();
		});
	}

	@Test
	@DisplayName("인코딩만 경로 완료 전이는 VIDEO 완료 알림을 기록한다 — event_key·문구 (FR-1)")
	void 인코딩만_경로_완료_전이는_VIDEO_완료_알림을_기록한다() {
		statusWriter.markReady(videoId, originalKey, ENCODED_KEY, THUMBNAIL_KEY);

		assertThat(notificationCount()).isEqualTo(1);
		Object[] row = notificationRow("VIDEO:" + videoId + ":" + fileName);
		assertThat(row[0]).isEqualTo("VIDEO");
		assertThat(row[1]).isEqualTo("영상이 준비됐어요");
		assertThat(row[2]).isEqualTo("올린 영상 처리가 끝났어요. 지금 확인해 보세요");
	}

	@Test
	@DisplayName("블러 경로 완료 전이는 VIDEO 완료 알림을 기록한다 — markBlurReady 적용 경로 (FR-1)")
	void 블러_경로_완료_전이는_VIDEO_완료_알림을_기록한다() {
		enterBlurring("job-1");

		boolean applied = statusWriter.markBlurReady(videoId, "job-1", "videos/blurred/1/n.mp4",
			THUMBNAIL_KEY, List.of(List.of(0.0, 3.33)));

		assertThat(applied).isTrue();
		assertThat(notificationCount()).isEqualTo(1);
		Object[] row = notificationRow("VIDEO:" + videoId + ":" + fileName);
		assertThat(row[1]).isEqualTo("영상이 준비됐어요");
	}

	@Test
	@DisplayName("인코딩 실패 전이는 재업로드 유도 문구의 실패 알림을 기록한다 (FR-2b)")
	void 인코딩_실패_전이는_재업로드_유도_문구의_실패_알림을_기록한다() {
		statusWriter.markFailed(videoId, originalKey);

		assertThat(notificationCount()).isEqualTo(1);
		Object[] row = notificationRow("VIDEO:" + videoId + ":" + fileName);
		assertThat(row[1]).isEqualTo("영상 처리에 실패했어요");
		assertThat(row[2]).isEqualTo("올린 영상을 준비하지 못했어요. 영상을 다시 올려 주세요");
	}

	@Test
	@DisplayName("블러 실패 전이는 실패 알림을 기록하고 탈락 사유는 문구에 넣지 않는다 (FR-2b)")
	void 블러_실패_전이는_실패_알림을_기록하고_탈락_사유는_문구에_넣지_않는다() {
		LocalDateTime startedAt = enterBlurring("job-1");

		statusWriter.markBlurFailed(videoId, "job-1", startedAt, "too_dark");

		assertThat(notificationCount()).isEqualTo(1);
		Object[] row = notificationRow("VIDEO:" + videoId + ":" + fileName);
		assertThat(row[1]).isEqualTo("영상 처리에 실패했어요");
		assertThat(row[2]).isEqualTo("올린 영상을 준비하지 못했어요. 영상을 다시 올려 주세요");
		assertThat((String) row[2]).doesNotContain("too_dark");
	}

	@Test
	@DisplayName("스테일 전이는 상태도 알림도 남기지 않는다 — 원본 키 불일치 가드 skip")
	void 스테일_전이는_상태도_알림도_남기지_않는다() {
		statusWriter.markReady(videoId, "videos/original/" + userId + "/stale.mp4", ENCODED_KEY, THUMBNAIL_KEY);

		assertThat(processingStatus()).isEqualTo("UPLOADED");
		assertThat(notificationCount()).isZero();
	}

	@Test
	@DisplayName("같은 시도의 중복 전이는 알림을 한 번만 기록한다 — 같은 event_key, ON CONFLICT 흡수 (FR-6)")
	void 같은_시도의_중복_전이는_알림을_한_번만_기록한다() {
		statusWriter.markReady(videoId, originalKey, ENCODED_KEY, THUMBNAIL_KEY);
		statusWriter.markReady(videoId, originalKey, ENCODED_KEY, THUMBNAIL_KEY);

		assertThat(notificationCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("교체 후 재처리 완료는 새 알림으로 기록된다 — 다른 originalS3Key = 다른 event_key (FR-2)")
	void 교체_후_재처리_완료는_새_알림으로_기록된다() {
		statusWriter.markReady(videoId, originalKey, ENCODED_KEY, THUMBNAIL_KEY);
		String replacedFileName = System.nanoTime() + ".mp4";
		String replacedKey = "videos/original/" + userId + "/" + replacedFileName;
		tx.executeWithoutResult(status ->
			videoRepository.findById(videoId).orElseThrow()
				.replaceFile(replacedKey, (short) 8, LocalDateTime.now()));

		statusWriter.markReady(videoId, replacedKey, ENCODED_KEY, THUMBNAIL_KEY);

		assertThat(notificationCount()).isEqualTo(2);
		Object[] replaced = notificationRow("VIDEO:" + videoId + ":" + replacedFileName);
		assertThat(replaced[1]).isEqualTo("영상이 준비됐어요");
	}

	@Test
	@DisplayName("확정 흐름 실측 77자 파일명에서도 event_key 는 꼬리 45자로 잘려 100자 이내다 — VARCHAR(100) 상한")
	void 확정_흐름_실측_77자_파일명에서도_event_key는_100자_이내다() {
		// confirmUpload 실제 형식: {pendingUuid}-{attemptUuid}.mp4 = 77자
		String longFileName = UUID.randomUUID() + "-" + UUID.randomUUID() + ".mp4";
		String longKey = "videos/original/" + userId + "/" + longFileName;
		tx.executeWithoutResult(status ->
			videoRepository.findById(videoId).orElseThrow()
				.replaceFile(longKey, (short) 8, LocalDateTime.now()));

		statusWriter.markReady(videoId, longKey, ENCODED_KEY, THUMBNAIL_KEY);

		// 꼬리 45자 = pendingUuid 끝 4자 + "-" + attemptUuid(36) + ".mp4" — attemptUuid 는 온전히 남는다
		String expectedKey = "VIDEO:" + videoId + ":" + longFileName.substring(longFileName.length() - 45);
		assertThat(expectedKey.length()).isLessThanOrEqualTo(100);
		assertThat(notificationRow(expectedKey)[1]).isEqualTo("영상이 준비됐어요");
	}

	@Test
	@DisplayName("영상 행이 없는 전이는 예외 없이 무동작이다 — 탈퇴 CASCADE 이후 지연 도착 재현 (Codex 2R)")
	void 영상_행이_없는_전이는_예외_없이_무동작이고_알림도_없다() {
		tx.executeWithoutResult(status ->
			em.createNativeQuery("DELETE FROM videos WHERE id = :videoId")
				.setParameter("videoId", videoId)
				.executeUpdate());

		statusWriter.markReady(videoId, originalKey, ENCODED_KEY, THUMBNAIL_KEY);

		assertThat(notificationCount()).isZero();
	}

	@Test
	@DisplayName("잡 유실 복귀는 알림을 기록하지 않는다 — clearAiJob 은 종결이 아니라 재제출 대기")
	void 잡_유실_복귀는_알림을_기록하지_않는다() {
		enterBlurring("job-1");

		statusWriter.clearAiJob(videoId, "job-1");

		assertThat(notificationCount()).isZero();
	}

	/**
	 * 블러 경로 진입 — 인코딩 시작·완료(BLURRING 전이) 후 잡 제출까지. blurringStartedAt 은 커밋 후
	 * 재조회값을 쓴다 — DB timestamp 마이크로초 절단과 recordAiJob 의 넌스 일치 가드를 정합시킨다.
	 */
	private LocalDateTime enterBlurring(String jobId) {
		statusWriter.markEncoding(videoId, originalKey);
		statusWriter.markEncoded(videoId, originalKey, ENCODED_KEY);
		LocalDateTime startedAt = tx.execute(status ->
			videoRepository.findById(videoId).orElseThrow().getBlurringStartedAt());
		statusWriter.recordAiJob(videoId, jobId, startedAt);
		return startedAt;
	}

	private long notificationCount() {
		return ((Number) em.createNativeQuery("SELECT count(*) FROM notifications WHERE user_id = :userId")
			.setParameter("userId", userId)
			.getSingleResult()).longValue();
	}

	/** category·title·body 스냅샷 — 단언은 호출부에서 (BadgeNotificationIntegrationTest 선례). */
	private Object[] notificationRow(String eventKey) {
		return (Object[]) em.createNativeQuery("""
				SELECT category, title, body FROM notifications
				WHERE user_id = :userId AND event_key = :eventKey
				""")
			.setParameter("userId", userId)
			.setParameter("eventKey", eventKey)
			.getSingleResult();
	}

	private String processingStatus() {
		return (String) em.createNativeQuery("SELECT processing_status FROM videos WHERE id = :videoId")
			.setParameter("videoId", videoId)
			.getSingleResult();
	}
}
