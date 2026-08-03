package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

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
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.exception.VideoErrorCode;

/**
 * 삭제 + 점령 롤백 (MSG-72).
 *
 * 카운트는 모두 user_id 로 좁혀서 센다 — videos 를 격자 단위로만 세면 다른 사용자·다른 테스트가 같은
 * 격자에 올린 영상까지 잡혀 깨진다 (VideoServiceIntegrationTest 가 실제로 그 방식이라 로컬 E2E 데이터에
 * 오염된 적이 있다).
 */
@SpringBootTest
@Transactional
@DisplayName("영상 삭제 · 점령 롤백 (실 PostGIS)")
class VideoDeleteIntegrationTest {

	// 다른 테스트(성수)와 겹치지 않는 좌표.
	private static final double 여의도_LAT = 37.5219;
	private static final double 여의도_LON = 126.9245;

	@Autowired
	private VideoService videoService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	/**
	 * saveVideo 가 s3Key 실존을 headObject 로 확인한다(MSG-132). 목이 없으면 실제 S3 를 호출해
	 * CI(자격증명 없음)에서 깨진다. 기본 스텁은 "객체가 있다" = 정상 업로드를 마친 상태다.
	 */
	@MockitoBean
	private S3Client s3Client;

	private Long userId;
	private String gridId;

	@BeforeEach
	void setUp() {
		given(s3Client.headObject(any(HeadObjectRequest.class))).willReturn(HeadObjectResponse.builder().build());
		userId = userRepository.save(User.createLocalUser("deleter@example.com", "hash", "삭제자")).getId();
		gridId = GridEncoder.encode(여의도_LAT, 여의도_LON);
	}

	private long upload() {
		return videoService.saveVideo(userId, new VideoUploadRequestDto(
			"videos/pending/" + userId + "/" + System.nanoTime() + ".mp4",
			여의도_LAT, 여의도_LON, (short) 10, LocalDateTime.now(ZoneOffset.UTC), "PRIVATE")).videoId();
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

	private String statusOf(long videoId) {
		return (String) em.createNativeQuery("SELECT status FROM videos WHERE id = :i")
			.setParameter("i", videoId)
			.getSingleResult();
	}

	private long gridRows() {
		return ((Number) em.createNativeQuery("SELECT count(*) FROM grids WHERE grid_id = :g")
			.setParameter("g", gridId)
			.getSingleResult()).longValue();
	}

	@Test
	void 마지막_영상을_지우면_점령이_풀리고_격자는_남는다() {
		long videoId = upload();
		assertThat(userGridCount()).isEqualTo(1);

		videoService.deleteVideo(userId, videoId);

		assertThat(statusOf(videoId)).isEqualTo("DELETED");   // soft delete — row 는 남는다
		assertThat(userGridCount()).as("점령 롤백").isZero();
		assertThat(gridRows()).as("전역 격자 등록은 영구 (D5)").isEqualTo(1);
	}

	@Test
	void 두_개_중_하나만_지우면_점령은_유지되고_카운트만_준다() {
		long first = upload();
		upload();
		assertThat(videoCount()).isEqualTo(2);

		videoService.deleteVideo(userId, first);

		assertThat(userGridCount()).as("점령 유지").isEqualTo(1);
		assertThat(videoCount()).isEqualTo(1);
	}

	@Test
	void cover_영상을_지우면_남은_영상_중_가장_오래된_것으로_재선정된다() {
		long first = upload();
		long second = upload();
		assertThat(coverVideoId()).as("첫 업로드가 cover").isEqualTo(first);

		videoService.deleteVideo(userId, first);

		assertThat(coverVideoId()).as("남은 영상으로 재선정 (D4)").isEqualTo(second);
	}

	@Test
	void cover_가_아닌_영상을_지우면_cover_는_그대로다() {
		long first = upload();
		long second = upload();

		videoService.deleteVideo(userId, second);

		assertThat(coverVideoId()).isEqualTo(first);
	}

	@Test
	void 타인의_영상은_지울_수_없다() {
		long videoId = upload();
		Long otherUserId = userRepository.save(
			User.createLocalUser("other@example.com", "hash", "타인")).getId();

		assertThatThrownBy(() -> videoService.deleteVideo(otherUserId, videoId))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.VIDEO_FORBIDDEN);

		assertThat(statusOf(videoId)).as("타인 시도로 상태가 바뀌면 안 된다").isEqualTo("ACTIVE");
	}

	@Test
	void 없는_영상을_지우면_VIDEO_NOT_FOUND_다() {
		assertThatThrownBy(() -> videoService.deleteVideo(userId, 999_999L))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.VIDEO_NOT_FOUND);
	}

	@Test
	void 이미_지운_영상을_다시_지워도_멱등하다() {
		long videoId = upload();
		videoService.deleteVideo(userId, videoId);

		// 두 번째 호출이 예외를 던지거나 video_count 를 또 깎으면 안 된다 (D7).
		assertThatCode(() -> videoService.deleteVideo(userId, videoId)).doesNotThrowAnyException();

		assertThat(userGridCount()).isZero();
	}

	@Test
	void 같은_격자에_영상이_둘인데_둘_다_지우면_점령이_풀린다() {
		long first = upload();
		long second = upload();

		videoService.deleteVideo(userId, first);
		assertThat(userGridCount()).isEqualTo(1);

		videoService.deleteVideo(userId, second);
		assertThat(userGridCount()).as("마지막이 사라지면 롤백").isZero();
	}
}
