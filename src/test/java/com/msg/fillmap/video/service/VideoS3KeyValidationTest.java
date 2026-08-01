package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

import java.time.LocalDateTime;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.exception.VideoErrorCode;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * s3Key 검증 (MSG-132).
 *
 * FillMap 은 "직접 가서 찍어야 격자를 채운다"는 게임이다. 파일을 올리지 않고 좌표만 찍어서 격자를
 * 점령할 수 있으면 게임이 성립하지 않는다.
 *
 * S3Client 를 목으로 대체해 실제 S3 없이 "객체가 있다/없다"를 제어한다.
 */
@SpringBootTest
@Transactional
@DisplayName("s3Key 검증 — 파일 없이 점령 차단")
class VideoS3KeyValidationTest {

	private static final double 강남_LAT = 37.4979;
	private static final double 강남_LON = 127.0276;

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
		userId = userRepository.save(User.createLocalUser("s3key@example.com", "hash", "검증")).getId();
		gridId = GridEncoder.encode(강남_LAT, 강남_LON);
		// 기본값: 객체가 S3 에 존재한다 (정상 업로드를 마친 상태)
		given(s3Client.headObject(any(HeadObjectRequest.class))).willReturn(HeadObjectResponse.builder().build());
	}

	private VideoUploadRequestDto request(String s3Key) {
		return new VideoUploadRequestDto(s3Key, 강남_LAT, 강남_LON, (short) 10, LocalDateTime.now(), "PRIVATE");
	}

	private String myKey() {
		return "videos/pending/" + userId + "/" + java.util.UUID.randomUUID() + ".mp4";
	}

	private long userGridCount() {
		return ((Number) em.createNativeQuery(
				"SELECT count(*) FROM user_grids WHERE user_id = :u AND grid_id = :g")
			.setParameter("u", userId).setParameter("g", gridId)
			.getSingleResult()).longValue();
	}

	@Test
	@DisplayName("S3 에 없는 s3Key 로는 저장도 점령도 안 된다 — 핵심")
	void 올리지_않은_s3Key는_거부된다() {
		// 업로드하지 않았으므로 S3 는 객체가 없다고 답한다.
		willThrow(NoSuchKeyException.builder().message("not found").build())
			.given(s3Client).headObject(any(HeadObjectRequest.class));

		assertThatThrownBy(() -> videoService.saveVideo(userId, request(myKey())))
			.isInstanceOf(ApiException.class);

		assertThat(userGridCount()).as("파일 없이 격자가 점령되면 안 된다").isZero();
	}

	@Test
	void 형식이_아예_다른_s3Key는_거부된다() {
		assertThatThrownBy(() -> videoService.saveVideo(userId, request("아무거나")))
			.isInstanceOf(ApiException.class);

		assertThat(userGridCount()).isZero();
	}

	@Test
	void 타인_경로의_s3Key는_거부된다() {
		String othersKey = "videos/pending/" + (userId + 999) + "/" + java.util.UUID.randomUUID() + ".mp4";

		assertThatThrownBy(() -> videoService.saveVideo(userId, request(othersKey)))
			.isInstanceOf(ApiException.class);

		assertThat(userGridCount()).isZero();
	}

	@Test
	void 같은_s3Key_를_두_번_확정할_수_없다() {
		String key = myKey();
		videoService.saveVideo(userId, request(key));

		// 영상 하나로 좌표만 바꿔가며 무한 점령하는 걸 막는다.
		//
		// 에러코드까지 못박는 이유(MSG-133): 확정 시 pending → original 로 키가 바뀌므로 중복 조회를
		// original 키로 해야 한다. pending 키로 조회하면 DB 에 없어 영영 매치되지 않고, UNIQUE 제약이
		// 4xx 대신 500 을 낸다. Exception 만 보면 그 퇴행을 놓친다.
		assertThatThrownBy(() -> videoService.saveVideo(userId, request(key)))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", VideoErrorCode.INVALID_S3_KEY);
	}

	@Test
	@DisplayName("정상 업로드(실제로 S3 에 있는 내 키)는 그대로 통과한다")
	void 정상_업로드는_통과한다() {
		assertThatCode(() -> videoService.saveVideo(userId, request(myKey())))
			.doesNotThrowAnyException();

		assertThat(userGridCount()).isEqualTo(1);
	}
}
