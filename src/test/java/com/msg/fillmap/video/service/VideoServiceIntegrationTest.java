package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

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
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.dto.VideoUploadResponseDto;
import com.msg.fillmap.video.exception.VideoErrorCode;
import com.msg.fillmap.video.repository.VideoRepository;

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
	private VideoRepository videoRepository;

	@Autowired
	private EntityManager em;

	/**
	 * saveVideo 가 s3Key 실존을 headObject 로 확인한다(MSG-132). 목이 없으면 테스트가 실제 S3 를 호출해
	 * CI(자격증명 없음)에서 깨진다. 기본 스텁은 "객체가 있다" = 정상 업로드를 마친 상태다.
	 */
	@MockitoBean
	private S3Client s3Client;

	private Long userId;

	@BeforeEach
	void setUp() {
		given(s3Client.headObject(any(HeadObjectRequest.class))).willReturn(HeadObjectResponse.builder().build());
		User user = userRepository.save(User.createLocalUser("video-tester@example.com", "hash", "테스터"));
		userId = user.getId();
	}

	/** s3Key 는 매번 새로 만든다 — presign 이 호출마다 새 UUID 를 발급하고, 재사용은 이제 거부된다(MSG-132). */
	private VideoUploadRequestDto request(double lat, double lon) {
		return request(lat, lon, null);   // visibility 미지정 = 기본 PUBLIC (MSG-204)
	}

	private VideoUploadRequestDto request(double lat, double lon, String visibility) {
		return new VideoUploadRequestDto(
			"videos/pending/" + userId + "/" + java.util.UUID.randomUUID() + ".mp4",
			lat, lon, (short) 10, LocalDateTime.now(ZoneOffset.UTC), visibility);
	}

	private String visibilityOf(long videoId) {
		em.flush();
		return (String) em.createNativeQuery("SELECT visibility FROM videos WHERE id = :i")
			.setParameter("i", videoId)
			.getSingleResult();
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

	// 업로드 시 공개범위 지정 (MSG-204). 미지정은 PUBLIC, 지정값 저장, 오류값은 S3 부수효과 없이 3420.

	@Test
	void 업로드에_visibility를_생략하면_PUBLIC으로_저장된다() {
		long videoId = videoService.saveVideo(userId, request(성수_LAT, 성수_LON)).videoId();

		assertThat(visibilityOf(videoId)).isEqualTo("PUBLIC");
	}

	@Test
	void 업로드에_PRIVATE를_지정하면_PRIVATE로_저장된다() {
		long videoId = videoService.saveVideo(userId, request(성수_LAT, 성수_LON, "PRIVATE")).videoId();

		assertThat(visibilityOf(videoId)).isEqualTo("PRIVATE");
	}

	@Test
	void 업로드에_PUBLIC을_지정하면_PUBLIC으로_저장된다() {
		long videoId = videoService.saveVideo(userId, request(성수_LAT, 성수_LON, "PUBLIC")).videoId();

		assertThat(visibilityOf(videoId)).isEqualTo("PUBLIC");
	}

	@Test
	void 업로드_visibility는_소문자도_허용된다() {
		// parseVisibility 의 toUpperCase 관용 — MSG-162 전환 API 와 동일
		long videoId = videoService.saveVideo(userId, request(성수_LAT, 성수_LON, "private")).videoId();

		assertThat(visibilityOf(videoId)).isEqualTo("PRIVATE");
	}

	@Test
	void 업로드에_PUBLIC_PRIVATE가_아닌_값이면_INVALID_VISIBILITY고_영상은_저장되지_않는다() {
		assertThatThrownBy(() -> videoService.saveVideo(userId, request(성수_LAT, 성수_LON, "FRIENDS")))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.INVALID_VISIBILITY);

		String gridId = GridEncoder.encode(성수_LAT, 성수_LON);
		assertThat(countRows("SELECT count(*) FROM videos WHERE grid_id = :g", gridId)).isZero();
		// 파싱이 confirmUpload 보다 먼저라 S3 검증(headObject)·원본 복사(copyObject)가 아예 없어야 한다 (FR-3).
		then(s3Client).should(never()).headObject(any(HeadObjectRequest.class));
		then(s3Client).should(never()).copyObject(any(CopyObjectRequest.class));
	}

	@Test
	void 업로드_직후_PUBLIC이어도_READY_전엔_전역_노출면에_안_잡힌다() {
		videoService.saveVideo(userId, request(성수_LAT, 성수_LON));   // 기본 PUBLIC · processing_status=UPLOADED

		String gridId = GridEncoder.encode(성수_LAT, 성수_LON);
		em.flush();
		em.clear();
		assertThat(videoRepository.findGlobalCover(gridId))
			.as("전역 노출 게이트는 visibility='PUBLIC' AND processing_status='READY' — READY 전엔 비노출 (FR-4)")
			.isEmpty();
	}
}
