package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.dto.VideoUploadResponseDto;
import com.msg.fillmap.video.exception.VideoErrorCode;

@SpringBootTest
@Transactional
@DisplayName("VideoService 통합 (실 PostGIS)")
class VideoServiceIntegrationTest {

	private static final double 성수_LAT = 37.5445;
	private static final double 성수_LON = 127.0560;

	@Autowired
	private VideoService videoService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	private Long userId;

	@BeforeEach
	void setUp() {
		User user = userRepository.save(User.createLocalUser("video-tester@example.com", "hash", "테스터"));
		userId = user.getId();
	}

	private VideoUploadRequestDto request(double lat, double lon) {
		return new VideoUploadRequestDto(
			"videos/original/" + userId + "/uuid.mp4", lat, lon, (short) 10, LocalDateTime.now());
	}

	private long countRows(String sql, String gridId) {
		return ((Number) em.createNativeQuery(sql)
			.setParameter("g", gridId)
			.getSingleResult()).longValue();
	}

	@Test
	@DisplayName("업로드하면 videos·user_grids·grids row 가 생성되고 첫 점령은 occupied=true 다")
	void 업로드하면_row가_생성되고_첫점령이다() {
		VideoUploadResponseDto response = videoService.saveVideo(userId, request(성수_LAT, 성수_LON));

		String gridId = GridEncoder.encode(성수_LAT, 성수_LON);
		assertThat(response.videoId()).isNotNull();
		assertThat(response.gridId()).isEqualTo(gridId);
		assertThat(response.processingStatus()).isEqualTo("UPLOADED");
		assertThat(response.occupied()).isTrue();

		assertThat(countRows("SELECT count(*) FROM grids WHERE grid_id = :g", gridId)).isEqualTo(1);
		assertThat(countRows("SELECT count(*) FROM videos WHERE grid_id = :g", gridId)).isEqualTo(1);
		assertThat(countRows("SELECT video_count FROM user_grids WHERE grid_id = :g", gridId)).isEqualTo(1);
	}

	@Test
	@DisplayName("같은 좌표로 두 번 업로드하면 grids row 는 하나, video_count 는 2, 재방문은 occupied=false 다")
	void 같은좌표_두번이면_격자멱등_카운트증가() {
		VideoUploadResponseDto first = videoService.saveVideo(userId, request(성수_LAT, 성수_LON));
		VideoUploadResponseDto second = videoService.saveVideo(userId, request(성수_LAT, 성수_LON));

		String gridId = GridEncoder.encode(성수_LAT, 성수_LON);
		assertThat(first.occupied()).isTrue();
		assertThat(second.occupied()).isFalse();

		assertThat(countRows("SELECT count(*) FROM grids WHERE grid_id = :g", gridId)).isEqualTo(1);
		assertThat(countRows("SELECT count(*) FROM videos WHERE grid_id = :g", gridId)).isEqualTo(2);
		assertThat(countRows("SELECT video_count FROM user_grids WHERE grid_id = :g", gridId)).isEqualTo(2);
	}

	@Test
	@DisplayName("서비스 범위 밖 좌표는 INVALID_COORDINATE 를 던진다")
	void 범위밖_좌표는_거부된다() {
		assertThatThrownBy(() -> videoService.saveVideo(userId, request(0.0, 0.0)))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.INVALID_COORDINATE);
	}
}
