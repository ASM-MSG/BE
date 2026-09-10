package com.msg.fillmap.video.service;

import static com.msg.fillmap.video.support.S3VideoObjectStub.givenUploadedVideoObject;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.mission.dto.MissionVideoUploadRequestDto;
import com.msg.fillmap.mission.exception.MissionErrorCode;
import com.msg.fillmap.mission.service.MissionVideoService;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.dto.VideoReplaceRequestDto;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.exception.VideoErrorCode;

/**
 * 업로드 확정의 영상 컨테이너 검사 (MSG-392, 실 PostgreSQL). 판별 규칙 자체는 VideoSignatureTest 가
 * 바이트 배열로 보고, 여기서는 <b>거부의 효과</b>(점령·뱃지·스트릭·스탬프가 하나도 안 남는가)와
 * <b>wholeObject 산출</b>(contentLength 우선 규칙)을 본다 — 둘 다 DB 부수효과와 S3 응답 조합이라
 * 실 스택으로만 관측된다.
 * <p>
 * 격리(공유 로컬 DB): 서해 먼바다 격자(125.7 대역)만 쓰고 {@code @Transactional} 롤백으로 정리한다 —
 * 육상 실데이터·행사(125.0~125.4)·미션(125.5) 테스트와 겹치지 않는 대역이다.
 */
@SpringBootTest
@Transactional
@DisplayName("업로드 확정의 영상 컨테이너 검사 (실 PostgreSQL)")
class VideoSignatureIntegrationTest {

	private static final double 바다_LAT = 34.0;
	private static final double 바다_LON = 125.7;

	@Autowired
	private VideoService videoService;

	@Autowired
	private MissionVideoService missionVideoService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@MockitoBean
	private S3Client s3Client;

	private long userId;

	@BeforeEach
	void setUp() {
		userId = userRepository.save(User.createLocalUser(
			"msg392-" + UUID.randomUUID() + "@example.com", "hash", "업로더")).getId();
		givenUploadedVideoObject(s3Client);   // 기본은 정상 업로드를 마친 상태 — 케이스마다 덮어쓴다
	}

	@Nested
	@DisplayName("영상이 아닌 파일은 확정되지 않는다")
	class 거부의_효과 {

		// 검증: FR-VIDEO-03
		@Test
		@DisplayName("영상이 아닌 파일로 확정하면 거부되고 격자도 뱃지도 스트릭도 남지 않는다")
		void 영상이_아닌_파일로_확정하면_거부되고_격자도_뱃지도_스트릭도_남지_않는다() {
			givenUploadedVideoObject(s3Client, 텍스트());

			assertThatThrownBy(() -> videoService.saveVideo(userId, 요청(키())))
				.isInstanceOf(ApiException.class)
				.extracting("errorCode")
				.isEqualTo(VideoErrorCode.NOT_A_VIDEO_FILE);

			assertThat(카운트("videos")).isZero();
			assertThat(카운트("user_grids")).isZero();
			assertThat(카운트("user_badges")).isZero();
			assertThat(카운트("streaks")).isZero();
		}

		// 검증: FR-VIDEO-03
		@Test
		@DisplayName("미션 경유 업로드가 거부되면 12409로 가려지고 스탬프도 찍히지 않는다")
		void 미션_경유_업로드가_거부되면_12409로_가려지고_스탬프도_찍히지_않는다() {
			long missionId = 진행중_팝업();
			givenUploadedVideoObject(s3Client, 텍스트());

			// 미션 경로는 영상 도메인 실패를 전부 마스킹한다 — 이 티켓의 새 실패에도 존재 은닉이 살아 있다.
			assertThatThrownBy(() -> missionVideoService.upload(userId, missionId,
				new MissionVideoUploadRequestDto(키(), (short) 10, 한시간_전())))
				.isInstanceOf(ApiException.class)
				.extracting("errorCode")
				.isEqualTo(MissionErrorCode.MISSION_UPLOAD_UNAVAILABLE);

			assertThat(카운트("user_missions")).isZero();
			assertThat(카운트("videos")).isZero();

			// 12409 가 파일 때문이지 미션 조회 실패 때문이 아님을 못박는다 — 마스킹이 모든 실패를 한 코드로
			// 덮으므로, 같은 미션이 정상 파일로는 통과한다는 것까지 봐야 위 단언이 의미를 갖는다.
			givenUploadedVideoObject(s3Client);
			missionVideoService.upload(userId, missionId,
				new MissionVideoUploadRequestDto(키(), (short) 10, 한시간_전()));

			assertThat(카운트("user_missions")).isEqualTo(1);
		}

		// 검증: FR-VIDEO-08
		@Test
		@DisplayName("교체도 같은 검사를 지난다")
		void 교체도_같은_검사를_지난다() {
			long videoId = videoService.saveVideo(userId, 요청(키())).videoId();
			String 원본키 = 원본키(videoId);
			givenUploadedVideoObject(s3Client, 텍스트());

			assertThatThrownBy(() -> videoService.replaceVideo(userId, videoId,
				new VideoReplaceRequestDto(키(), null, null, (short) 7, 한시간_전())))
				.isInstanceOf(ApiException.class)
				.extracting("errorCode")
				.isEqualTo(VideoErrorCode.NOT_A_VIDEO_FILE);

			assertThat(원본키(videoId)).isEqualTo(원본키);
		}

		// 검증: FR-VIDEO-03
		@Test
		@DisplayName("정상 mp4 헤더면 확정이 성공한다")
		void 정상_mp4_헤더면_확정이_성공한다() {
			assertThat(videoService.saveVideo(userId, 요청(키())).videoId()).isPositive();
			assertThat(카운트("videos")).isEqualTo(1);
		}
	}

	@Nested
	@DisplayName("파일 끝은 contentLength 로 판정한다")
	class 파일_끝_판정 {

		// 검증: FR-VIDEO-03
		@Test
		@DisplayName("정확히 4096바이트인 잘린 객체도 확정에서 거부된다")
		void 정확히_4096바이트인_잘린_객체도_확정에서_거부된다() {
			// 받은 바이트 수만 보면 요청량(4096)과 같아 창 소진으로 오분류된다 — contentLength 를 먼저 쓰는 이유.
			givenUploadedVideoObject(s3Client, 크기가_파일_밖인_mdat(4096));

			assertThatThrownBy(() -> videoService.saveVideo(userId, 요청(키())))
				.isInstanceOf(ApiException.class)
				.extracting("errorCode")
				.isEqualTo(VideoErrorCode.NOT_A_VIDEO_FILE);
		}

		// 검증: FR-VIDEO-03
		@Test
		@DisplayName("contentLength가 없으면 받은 바이트 수로 파일 끝을 판정한다")
		void contentLength가_없으면_받은_바이트_수로_파일_끝을_판정한다() {
			// contentLength 가 null 이면 8바이트 미만 지름길도 건너뛰고 범위 요청 결과만으로 판정한다.
			given(s3Client.headObject(any(HeadObjectRequest.class)))
				.willReturn(HeadObjectResponse.builder().build());
			given(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
				.willReturn(응답(크기가_파일_밖인_mdat(200)));

			assertThatThrownBy(() -> videoService.saveVideo(userId, 요청(키())))
				.isInstanceOf(ApiException.class)
				.extracting("errorCode")
				.isEqualTo(VideoErrorCode.NOT_A_VIDEO_FILE);
		}
	}

	@Nested
	@DisplayName("S3 갈래마다 실패 코드가 다르다")
	class S3_오류 {

		// 검증: FR-VIDEO-03
		@Test
		@DisplayName("범위 요청이 416이면 3428로 거부된다")
		void 범위_요청이_416이면_3428로_거부된다() {
			// head 와 범위 요청 사이에 같은 키가 빈 객체로 덮어쓰인 경합 — 그 자리에 읽을 내용이 없다.
			given(s3Client.headObject(any(HeadObjectRequest.class)))
				.willReturn(HeadObjectResponse.builder().contentLength(100L).build());
			given(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
				.willThrow(S3Exception.builder().statusCode(416).message("Requested Range Not Satisfiable").build());

			assertThatThrownBy(() -> videoService.saveVideo(userId, 요청(키())))
				.isInstanceOf(ApiException.class)
				.extracting("errorCode")
				.isEqualTo(VideoErrorCode.NOT_A_VIDEO_FILE);
		}

		// 검증: FR-VIDEO-03
		@Test
		@DisplayName("여덟 바이트 미만 객체는 본문을 읽지 않고 거부된다")
		void 여덟_바이트_미만_객체는_본문을_읽지_않고_거부된다() {
			givenUploadedVideoObject(s3Client, new byte[] {0x00});

			assertThatThrownBy(() -> videoService.saveVideo(userId, 요청(키())))
				.isInstanceOf(ApiException.class)
				.extracting("errorCode")
				.isEqualTo(VideoErrorCode.NOT_A_VIDEO_FILE);
			then(s3Client).should(never()).getObjectAsBytes(any(GetObjectRequest.class));
		}

		// 검증: FR-VIDEO-08
		@Test
		@DisplayName("범위 요청 중 객체가 사라지면 3402로 거부된다")
		void 범위_요청_중_객체가_사라지면_3402로_거부된다() {
			// 416(빈 객체로 덮어쓰기)과 같은 경합 창의 변종이라 같은 급으로 다룬다 — 정상 경합이 500 알람을
			// 울리지 않게, 존재 확인이 쓰는 코드로 수렴시킨다.
			given(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
				.willThrow(NoSuchKeyException.builder().build());

			assertThatThrownBy(() -> videoService.saveVideo(userId, 요청(키())))
				.isInstanceOf(ApiException.class)
				.extracting("errorCode")
				.isEqualTo(VideoErrorCode.UPLOAD_NOT_FOUND);
		}

		// 검증: FR-VIDEO-08
		@Test
		@DisplayName("S3 오류는 3428이 아니라 그대로 전파되고 확정도 실패한다")
		void S3_오류는_3428이_아니라_그대로_전파되고_확정도_실패한다() {
			// 인프라 사정을 "영상 파일이 아닙니다"로 안내하지 않는다 — 장애 중 대량 오안내 방지.
			given(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
				.willThrow(S3Exception.builder().statusCode(500).message("Internal Error").build());

			assertThatThrownBy(() -> videoService.saveVideo(userId, 요청(키())))
				.isInstanceOf(S3Exception.class);
			assertThat(카운트("videos")).isZero();
		}

		// 검증: FR-VIDEO-08
		@Test
		@DisplayName("확정이 거부된 뒤 같은 키로 다시 확정할 수 있다")
		void 확정이_거부된_뒤_같은_키로_다시_확정할_수_있다() {
			String s3Key = 키();
			givenUploadedVideoObject(s3Client, 텍스트());
			assertThatThrownBy(() -> videoService.saveVideo(userId, 요청(s3Key)))
				.isInstanceOf(ApiException.class);

			// 실패한 시도는 클레임을 남기지 않으므로 이중 확정 차단(3401)에 걸리지 않는다.
			givenUploadedVideoObject(s3Client);

			assertThat(videoService.saveVideo(userId, 요청(s3Key)).videoId()).isPositive();
		}
	}

	private String 키() {
		return "videos/pending/" + userId + "/" + UUID.randomUUID() + ".mp4";
	}

	private VideoUploadRequestDto 요청(String s3Key) {
		return new VideoUploadRequestDto(s3Key, 바다_LAT, 바다_LON, (short) 10, 한시간_전(), null);
	}

	private static LocalDateTime 한시간_전() {
		return LocalDateTime.now(ZoneOffset.UTC).minusHours(1);
	}

	private long 카운트(String table) {
		em.flush();
		return ((Number) em.createNativeQuery("SELECT count(*) FROM " + table + " WHERE user_id = :u")
			.setParameter("u", userId)
			.getSingleResult()).longValue();
	}

	private String 원본키(long videoId) {
		em.flush();
		return (String) em.createNativeQuery("SELECT original_s3_key FROM videos WHERE id = :i")
			.setParameter("i", videoId)
			.getSingleResult();
	}

	/** 진행 중인 팝업 미션 1건 — 판정 격자 1칸이 곧 대표 격자다. */
	private long 진행중_팝업() {
		String gridId = GridEncoder.encode(바다_LAT, 바다_LON);
		long missionId = ((Number) em.createNativeQuery("""
				INSERT INTO missions (type, title, start_at, end_at, target_count, source)
				VALUES ('POPUP', :title, :startAt, :endAt, 1, 'MSG392IT') RETURNING id
				""")
			.setParameter("title", "msg392-" + UUID.randomUUID())
			.setParameter("startAt", LocalDateTime.now(ZoneOffset.UTC).minusDays(1))
			.setParameter("endAt", LocalDateTime.now(ZoneOffset.UTC).plusDays(1))
			.getSingleResult()).longValue();
		em.createNativeQuery("INSERT INTO mission_grids (mission_id, grid_id) VALUES (:m, :g)")
			.setParameter("m", missionId).setParameter("g", gridId).executeUpdate();
		em.createNativeQuery("UPDATE missions SET representative_grid_id = :g WHERE id = :m")
			.setParameter("g", gridId).setParameter("m", missionId).executeUpdate();
		return missionId;
	}

	private static byte[] 텍스트() {
		return "not a video, just text pretending to be one".getBytes(StandardCharsets.US_ASCII);
	}

	/** 8192를 선언한 mdat 헤더 + 0 패딩 — 선언 크기가 실제 길이 밖이라 파일 끝 판정에서 거부된다. */
	private static byte[] 크기가_파일_밖인_mdat(int length) {
		byte[] out = new byte[length];
		System.arraycopy(new byte[] {0x00, 0x00, 0x20, 0x00, 'm', 'd', 'a', 't'}, 0, out, 0, 8);
		return out;
	}

	private static ResponseBytes<GetObjectResponse> 응답(byte[] bytes) {
		return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), bytes);
	}
}
