package com.msg.fillmap.video.service;

import static com.msg.fillmap.video.support.S3VideoObjectStub.givenUploadedVideoObject;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import software.amazon.awssdk.services.s3.S3Client;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.dto.GridVideoResponseDto;
import com.msg.fillmap.video.dto.VideoPlaybackResponseDto;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.exception.VideoErrorCode;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;

/**
 * 블라인드 전환 · 해제 (MSG-193).
 *
 * 검증의 절반은 전이 자체(상태·대표 재선정·점령 불변)이고, 나머지 절반은 회귀다 — 이 티켓은 조회
 * 쿼리를 한 줄도 고치지 않으므로 "전환 즉시 노출 경로에서 사라지는가"를 기존 필터로 확인해야 한다.
 *
 * 카운트는 user_id 로 좁혀서 센다 (VideoDeleteIntegrationTest 관례). 전역 경로(대표·목록)는 user_id 로
 * 좁힐 수 없어 이 테스트 전용 좌표를 쓰고, 롤백 없이 죽은 이전 실행의 잔여 시드를 setUp 에서 지운다.
 */
@SpringBootTest
@Transactional
@DisplayName("영상 블라인드 전환 · 해제 (실 PostGIS)")
class VideoBlindIntegrationTest {

	// 다른 테스트(성수·여의도·잠실 등)와 겹치지 않는 이 테스트 전용 좌표.
	private static final double 노원_LAT = 37.6542;
	private static final double 노원_LON = 127.0568;

	@Autowired
	private VideoModerationService videoModerationService;

	@Autowired
	private VideoService videoService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	/** saveVideo 가 s3Key 실존을 headObject 로 확인한다(MSG-132). 목이 없으면 실제 S3 를 호출한다. */
	@MockitoBean
	private S3Client s3Client;

	/**
	 * presign 은 AWS 자격증명 의존을 피해 목으로 대체한다. 어떤 key 든 URL 을 돌려주므로, 블라인드
	 * 영상의 playbackUrl 이 null 이라는 건 presign 실패가 아니라 status 게이트가 막았다는 뜻이 된다.
	 */
	@MockitoBean
	private ThumbnailUrlPresigner thumbnailUrlPresigner;

	private Long userId;
	private Long otherUserId;
	private String gridId;

	@BeforeEach
	void setUp() {
		givenUploadedVideoObject(s3Client);
		given(thumbnailUrlPresigner.presign(anyString())).willReturn("https://signed");
		given(thumbnailUrlPresigner.ttlSeconds()).willReturn(600L);
		userId = userRepository.save(User.createLocalUser("blind-owner@example.com", "hash", "주인")).getId();
		otherUserId = userRepository.save(User.createLocalUser("blind-other@example.com", "hash", "타인")).getId();
		gridId = GridEncoder.encode(노원_LAT, 노원_LON);
		clearGridSeed();
	}

	/** 전역 경로 단언이 이전 실행 잔여물에 걸리지 않도록 전용 격자를 비운다 (이 정리도 테스트 종료 시 롤백된다). */
	private void clearGridSeed() {
		em.createNativeQuery("DELETE FROM user_grids WHERE grid_id = :g").setParameter("g", gridId).executeUpdate();
		em.createNativeQuery("DELETE FROM videos WHERE grid_id = :g").setParameter("g", gridId).executeUpdate();
	}

	private long upload(String visibility) {
		return videoService.saveVideo(userId, new VideoUploadRequestDto(
			"videos/pending/" + userId + "/" + System.nanoTime() + ".mp4",
			노원_LAT, 노원_LON, (short) 10, LocalDateTime.now(ZoneOffset.UTC), visibility)).videoId();
	}

	/**
	 * 전역 노출 후보 조건(READY + 재생본 key)까지 올린다 — 업로드 직후는 UPLOADED 라 전역 쿼리·재생 URL
	 * 발급 조건에 애초에 못 든다. 그 상태로는 "블라인드라서 빠졌다"를 증명할 수 없다.
	 */
	private void markReady(long videoId) {
		em.flush();
		em.createNativeQuery("UPDATE videos SET processing_status = 'READY', encoded_url = :enc WHERE id = :id")
			.setParameter("enc", "videos/encoded/" + videoId + ".mp4")
			.setParameter("id", videoId)
			.executeUpdate();
		em.clear();   // native UPDATE 는 영속성 컨텍스트를 안 건드린다 — 이후 조회가 DB 값을 다시 읽게 한다
	}

	private String statusOf(long videoId) {
		return (String) em.createNativeQuery("SELECT status FROM videos WHERE id = :i")
			.setParameter("i", videoId)
			.getSingleResult();
	}

	private Long userGridCount() {
		return ((Number) em.createNativeQuery(
				"SELECT count(*) FROM user_grids WHERE user_id = :u AND grid_id = :g")
			.setParameter("u", userId).setParameter("g", gridId)
			.getSingleResult()).longValue();
	}

	private Integer videoCount() {
		return (Integer) em.createNativeQuery(
				"SELECT video_count FROM user_grids WHERE user_id = :u AND grid_id = :g")
			.setParameter("u", userId).setParameter("g", gridId)
			.getSingleResult();
	}

	private Long coverVideoId() {
		Number cover = (Number) em.createNativeQuery(
				"SELECT cover_video_id FROM user_grids WHERE user_id = :u AND grid_id = :g")
			.setParameter("u", userId).setParameter("g", gridId)
			.getSingleResult();
		return cover == null ? null : cover.longValue();
	}

	/** 전역 경로는 DB 를 직접 읽으므로 보류 중인 상태 전이를 먼저 내보낸다. */
	private void flushAndClear() {
		em.flush();
		em.clear();
	}

	private List<Long> myVideoIds() {
		flushAndClear();
		return videoService.getGridVideos(userId, gridId).stream().map(GridVideoResponseDto::videoId).toList();
	}

	private List<Long> globalVideoIds() {
		flushAndClear();
		return videoService.getGridGlobalVideos(gridId, null, 20).videos().stream()
			.map(video -> video.videoId())
			.toList();
	}

	@Test
	void 블라인드_전환하면_상태가_BLINDED_로_저장된다() {
		long videoId = upload("PUBLIC");

		videoModerationService.blind(videoId);

		assertThat(statusOf(videoId)).isEqualTo("BLINDED");
	}

	@Test
	void 해제하면_ACTIVE_로_돌아와_타인_재생이_다시_허용된다() {
		long videoId = upload("PUBLIC");
		videoModerationService.blind(videoId);

		videoModerationService.unblind(videoId);

		assertThat(statusOf(videoId)).isEqualTo("ACTIVE");
		assertThat(videoService.getVideoPlayback(otherUserId, videoId).status()).isEqualTo("ACTIVE");
	}

	// 검증: FR-VIDEO-13, FR-MOD-06
	@Test
	void 블라인드된_영상은_타인_재생이_404_로_거부된다() {
		long videoId = upload("PUBLIC");

		videoModerationService.blind(videoId);

		assertThatThrownBy(() -> videoService.getVideoPlayback(otherUserId, videoId))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.VIDEO_NOT_FOUND);
	}

	// 검증: FR-VIDEO-13, FR-MOD-06
	@Test
	void 블라인드된_영상은_소유자_조회에서_상태는_보이지만_재생_URL_이_발급되지_않는다() {
		long videoId = upload("PUBLIC");
		markReady(videoId);   // 블라인드가 아니었다면 URL 이 발급됐을 상태
		assertThat(videoService.getVideoPlayback(userId, videoId).playbackUrl())
			.as("전환 전에는 재생 URL 이 발급된다 — 아래 null 이 markReady 미적용 탓이 아님을 닫는다").isNotNull();

		videoModerationService.blind(videoId);

		VideoPlaybackResponseDto playback = videoService.getVideoPlayback(userId, videoId);
		assertThat(playback.status()).as("소유자는 블라인드 사유를 상태로 구분한다").isEqualTo("BLINDED");
		assertThat(playback.playbackUrl()).isNull();
		assertThat(playback.expiresInSec()).isNull();
	}

	// 검증: FR-MOD-06
	@Test
	void 블라인드된_영상은_격자_전역_대표에서_제외된다() {
		long videoId = upload("PUBLIC");
		markReady(videoId);
		flushAndClear();
		assertThat(videoService.getGridCover(gridId)).as("전환 전에는 대표 후보").isNotNull();

		videoModerationService.blind(videoId);

		flushAndClear();
		assertThat(videoService.getGridCover(gridId)).isNull();
	}

	// 검증: FR-MOD-06
	@Test
	void 블라인드된_영상은_격자_전역_목록에서_제외된다() {
		long kept = upload("PUBLIC");
		long blinded = upload("PUBLIC");
		markReady(kept);
		markReady(blinded);

		videoModerationService.blind(blinded);

		assertThat(globalVideoIds()).contains(kept).doesNotContain(blinded);
	}

	// 검증: FR-MOD-06
	@Test
	void 블라인드된_영상은_격자별_내_영상_리스트에서_제외된다() {
		long kept = upload("PRIVATE");
		long blinded = upload("PRIVATE");

		videoModerationService.blind(blinded);

		assertThat(myVideoIds()).contains(kept).doesNotContain(blinded);
	}

	@Test
	void 대표_영상을_블라인드하면_남은_ACTIVE_영상으로_대표가_재선정된다() {
		long first = upload("PRIVATE");
		long second = upload("PRIVATE");
		assertThat(coverVideoId()).as("첫 업로드가 대표").isEqualTo(first);

		videoModerationService.blind(first);

		assertThat(coverVideoId()).as("남은 ACTIVE 중 가장 오래된 것 (FR-10)").isEqualTo(second);
	}

	@Test
	void 대표가_아닌_영상을_블라인드해도_대표는_바뀌지_않는다() {
		long first = upload("PRIVATE");
		long second = upload("PRIVATE");

		videoModerationService.blind(second);

		assertThat(coverVideoId()).isEqualTo(first);
	}

	@Test
	void 유일한_영상을_블라인드하면_대표가_비워지고_점령은_유지된다() {
		long videoId = upload("PRIVATE");

		videoModerationService.blind(videoId);

		assertThat(coverVideoId()).as("후보가 없으면 NULL — 도감 계약이 허용한다 (D5)").isNull();
		assertThat(userGridCount()).as("점령은 삭제가 아니면 안 풀린다").isEqualTo(1);
		assertThat(videoCount()).isEqualTo(1);
	}

	@Test
	void 해제해도_대표는_원복되지_않는다() {
		long first = upload("PRIVATE");
		long second = upload("PRIVATE");
		videoModerationService.blind(first);
		assertThat(coverVideoId()).isEqualTo(second);

		videoModerationService.unblind(first);

		assertThat(coverVideoId()).as("해제는 상태 복귀만 한다 (D6)").isEqualTo(second);
	}

	// 검증: FR-MOD-08
	@Test
	void 블라인드_전환과_해제는_점령_row_와_video_count_를_바꾸지_않는다() {
		long videoId = upload("PRIVATE");
		upload("PRIVATE");
		assertThat(videoCount()).isEqualTo(2);

		videoModerationService.blind(videoId);

		assertThat(userGridCount()).isEqualTo(1);
		assertThat(videoCount()).as("점령 롤백은 삭제 전용 (FR-11)").isEqualTo(2);

		videoModerationService.unblind(videoId);

		assertThat(userGridCount()).isEqualTo(1);
		assertThat(videoCount()).isEqualTo(2);
	}

	@Test
	void 삭제된_영상의_전환과_해제는_404_로_실패한다() {
		long videoId = upload("PRIVATE");
		videoService.deleteVideo(userId, videoId);

		assertThatThrownBy(() -> videoModerationService.blind(videoId))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.VIDEO_NOT_FOUND);
		assertThatThrownBy(() -> videoModerationService.unblind(videoId))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.VIDEO_NOT_FOUND);
	}

	@Test
	void 존재하지_않는_영상의_전환은_404_로_실패한다() {
		assertThatThrownBy(() -> videoModerationService.blind(999_999L))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.VIDEO_NOT_FOUND);
	}

	@Test
	void 이미_블라인드된_영상의_전환은_3409_로_실패한다() {
		long videoId = upload("PRIVATE");
		videoModerationService.blind(videoId);

		assertThatThrownBy(() -> videoModerationService.blind(videoId))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.ALREADY_IN_TARGET_STATUS);
	}

	@Test
	void ACTIVE_영상의_해제는_3409_로_실패한다() {
		long videoId = upload("PRIVATE");

		assertThatThrownBy(() -> videoModerationService.unblind(videoId))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.ALREADY_IN_TARGET_STATUS);
	}

	// ---- 블라인드 전환·해제 → MODERATION 소유자 알림 (MSG-417) ----

	// 검증: FR-NOTI-15
	@Test
	@DisplayName("블라인드 전환이 소유자에게 MODERATION 알림을 남긴다 — 문구에 신고 정보 미탑재 (FR-1·FR-2)")
	void 블라인드_전환이_소유자에게_MODERATION_알림을_남긴다() {
		long videoId = upload("PUBLIC");

		videoModerationService.blind(videoId);

		List<Object[]> rows = moderationRows();
		assertThat(rows).hasSize(1);
		assertThat((String) rows.get(0)[0]).startsWith("MODERATION:BLIND:" + videoId + ":");
		assertThat(rows.get(0)[1]).isEqualTo("영상이 가려졌어요");
		assertThat(rows.get(0)[2]).isEqualTo("올린 영상이 운영 정책에 따라 가려졌어요. 지금은 다른 사람에게 보이지 않아요");
		assertThat(rows.get(0)[3]).isEqualTo("PENDING");
	}

	// 검증: FR-NOTI-15
	@Test
	@DisplayName("해제가 복구 알림을 남긴다 — visibility 보존이라 '공개' 아닌 '원래 상태' 문구 (FR-3)")
	void 해제가_복구_알림을_남긴다() {
		long videoId = upload("PRIVATE");
		videoModerationService.blind(videoId);

		videoModerationService.unblind(videoId);

		List<Object[]> rows = moderationRows();
		assertThat(rows).hasSize(2);
		assertThat((String) rows.get(1)[0]).startsWith("MODERATION:UNBLIND:" + videoId + ":");
		assertThat(rows.get(1)[1]).isEqualTo("영상 숨김이 풀렸어요");
		assertThat(rows.get(1)[2]).isEqualTo("가려졌던 영상이 원래 상태로 돌아왔어요");
	}

	// 검증: FR-NOTI-15
	@Test
	@DisplayName("해제 후 재블라인드는 새 알림 행을 만든다 — 무작위 UUID 꼬리라 dedupe 에 안 먹힌다 (FR-5)")
	void 해제_후_재블라인드는_새_알림_행을_만든다() {
		long videoId = upload("PUBLIC");

		videoModerationService.blind(videoId);
		videoModerationService.unblind(videoId);
		videoModerationService.blind(videoId);

		List<Object[]> rows = moderationRows();
		assertThat(rows).hasSize(3);
		assertThat(rows.stream().map(row -> (String) row[0]).distinct()).hasSize(3);
	}

	// 검증: FR-NOTI-15
	@Test
	@DisplayName("이미 블라인드된 영상 재요청은 알림을 남기지 않는다 — 3409 가드가 record 앞 (FR-4)")
	void 이미_블라인드된_영상_재요청은_알림을_남기지_않는다() {
		long videoId = upload("PUBLIC");
		videoModerationService.blind(videoId);

		assertThatThrownBy(() -> videoModerationService.blind(videoId))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.ALREADY_IN_TARGET_STATUS);

		assertThat(moderationRows()).hasSize(1);
	}

	// 검증: FR-NOTI-15
	@Test
	@DisplayName("삭제된 영상 블라인드 시도는 알림을 남기지 않는다 — 3404 가드가 record 앞 (FR-4)")
	void 삭제된_영상_블라인드_시도는_알림을_남기지_않는다() {
		long videoId = upload("PRIVATE");
		videoService.deleteVideo(userId, videoId);

		assertThatThrownBy(() -> videoModerationService.blind(videoId))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.VIDEO_NOT_FOUND);

		assertThat(moderationRows()).isEmpty();
	}

	/** 소유자의 MODERATION 알림 행 스냅샷 (id 순) — event_key·title·body·status, 단언은 호출부에서. */
	@SuppressWarnings("unchecked")
	private List<Object[]> moderationRows() {
		return em.createNativeQuery("""
				SELECT event_key, title, body, status FROM notifications
				WHERE user_id = :u AND category = 'MODERATION' ORDER BY id
				""")
			.setParameter("u", userId)
			.getResultList();
	}
}
