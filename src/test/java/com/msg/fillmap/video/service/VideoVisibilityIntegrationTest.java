package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.Optional;
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
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.dto.VideoVisibilityRequestDto;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.exception.VideoErrorCode;
import com.msg.fillmap.video.repository.VideoRepository;

/**
 * 영상 공개 범위 전환 (MSG-162). 소유자가 자기 영상의 visibility 를 PUBLIC↔PRIVATE 로 돌린다.
 * 이 티켓은 MSG-87 전역 대표(PUBLIC·READY 필터)의 노출 대상을 실데이터로 채우는 게 목적이라,
 * 마지막 테스트가 "PRIVATE 만 있어 대표가 null 이던 격자가 공개 전환으로 대표가 잡히는지"를 실 PostGIS 로 본다.
 */
@SpringBootTest
@Transactional
@DisplayName("영상 공개 범위 전환 (실 PostGIS)")
class VideoVisibilityIntegrationTest {

	// 다른 테스트와 겹치지 않는 좌표 (구로디지털단지 인근).
	private static final double 구로_LAT = 37.4851;
	private static final double 구로_LON = 126.9014;

	@Autowired
	private VideoService videoService;

	@Autowired
	private VideoRepository videoRepository;

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
		userId = userRepository.save(User.createLocalUser("visibility@example.com", "hash", "공개전환자")).getId();
		gridId = GridEncoder.encode(구로_LAT, 구로_LON);
	}

	private String newKey() {
		return "videos/pending/" + userId + "/" + UUID.randomUUID() + ".mp4";
	}

	/** PRIVATE 명시 업로드 — MSG-204 부터 미지정은 PUBLIC 이라, "PRIVATE→PUBLIC 전환" 전제를 명시로 보존한다. */
	private long upload() {
		return videoService.saveVideo(userId, new VideoUploadRequestDto(
			newKey(), 구로_LAT, 구로_LON, (short) 10, LocalDateTime.now(), "PRIVATE")).videoId();
	}

	private VideoVisibilityRequestDto request(String visibility) {
		return new VideoVisibilityRequestDto(visibility);
	}

	private String visibilityOf(long videoId) {
		em.flush();
		return (String) em.createNativeQuery("SELECT visibility FROM videos WHERE id = :i")
			.setParameter("i", videoId)
			.getSingleResult();
	}

	@Test
	void 본인_영상을_PUBLIC으로_전환하면_visibility가_PUBLIC이_된다() {
		long videoId = upload();

		assertThat(videoService.setVisibility(userId, videoId, request("PUBLIC")).visibility()).isEqualTo("PUBLIC");
		assertThat(visibilityOf(videoId)).as("DB 에도 반영된다").isEqualTo("PUBLIC");
	}

	@Test
	void 본인_영상을_다시_PRIVATE로_전환할_수_있다() {
		long videoId = upload();
		videoService.setVisibility(userId, videoId, request("PUBLIC"));

		assertThat(videoService.setVisibility(userId, videoId, request("PRIVATE")).visibility()).isEqualTo("PRIVATE");
		assertThat(visibilityOf(videoId)).isEqualTo("PRIVATE");
	}

	@Test
	void 타인_영상을_전환하면_VIDEO_FORBIDDEN이다() {
		long videoId = upload();
		Long otherUserId = userRepository.save(
			User.createLocalUser("other-visibility@example.com", "hash", "타인")).getId();

		assertThatThrownBy(() -> videoService.setVisibility(otherUserId, videoId, request("PUBLIC")))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.VIDEO_FORBIDDEN);
	}

	@Test
	void 없는_영상을_전환하면_VIDEO_NOT_FOUND다() {
		assertThatThrownBy(() -> videoService.setVisibility(userId, 999_999L, request("PUBLIC")))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.VIDEO_NOT_FOUND);
	}

	@Test
	void 삭제된_영상을_전환하면_VIDEO_NOT_FOUND다() {
		long videoId = upload();
		videoService.deleteVideo(userId, videoId);

		assertThatThrownBy(() -> videoService.setVisibility(userId, videoId, request("PUBLIC")))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.VIDEO_NOT_FOUND);
	}

	@Test
	void PUBLIC_PRIVATE가_아닌_값이면_INVALID_VISIBILITY다() {
		long videoId = upload();

		assertThatThrownBy(() -> videoService.setVisibility(userId, videoId, request("BOGUS")))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.INVALID_VISIBILITY);
	}

	@Test
	void 이미_PUBLIC인_영상을_다시_PUBLIC으로_전환하면_성공한다() {
		long videoId = upload();
		videoService.setVisibility(userId, videoId, request("PUBLIC"));

		assertThatCode(() -> videoService.setVisibility(userId, videoId, request("PUBLIC")))
			.doesNotThrowAnyException();
		assertThat(visibilityOf(videoId)).isEqualTo("PUBLIC");
	}

	@Test
	void 인코딩중_UPLOADED_영상도_PUBLIC으로_전환된다() {
		long videoId = upload();   // 업로드 직후는 UPLOADED — 노출 게이트는 read 경로 책임이라 전환은 허용된다

		assertThat(videoService.setVisibility(userId, videoId, request("PUBLIC")).visibility()).isEqualTo("PUBLIC");
	}

	@Test
	@DisplayName("동시 삭제가 반영된 뒤 공개 전환을 커밋해도 삭제가 되살아나지 않는다 (@DynamicUpdate lost-update 가드)")
	void 동시_삭제_후_공개_전환은_삭제를_되살리지_않는다() {
		long videoId = upload();
		em.flush();
		em.find(Video.class, videoId);   // tx1 스냅샷 확보: status=ACTIVE (managed)

		// 동시 삭제를 흉내 — persistence context 밖에서 DB row 만 DELETED 로 바꾼다.
		// native UPDATE 는 managed 스냅샷을 갱신하지 않으므로 방금 로드한 엔티티는 여전히 ACTIVE 다.
		em.createNativeQuery("UPDATE videos SET status='DELETED' WHERE id = :i")
			.setParameter("i", videoId)
			.executeUpdate();

		// 공개 전환(자기 축)만 커밋한다. @DynamicUpdate 가 없으면 전체 컬럼 UPDATE 라 스냅샷의
		// status=ACTIVE 가 함께 실려 DELETED 를 덮어써 삭제를 부활시킨다 — 그게 이 가드가 막는 lost update 다.
		videoService.setVisibility(userId, videoId, request("PUBLIC"));
		em.flush();
		em.clear();

		String status = (String) em.createNativeQuery("SELECT status FROM videos WHERE id = :i")
			.setParameter("i", videoId)
			.getSingleResult();
		assertThat(status).as("공개 전환이 삭제를 되살리면 안 된다 — 자기 dirty 컬럼만 UPDATE 돼야 한다")
			.isEqualTo("DELETED");
	}

	@Test
	@DisplayName("PRIVATE만 있던 격자는 대표가 null이지만 PUBLIC·READY로 전환하면 대표로 잡힌다 (MSG-87 연동)")
	void PRIVATE만_있던_격자는_대표가_null이지만_PUBLIC_READY로_전환하면_대표로_잡힌다() {
		long videoId = upload();
		assertThat(findCover()).as("업로드 직후엔 PRIVATE·UPLOADED 라 대표 없음").isEmpty();

		// 인코딩 완료를 흉내 — 대표 후보 조건 중 READY 를 맞춘다. visibility 는 아직 PRIVATE.
		em.createNativeQuery("UPDATE videos SET processing_status='READY', encoded_url='enc.mp4',"
				+ " thumbnail_url='th.jpg' WHERE id = :i")
			.setParameter("i", videoId)
			.executeUpdate();
		em.clear();

		videoService.setVisibility(userId, videoId, request("PUBLIC"));

		assertThat(findCover()).as("공개 전환으로 findGlobalCover 가 실데이터로 대표를 반환한다")
			.map(Video::getId).contains(videoId);
	}

	private Optional<Video> findCover() {
		em.flush();
		em.clear();
		return videoRepository.findGlobalCover(gridId);
	}
}
