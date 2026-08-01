package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
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

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.dto.VideoReplaceRequestDto;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.exception.VideoErrorCode;

/**
 * 영상 교체 (MSG-71).
 *
 * 핵심은 "파일만 바뀌고 도감은 그대로"다 — 정책 B(row 유지 갱신)를 택한 이유가 그것이고,
 * 그게 실제로 성립하는지가 이 테스트의 존재 이유다.
 */
@SpringBootTest
@Transactional
@DisplayName("영상 교체 (실 PostGIS)")
class VideoReplaceIntegrationTest {

	// 다른 테스트(성수·여의도·강남)와 겹치지 않는 좌표.
	private static final double 잠실_LAT = 37.5133;
	private static final double 잠실_LON = 127.1000;
	// 다른 격자 — GRID_MISMATCH 검증용.
	private static final double 광화문_LAT = 37.5759;
	private static final double 광화문_LON = 126.9769;

	@Autowired
	private VideoService videoService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@MockitoBean
	private S3Client s3Client;

	private Long userId;
	private String gridId;

	@BeforeEach
	void setUp() {
		given(s3Client.headObject(any(HeadObjectRequest.class))).willReturn(HeadObjectResponse.builder().build());
		userId = userRepository.save(User.createLocalUser("replacer@example.com", "hash", "교체자")).getId();
		gridId = GridEncoder.encode(잠실_LAT, 잠실_LON);
	}

	private String newKey() {
		return "videos/pending/" + userId + "/" + UUID.randomUUID() + ".mp4";
	}

	private long upload() {
		return videoService.saveVideo(userId, new VideoUploadRequestDto(
			newKey(), 잠실_LAT, 잠실_LON, (short) 10, LocalDateTime.now(), "PRIVATE")).videoId();
	}

	private VideoReplaceRequestDto replaceRequest(String s3Key, Double lat, Double lon) {
		return new VideoReplaceRequestDto(s3Key, lat, lon, (short) 7, LocalDateTime.now());
	}

	private Object[] videoRow(long videoId) {
		return (Object[]) em.createNativeQuery(
				"SELECT original_s3_key, processing_status, encoded_url, thumbnail_url, duration_sec, recorded_at"
					+ " FROM videos WHERE id = :i")
			.setParameter("i", videoId)
			.getSingleResult();
	}

	private Object[] userGridRow() {
		return (Object[]) em.createNativeQuery(
				"SELECT video_count, cover_video_id FROM user_grids WHERE user_id = :u AND grid_id = :g")
			.setParameter("u", userId).setParameter("g", gridId)
			.getSingleResult();
	}

	@Test
	@DisplayName("교체하면 파일 참조가 갱신되고 재인코딩 대기 상태로 돌아간다")
	void 교체하면_파일이_갈리고_UPLOADED_로_리셋된다() {
		long videoId = upload();
		// 인코딩이 끝난 상태를 흉내낸다 — 교체가 이걸 비워야 옛 영상이 재생되지 않는다.
		em.createNativeQuery("UPDATE videos SET processing_status='READY', encoded_url='old.mp4',"
				+ " thumbnail_url='old.jpg' WHERE id = :i")
			.setParameter("i", videoId).executeUpdate();
		em.clear();

		String newS3Key = newKey();
		videoService.replaceVideo(userId, videoId, replaceRequest(newS3Key, null, null));
		em.flush();

		Object[] row = videoRow(videoId);
		// 저장되는 건 클라이언트가 준 pending 키가 아니라 확정 시 옮겨진 original 키다 (MSG-133).
		// 키는 pendingStem 을 보존한 시도별 발급이라(MSG-247 2R) 정확값 대신 파생 prefix 로 단언한다.
		assertThat((String) row[0]).as("새 파일로 교체")
			.startsWith(newS3Key.replace("videos/pending/", "videos/original/").replace(".mp4", "-"))
			.endsWith(".mp4");
		assertThat(row[1]).as("재인코딩 대기").isEqualTo("UPLOADED");
		assertThat(row[2]).as("옛 인코딩본은 비워야 한다").isNull();
		assertThat(row[3]).as("옛 썸네일도 비워야 한다").isNull();
		assertThat(((Number) row[4]).shortValue()).as("길이 갱신").isEqualTo((short) 7);
	}

	@Test
	@DisplayName("교체하면 recorded_at 이 요청의 새 촬영 시각으로 갱신된다")
	void 교체하면_recordedAt이_요청_값으로_갱신된다() {
		LocalDateTime 옛시각 = LocalDateTime.of(2026, 6, 1, 10, 0);
		LocalDateTime 새시각 = LocalDateTime.of(2026, 7, 24, 18, 0);
		long videoId = videoService.saveVideo(userId, new VideoUploadRequestDto(
			newKey(), 잠실_LAT, 잠실_LON, (short) 10, 옛시각, "PRIVATE")).videoId();

		videoService.replaceVideo(userId, videoId, new VideoReplaceRequestDto(newKey(), null, null, (short) 7, 새시각));
		em.flush();

		assertThat((LocalDateTime) videoRow(videoId)[5])
			.as("옛 촬영 시각이 아니라 교체 요청의 새 촬영 시각").isEqualTo(새시각);
	}

	@Test
	@DisplayName("교체해도 도감은 전혀 변하지 않는다 — 이 티켓의 핵심")
	void 교체해도_도감은_불변이다() {
		long first = upload();
		upload();
		Object[] before = userGridRow();

		videoService.replaceVideo(userId, first, replaceRequest(newKey(), null, null));
		em.flush();

		Object[] after = userGridRow();
		assertThat(after[0]).as("video_count 불변").isEqualTo(before[0]);
		assertThat(after[1]).as("cover_video_id 불변 — 같은 row라 id가 안 바뀐다").isEqualTo(before[1]);
	}

	@Test
	void 좌표를_안_보내면_기존_격자를_유지하고_통과한다() {
		long videoId = upload();

		assertThatCode(() -> videoService.replaceVideo(userId, videoId, replaceRequest(newKey(), null, null)))
			.doesNotThrowAnyException();
	}

	@Test
	void 같은_격자_좌표면_통과한다() {
		long videoId = upload();

		assertThatCode(() -> videoService.replaceVideo(
			userId, videoId, replaceRequest(newKey(), 잠실_LAT, 잠실_LON))).doesNotThrowAnyException();
	}

	@Test
	void 다른_격자_좌표는_GRID_MISMATCH_다() {
		long videoId = upload();

		assertThatThrownBy(() -> videoService.replaceVideo(
			userId, videoId, replaceRequest(newKey(), 광화문_LAT, 광화문_LON)))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.GRID_MISMATCH);
	}

	@Test
	void 좌표를_하나만_보내면_거부된다() {
		long videoId = upload();

		assertThatThrownBy(() -> videoService.replaceVideo(
			userId, videoId, replaceRequest(newKey(), 잠실_LAT, null)))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.INVALID_COORDINATE);
	}

	@Test
	@DisplayName("교체로도 가짜 s3Key를 밀어넣을 수 없다 — MSG-132 검증이 이 경로에도 걸린다")
	void 가짜_s3Key로는_교체할_수_없다() {
		long videoId = upload();

		assertThatThrownBy(() -> videoService.replaceVideo(userId, videoId, replaceRequest("아무거나", null, null)))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.INVALID_S3_KEY);
	}

	@Test
	void 타인의_영상은_교체할_수_없다() {
		long videoId = upload();
		Long otherUserId = userRepository.save(
			User.createLocalUser("other-replacer@example.com", "hash", "타인")).getId();

		assertThatThrownBy(() -> videoService.replaceVideo(otherUserId, videoId, replaceRequest(newKey(), null, null)))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.VIDEO_FORBIDDEN);
	}

	@Test
	void 없는_영상은_교체할_수_없다() {
		assertThatThrownBy(() -> videoService.replaceVideo(userId, 999_999L, replaceRequest(newKey(), null, null)))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.VIDEO_NOT_FOUND);
	}

	@Test
	void 삭제된_영상은_교체할_수_없다() {
		long videoId = upload();
		videoService.deleteVideo(userId, videoId);

		assertThatThrownBy(() -> videoService.replaceVideo(userId, videoId, replaceRequest(newKey(), null, null)))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.VIDEO_NOT_FOUND);
	}
}
