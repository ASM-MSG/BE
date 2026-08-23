package com.msg.fillmap.mission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import com.msg.fillmap.badge.dto.EarnedBadgeResponseDto;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.hotzone.service.HotScoreCommandService;
import com.msg.fillmap.mission.dto.CompletedMissionResponseDto;
import com.msg.fillmap.mission.dto.MissionVideoUploadRequestDto;
import com.msg.fillmap.mission.dto.MissionVideoUploadResponseDto;
import com.msg.fillmap.mission.exception.MissionErrorCode;
import com.msg.fillmap.mission.repository.MissionRepository;
import com.msg.fillmap.mission.repository.UserMissionRepository;
import com.msg.fillmap.mission.service.impl.MissionVideoServiceImpl;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.exception.VideoErrorCode;
import com.msg.fillmap.video.service.VideoEncodingService;
import com.msg.fillmap.video.service.VideoService;

/**
 * 미션 경유 영상 업로드 (MSG-459 §API 1, 실 PostgreSQL). 검증 대상이 대부분 DB 부수효과
 * (videos·user_grids·user_missions)와 판정 쿼리라 모킹으로는 잡히지 않아 실 스택으로 돈다 —
 * 특히 "업로드가 통과하면 스탬프도 찍힌다"(D-9)는 게이트와 판정 쿼리가 같은 값을 볼 때만 성립하므로
 * 두 쪽을 다 진짜로 돌려야 관측된다. 뱃지·스트릭도 실제 빈이라 스텁 누락으로 계약이 흐려지지 않는다.
 * <p>
 * 격리(공유 로컬 DB): 서해 먼바다 격자(125.5 대역)와 합성 제목(msg459-*)만 쓰고 {@code @Transactional}
 * 롤백으로 정리한다. S3·인코딩·핫스코어는 목이다 — 확정 경로가 실제 버킷·워커·Redis 로 나가지 않게 한다.
 * <p>
 * 미션 기간은 <b>실시간 기준 상대값</b>이다. 스탬프 판정 쿼리가 {@code statement_timestamp()} 로 활성
 * 후보를 찾으므로, 고정 클럭으로 과거에 미션을 두면 게이트만 통과하고 스탬프가 안 찍혀 보존 확인(7번)에
 * 걸린다. 고정 클럭은 그 성질을 일부러 재현하는 테스트에서만 주입한다.
 */
@SpringBootTest
@Transactional
@DisplayName("미션 경유 영상 업로드 확정 (실 PostgreSQL)")
class MissionVideoUploadServiceTest {

	/** 서해 먼바다 기준 격자 — 육상 실데이터·행사 테스트(125.0~125.4)와 겹치지 않는 대역. */
	private static final GridIndex 바다 = GridEncoder.decode(GridEncoder.encode(34.0, 125.5));

	/** 이 테스트가 만든 미션의 적재 출처 — 종료 정리 쿼리를 실데이터와 무관하게 돌리기 위한 표식이다. */
	private static final String SOURCE = "MSG459IT";

	@Autowired
	private MissionRepository missionRepository;

	@Autowired
	private UserMissionRepository userMissionRepository;

	@Autowired
	private VideoService videoService;

	@Autowired
	private MissionAwardService missionAwardService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@MockitoBean
	private S3Client s3Client;

	/** 확정 커밋 후 인코딩 제출이 실제 워커로 나가지 않게 막는다 — 이 테스트의 관심사는 확정까지다. */
	@MockitoBean
	private VideoEncodingService videoEncodingService;

	/** 커밋 후 훅이라 롤백 테스트에서는 돌지 않지만, Redis 의존을 끊어 두는 편이 안전하다. */
	@MockitoBean
	private HotScoreCommandService hotScoreCommandService;

	private long userId;

	@BeforeEach
	void setUp() {
		given(s3Client.headObject(any(HeadObjectRequest.class))).willReturn(HeadObjectResponse.builder().build());
		userId = 사용자("uploader");
	}

	private long 사용자(String prefix) {
		return userRepository.save(User.createLocalUser(
			"msg459-" + prefix + "-" + UUID.randomUUID() + "@example.com", "hash", "미션업로더")).getId();
	}

	private MissionVideoService service() {
		return service(Clock.systemUTC());
	}

	private MissionVideoService service(LocalDateTime now) {
		return service(Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
	}

	private MissionVideoService service(Clock clock) {
		return new MissionVideoServiceImpl(
			missionRepository, userMissionRepository, videoService, missionAwardService, clock);
	}

	private static LocalDateTime 지금() {
		// PostgreSQL timestamp 는 마이크로초까지만 저장하며 그보다 잔 자리는 반올림한다 — Linux CI 의
		// 나노초 클럭 값을 그대로 심으면 저장값이 심은 값보다 커질 수 있어 정각 경계 대조가 뒤집힌다
		// (macOS 클럭은 마이크로초라 로컬에서 재현되지 않았다). 절단해 왕복이 항등이 되게 한다.
		return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
	}

	private String 격자(long dy, long dx) {
		return (바다.gridY() + dy) + "_" + (바다.gridX() + dx);
	}

	private String 키(long owner) {
		return "videos/pending/" + owner + "/" + UUID.randomUUID() + ".mp4";
	}

	private MissionVideoUploadRequestDto 요청(String s3Key, LocalDateTime recordedAt) {
		return new MissionVideoUploadRequestDto(s3Key, (short) 10, recordedAt);
	}

	/** 진행 중인 축제 1건 + 판정 격자 9×9, 대표 격자는 정중앙 — 실데이터와 같은 형태다. */
	private long 진행중_축제(long dy, long dx) {
		List<String> gridIds = new ArrayList<>(81);
		for (long y = -4; y <= 4; y++) {
			for (long x = -4; x <= 4; x++) {
				gridIds.add(격자(dy + y, dx + x));
			}
		}
		return 미션("EVENT", 지금().minusDays(1), 지금().plusDays(1), gridIds, 격자(dy, dx));
	}

	/** 진행 중인 팝업 1건 + 판정 격자 1칸 — 그 칸이 곧 대표 격자다. */
	private long 진행중_팝업(long dy, long dx) {
		return 미션("POPUP", 지금().minusDays(1), 지금().plusDays(1), List.of(격자(dy, dx)), 격자(dy, dx));
	}

	private long 미션(String type, LocalDateTime startAt, LocalDateTime endAt, List<String> gridIds,
		String representativeGridId) {
		long missionId = ((Number) em.createNativeQuery("""
				INSERT INTO missions (type, title, start_at, end_at, target_count, source)
				VALUES (:type, :title, :startAt, :endAt, 1, :source) RETURNING id
				""")
			.setParameter("type", type)
			.setParameter("title", "msg459-" + UUID.randomUUID())
			.setParameter("startAt", startAt)
			.setParameter("endAt", endAt)
			.setParameter("source", SOURCE)
			.getSingleResult()).longValue();
		gridIds.forEach(gridId -> em.createNativeQuery(
				"INSERT INTO mission_grids (mission_id, grid_id) VALUES (:m, :g)")
			.setParameter("m", missionId).setParameter("g", gridId).executeUpdate());
		if (representativeGridId != null) {
			대표격자(missionId, representativeGridId);
		}
		return missionId;
	}

	private void 대표격자(long missionId, String gridId) {
		em.createNativeQuery("UPDATE missions SET representative_grid_id = :g WHERE id = :m")
			.setParameter("g", gridId).setParameter("m", missionId).executeUpdate();
	}

	private String 대표격자(long missionId) {
		return (String) em.createNativeQuery("SELECT representative_grid_id FROM missions WHERE id = :m")
			.setParameter("m", missionId).getSingleResult();
	}

	private long 카운트(String sql, Object value) {
		em.flush();
		return ((Number) em.createNativeQuery(sql).setParameter("v", value).getSingleResult()).longValue();
	}

	private long 격자영상수(String gridId) {
		return 카운트("SELECT count(*) FROM videos WHERE grid_id = :v", gridId);
	}

	private long 스탬프수(long missionId) {
		return 카운트("SELECT count(*) FROM user_missions WHERE mission_id = :v", missionId);
	}

	private MissionVideoUploadResponseDto 업로드(long missionId, String s3Key) {
		return service().upload(userId, missionId, 요청(s3Key, 지금().minusHours(1)));
	}

	/** 실패 응답의 비교 재료 — 상태·developCode·메시지가 같은지로 존재 은닉(FR-10)을 대조한다. */
	private String 실패(long missionId, MissionVideoUploadRequestDto request) {
		try {
			service().upload(userId, missionId, request);
		} catch (ApiException e) {
			return "%s|%d|%s".formatted(e.getErrorCode().getHttpStatus(),
				e.getErrorCode().getErrorCode(), e.getMessage());
		}
		throw new AssertionError("업로드가 실패하지 않았습니다");
	}

	@Nested
	@DisplayName("대표 격자 저장과 판정")
	class 저장 {

		// 검증: FR-MISSION-22
		@Test
		@DisplayName("축제_미션에_좌표_없이_올리면_대표_격자에_저장된다")
		void 축제_미션에_좌표_없이_올리면_대표_격자에_저장된다() {
			long missionId = 진행중_축제(0, 0);

			MissionVideoUploadResponseDto response = 업로드(missionId, 키(userId));

			assertThat(response.gridId()).isEqualTo(대표격자(missionId));
			assertThat(response.processingStatus()).isEqualTo("UPLOADED");
			assertThat(격자영상수(격자(0, 0))).isEqualTo(1);
			// 판정 범위의 다른 칸에는 복제되지 않는다 — 영상은 대표 격자 한 칸에만 붙는다.
			assertThat(격자영상수(격자(1, 1))).isZero();
		}

		// 검증: FR-MISSION-22
		@Test
		@DisplayName("팝업_미션에_올린_영상도_대표_격자에_저장된다")
		void 팝업_미션에_올린_영상도_대표_격자에_저장된다() {
			long missionId = 진행중_팝업(20, 0);

			MissionVideoUploadResponseDto response = 업로드(missionId, 키(userId));

			assertThat(response.gridId()).isEqualTo(격자(20, 0));
			assertThat(격자영상수(격자(20, 0))).isEqualTo(1);
		}

		// 검증: FR-MISSION-22
		@Test
		@DisplayName("미션_경유_업로드는_대표_격자의_점령을_만든다")
		void 미션_경유_업로드는_대표_격자의_점령을_만든다() {
			long missionId = 진행중_팝업(21, 0);

			MissionVideoUploadResponseDto response = 업로드(missionId, 키(userId));

			assertThat(response.occupied()).isTrue();
			assertThat(카운트("SELECT count(*) FROM user_grids WHERE grid_id = :v", 격자(21, 0))).isEqualTo(1);
		}

		// 검증: FR-MISSION-22
		@Test
		@DisplayName("미션_경유_업로드로_조건을_채우면_스탬프가_응답에_실린다")
		void 미션_경유_업로드로_조건을_채우면_스탬프가_응답에_실린다() {
			long missionId = 진행중_팝업(22, 0);

			MissionVideoUploadResponseDto response = 업로드(missionId, 키(userId));

			assertThat(response.completedMissions()).extracting(CompletedMissionResponseDto::missionId)
				.containsExactly(missionId);
			assertThat(스탬프수(missionId)).isEqualTo(1);
		}

		// 검증: FR-MISSION-22
		@Test
		@DisplayName("미션_경유_업로드로_받은_미션_뱃지가_응답에_실린다")
		void 미션_경유_업로드로_받은_미션_뱃지가_응답에_실린다() {
			// 첫 팝업 스탬프가 종류별 임계 1(팝업 입문, V29 POPUP_1)을 넘긴다. 그 뱃지는 확정 코어가 아니라
			// 미션 판정이 발급하므로, 두 목록을 합치지 않으면 DB 에만 남고 응답에서 빠진다.
			long missionId = 진행중_팝업(25, 0);

			MissionVideoUploadResponseDto response = 업로드(missionId, 키(userId));

			assertThat(response.newBadges()).extracting(EarnedBadgeResponseDto::code).contains("POPUP_1");
			assertThat(카운트("SELECT count(*) FROM user_badges ub JOIN badges b ON b.id = ub.badge_id "
				+ "WHERE b.code = 'POPUP_1' AND ub.user_id = :v", userId)).isEqualTo(1);
		}

		@Test
		@DisplayName("이미_스탬프가_있는_미션에_다시_올리면_응답의_완료_미션이_비어_있다")
		void 이미_스탬프가_있는_미션에_다시_올리면_응답의_완료_미션이_비어_있다() {
			long missionId = 진행중_팝업(23, 0);
			업로드(missionId, 키(userId));

			MissionVideoUploadResponseDto second = 업로드(missionId, 키(userId));

			// 스탬프는 미션당 1회다 — 두 번째 업로드는 성공하되 새 스탬프가 없다(보존 확인은 통과한다).
			assertThat(second.completedMissions()).isEmpty();
			assertThat(second.occupied()).isFalse();
			assertThat(스탬프수(missionId)).isEqualTo(1);
		}

		@Test
		@DisplayName("미션_영상_목록_조회가_종전대로_동작한다")
		void 미션_영상_목록_조회가_종전대로_동작한다() {
			long missionId = 진행중_팝업(24, 0);
			업로드(missionId, 키(userId));

			// 같은 컨트롤러에 POST 가 붙어도 GET 계약은 그대로다. 방금 올린 영상은 인코딩 전(UPLOADED)이라
			// 목록 게이트(READY)에 잡히지 않는다 — 이 역시 이번 변경 이전과 같은 동작이다.
			assertThat(videoService.getMissionVideos(missionId, null, 20).videos()).isEmpty();
		}
	}

	@Nested
	@DisplayName("멱등 재시도")
	class 멱등 {

		@Test
		@DisplayName("같은_s3Key로_두_번_보내도_영상이_하나만_생긴다")
		void 같은_s3Key로_두_번_보내도_영상이_하나만_생긴다() {
			long missionId = 진행중_팝업(30, 0);
			String s3Key = 키(userId);
			MissionVideoUploadResponseDto first = 업로드(missionId, s3Key);

			MissionVideoUploadResponseDto retry = 업로드(missionId, s3Key);

			assertThat(retry.videoId()).isEqualTo(first.videoId());
			assertThat(격자영상수(격자(30, 0))).isEqualTo(1);
		}

		@Test
		@DisplayName("재시도_응답은_점령_false와_빈_뱃지_배열을_돌려준다")
		void 재시도_응답은_점령_false와_빈_뱃지_배열을_돌려준다() {
			long missionId = 진행중_팝업(31, 0);
			String s3Key = 키(userId);
			업로드(missionId, s3Key);

			MissionVideoUploadResponseDto retry = 업로드(missionId, s3Key);

			// 첫 응답 전용 필드는 재시도에서 비운다 — 복원할 저장 컬럼이 없다(행사 업로드와 같은 규칙).
			assertThat(retry.occupied()).isFalse();
			assertThat(retry.newBadges()).isEmpty();
			assertThat(retry.completedMissions()).isEmpty();
		}

		@Test
		@DisplayName("마감_직전에_확정된_업로드의_재시도가_마감_뒤에_와도_성공을_되돌려준다")
		void 마감_직전에_확정된_업로드의_재시도가_마감_뒤에_와도_성공을_되돌려준다() {
			long missionId = 진행중_팝업(32, 0);
			String s3Key = 키(userId);
			LocalDateTime recordedAt = 지금().minusHours(1);
			MissionVideoUploadResponseDto first = service().upload(userId, missionId, 요청(s3Key, recordedAt));

			// 미션이 끝난 뒤 도착한 재전송 — 멱등 판정이 기간 판정보다 앞이라 이미 성공한 요청이 거절되지 않는다.
			MissionVideoUploadResponseDto retry = service(지금().plusDays(2))
				.upload(userId, missionId, 요청(s3Key, recordedAt));

			assertThat(retry.videoId()).isEqualTo(first.videoId());
		}

		@Test
		@DisplayName("일반_업로드로_확정된_키를_미션에_재전송하면_저장된_촬영_시각이_기간_밖일_때_12409다")
		void 일반_업로드로_확정된_키를_미션에_재전송하면_저장된_촬영_시각이_기간_밖일_때_12409다() {
			long missionId = 진행중_팝업(33, 0);
			String s3Key = 키(userId);
			GridPoint center = GridEncoder.center(격자(33, 0));
			// 좌표 업로드는 촬영 시각 가드를 타지 않으므로 미션 기간 훨씬 이전 영상도 그 격자에 확정된다.
			videoService.saveVideo(userId, new VideoUploadRequestDto(s3Key, center.lat(), center.lon(),
				(short) 10, 지금().minusDays(60), null));

			// 격자만 맞으면 성공을 돌려주는 구현이었다면 FR-18 의 촬영 시각 가드가 우회된다.
			assertThatThrownBy(() -> 업로드(missionId, s3Key))
				.isInstanceOf(ApiException.class)
				.extracting("errorCode")
				.isEqualTo(MissionErrorCode.MISSION_UPLOAD_UNAVAILABLE);
		}

		// 검증: FR-MISSION-24
		@Test
		@DisplayName("행사_업로드로_확정된_키를_미션에_재전송하면_스탬프가_없어_12409다")
		void 행사_업로드로_확정된_키를_미션에_재전송하면_스탬프가_없어_12409다() {
			long missionId = 진행중_팝업(34, 0);
			String s3Key = 키(userId);
			// 행사 업로드가 하는 일 그대로 — 확정 코어만 태우고 미션 판정(awardOnUpload)은 타지 않는다
			// (MSG-438 제외 계약). 행사 대표 격자와 미션 대표 격자가 같은 칸이면 소유자·격자·촬영 시각이
			// 전부 맞는데 스탬프만 없는 상태가 만들어진다.
			videoService.confirmAtGrid(userId, 격자(34, 0), s3Key, (short) 10, 지금().minusHours(1));

			// 스탬프 보유를 안 보면 여기서 성공이 나가고, 스탬프 없는 미션 영상이 남아 종료 정리가 미션을 지운다.
			assertThatThrownBy(() -> 업로드(missionId, s3Key))
				.isInstanceOf(ApiException.class)
				.extracting("errorCode")
				.isEqualTo(MissionErrorCode.MISSION_UPLOAD_UNAVAILABLE);
			assertThat(스탬프수(missionId)).isZero();
		}
	}

	@Nested
	@DisplayName("미션 판정 실패 (12409 수렴)")
	class 판정 {

		@Test
		@DisplayName("코스_미션에_올리면_12409로_거절한다")
		void 코스_미션에_올리면_12409로_거절한다() {
			// 코스는 대표 격자를 가질 수 없다(CHECK) — 유형 판정이 먼저 걸린다.
			long missionId = 미션("COURSE", null, null, List.of(격자(40, 0)), null);

			assertThatThrownBy(() -> 업로드(missionId, 키(userId)))
				.isInstanceOf(ApiException.class)
				.extracting("errorCode")
				.isEqualTo(MissionErrorCode.MISSION_UPLOAD_UNAVAILABLE);
		}

		@Test
		@DisplayName("없는_미션에_올리면_12409로_거절한다")
		void 없는_미션에_올리면_12409로_거절한다() {
			assertThatThrownBy(() -> 업로드(-1L, 키(userId)))
				.isInstanceOf(ApiException.class)
				.extracting("errorCode")
				.isEqualTo(MissionErrorCode.MISSION_UPLOAD_UNAVAILABLE);
		}

		@Test
		@DisplayName("기간이_끝난_축제에_올리면_12409로_거절한다")
		void 기간이_끝난_축제에_올리면_12409로_거절한다() {
			long missionId = 미션("EVENT", 지금().minusDays(10), 지금().minusDays(1),
				List.of(격자(41, 0)), 격자(41, 0));

			assertThatThrownBy(() -> 업로드(missionId, 키(userId)))
				.isInstanceOf(ApiException.class)
				.extracting("errorCode")
				.isEqualTo(MissionErrorCode.MISSION_UPLOAD_UNAVAILABLE);
		}

		@Test
		@DisplayName("대표_격자가_비어_있는_미션에_올리면_12409로_거절한다")
		void 대표_격자가_비어_있는_미션에_올리면_12409로_거절한다() {
			// V42 백필이 형태를 증명하지 못해 NULL 로 남긴 미션 — 조회·판정은 종전대로 되고 업로드만 막힌다.
			long missionId = 미션("EVENT", 지금().minusDays(1), 지금().plusDays(1), List.of(격자(42, 0)), null);

			assertThatThrownBy(() -> 업로드(missionId, 키(userId)))
				.isInstanceOf(ApiException.class)
				.extracting("errorCode")
				.isEqualTo(MissionErrorCode.MISSION_UPLOAD_UNAVAILABLE);
		}

		// 검증: FR-MISSION-23
		@Test
		@DisplayName("신고된_촬영_시각이_미션_기간_밖이면_12409로_거절한다")
		void 신고된_촬영_시각이_미션_기간_밖이면_12409로_거절한다() {
			long missionId = 진행중_팝업(43, 0);

			// 축제 기간 중에 지난달 갤러리 영상을 고르는 시나리오 — 통과시키면 스탬프가 안 찍혀 보존이 깨진다.
			assertThatThrownBy(() -> service().upload(userId, missionId, 요청(키(userId), 지금().minusDays(30))))
				.isInstanceOf(ApiException.class)
				.extracting("errorCode")
				.isEqualTo(MissionErrorCode.MISSION_UPLOAD_UNAVAILABLE);
			assertThat(격자영상수(격자(43, 0))).isZero();
		}

		// 검증: FR-MISSION-23
		@Test
		@DisplayName("신고된_촬영_시각이_미션_시작_정각이면_업로드가_통과한다")
		void 신고된_촬영_시각이_미션_시작_정각이면_업로드가_통과한다() {
			LocalDateTime startAt = 지금().minusDays(1);
			long missionId = 미션("POPUP", startAt, 지금().plusDays(1), List.of(격자(44, 0)), 격자(44, 0));

			MissionVideoUploadResponseDto response = service()
				.upload(userId, missionId, 요청(키(userId), startAt));

			// 경계가 양끝 포함이라 판정 쿼리도 이 영상을 잡는다 — 스탬프가 실린 것이 그 증거다.
			assertThat(response.completedMissions()).hasSize(1);
		}

		// 검증: FR-MISSION-23
		@Test
		@DisplayName("신고된_촬영_시각이_미션_종료_정각이면_업로드가_통과한다")
		void 신고된_촬영_시각이_미션_종료_정각이면_업로드가_통과한다() {
			// 종료 정각이 곧 촬영 시각이려면 그 정각이 아직 미래여야 한다(미래 시각 허용 오차 5분 안).
			LocalDateTime endAt = 지금().plusMinutes(2);
			long missionId = 미션("POPUP", 지금().minusDays(1), endAt, List.of(격자(45, 0)), 격자(45, 0));

			MissionVideoUploadResponseDto response = service().upload(userId, missionId, 요청(키(userId), endAt));

			assertThat(response.completedMissions()).hasSize(1);
		}

		@Test
		@DisplayName("키_형식이_아니면_3401이다")
		void 키_형식이_아니면_3401이다() {
			long missionId = 진행중_팝업(46, 0);

			assertThatThrownBy(() -> 업로드(missionId, "not-a-pending-key"))
				.isInstanceOf(ApiException.class)
				.extracting("errorCode")
				.isEqualTo(VideoErrorCode.INVALID_S3_KEY);
		}

		@Test
		@DisplayName("남의_pending_키로_확정하려_하면_3401이다")
		void 남의_pending_키로_확정하려_하면_3401이다() {
			long missionId = 진행중_팝업(47, 0);

			assertThatThrownBy(() -> 업로드(missionId, 키(사용자("other"))))
				.isInstanceOf(ApiException.class)
				.extracting("errorCode")
				.isEqualTo(VideoErrorCode.INVALID_S3_KEY);
		}

		@Test
		@DisplayName("S3에_없는_키와_이미_다른_자리에_쓴_키는_12409로_수렴한다")
		void S3에_없는_키와_이미_다른_자리에_쓴_키는_12409로_수렴한다() {
			long missionId = 진행중_팝업(48, 0);
			long otherMissionId = 진행중_팝업(49, 0);
			String usedKey = 키(userId);
			업로드(otherMissionId, usedKey);
			String missingKey = 키(userId);
			given(s3Client.headObject(any(HeadObjectRequest.class)))
				.willThrow(NoSuchKeyException.builder().message("없는 키").build());

			// 형식·소유 접두어는 맞지만 S3 에 없는 키 — 3402 를 주면 지어낸 키 하나로 미션 존재를 알 수 있다.
			assertThatThrownBy(() -> 업로드(missionId, missingKey))
				.isInstanceOf(ApiException.class)
				.extracting("errorCode")
				.isEqualTo(MissionErrorCode.MISSION_UPLOAD_UNAVAILABLE);
			// 이미 다른 미션의 대표 격자에 쓴 키도 같은 응답으로 수렴한다.
			assertThatThrownBy(() -> 업로드(missionId, usedKey))
				.isInstanceOf(ApiException.class)
				.extracting("errorCode")
				.isEqualTo(MissionErrorCode.MISSION_UPLOAD_UNAVAILABLE);
		}
	}

	@Nested
	@DisplayName("존재 은닉 (FR-10)")
	class 존재은닉 {

		private static final long 없는_미션 = -1L;

		// 검증: FR-MISSION-26
		@Test
		@DisplayName("코스_미션과_없는_미션의_실패_응답이_같다")
		void 코스_미션과_없는_미션의_실패_응답이_같다() {
			long courseId = 미션("COURSE", null, null, List.of(격자(50, 0)), null);
			MissionVideoUploadRequestDto request = 요청(키(userId), 지금().minusHours(1));

			assertThat(실패(courseId, request)).isEqualTo(실패(없는_미션, request));
		}

		@Test
		@DisplayName("종료_미션과_없는_미션의_실패_응답이_같다")
		void 종료_미션과_없는_미션의_실패_응답이_같다() {
			long endedId = 미션("EVENT", 지금().minusDays(10), 지금().minusDays(1),
				List.of(격자(51, 0)), 격자(51, 0));
			MissionVideoUploadRequestDto request = 요청(키(userId), 지금().minusDays(5));

			assertThat(실패(endedId, request)).isEqualTo(실패(없는_미션, request));
		}

		@Test
		@DisplayName("신고된_촬영_시각_기간_밖과_없는_미션의_실패_응답이_같다")
		void 신고된_촬영_시각_기간_밖과_없는_미션의_실패_응답이_같다() {
			long missionId = 진행중_팝업(52, 0);
			MissionVideoUploadRequestDto request = 요청(키(userId), 지금().minusDays(30));

			assertThat(실패(missionId, request)).isEqualTo(실패(없는_미션, request));
		}

		@Test
		@DisplayName("지어낸_s3Key는_미션_존재_여부와_무관하게_같은_12409다")
		void 지어낸_s3Key는_미션_존재_여부와_무관하게_같은_12409다() {
			// 공격자는 자기 userId 를 알아 형식·소유 접두어를 맞춘 키를 지어낼 수 있다. 그 키가 S3 에 없다는
			// 사실은 확정 단계에서야 드러나므로, 3402 를 주면 비용 0 의 존재 오라클이 다시 열린다(D-10).
			given(s3Client.headObject(any(HeadObjectRequest.class)))
				.willThrow(NoSuchKeyException.builder().message("없는 키").build());
			long missionId = 진행중_팝업(53, 0);
			MissionVideoUploadRequestDto request = 요청(키(userId), 지금().minusHours(1));

			assertThat(실패(missionId, request)).isEqualTo(실패(없는_미션, request));
		}

		@Test
		@DisplayName("미래_촬영_시각은_미션_존재_여부와_무관하게_3424다")
		void 미래_촬영_시각은_미션_존재_여부와_무관하게_3424다() {
			long missionId = 진행중_팝업(54, 0);
			MissionVideoUploadRequestDto request = 요청(키(userId), 지금().plusDays(1));

			String response = 실패(missionId, request);

			// 미션 조회보다 앞선 검증이라 존재와 무관하게 같은 코드가 나온다 — 단말 시계 안내가 가능한 유일한 실패다.
			assertThat(response).isEqualTo(실패(없는_미션, request));
			assertThat(response).contains(String.valueOf(VideoErrorCode.RECORDED_AT_IN_FUTURE.getErrorCode()));
		}

		@Test
		@DisplayName("남의_pending_키는_미션_존재_여부와_무관하게_3401이다")
		void 남의_pending_키는_미션_존재_여부와_무관하게_3401이다() {
			long missionId = 진행중_팝업(55, 0);
			MissionVideoUploadRequestDto request = 요청(키(사용자("stranger")), 지금().minusHours(1));

			String response = 실패(missionId, request);

			assertThat(response).isEqualTo(실패(없는_미션, request));
			assertThat(response).contains(String.valueOf(VideoErrorCode.INVALID_S3_KEY.getErrorCode()));
		}
	}

	@Nested
	@DisplayName("종료 미션 보존 (FR-17)")
	class 보존 {

		// 검증: FR-MISSION-24
		@Test
		@DisplayName("미션_경유_업로드가_성공하면_같은_트랜잭션에서_스탬프가_찍힌다")
		void 미션_경유_업로드가_성공하면_같은_트랜잭션에서_스탬프가_찍힌다() {
			long missionId = 진행중_팝업(60, 0);

			업로드(missionId, 키(userId));

			// 영상과 스탬프가 같은 트랜잭션에 있다 — 이것이 보존 논증의 앞쪽 절반이다.
			assertThat(격자영상수(격자(60, 0))).isEqualTo(1);
			assertThat(스탬프수(missionId)).isEqualTo(1);
		}

		// 검증: FR-MISSION-24
		@Test
		@DisplayName("영상이_올라온_축제는_종료_정리에서_지워지지_않는다")
		void 영상이_올라온_축제는_종료_정리에서_지워지지_않는다() {
			long missionId = 진행중_팝업(61, 0);
			업로드(missionId, 키(userId));
			// 미션이 끝난 상태로 만든다 — 업로드 당시엔 활성이었고 지금은 정리 대상 기간이다.
			종료시킴(missionId);

			missionRepository.deleteEndedBySourceWithoutStamps(SOURCE);

			assertThat(missionRepository.findById(missionId)).isPresent();
		}

		@Test
		@DisplayName("스탬프_발급_후_영상을_지워도_미션은_정리에서_살아남는다")
		void 스탬프_발급_후_영상을_지워도_미션은_정리에서_살아남는다() {
			long missionId = 진행중_팝업(62, 0);
			MissionVideoUploadResponseDto response = 업로드(missionId, 키(userId));
			videoService.deleteVideo(userId, response.videoId());
			종료시킴(missionId);

			missionRepository.deleteEndedBySourceWithoutStamps(SOURCE);

			// 스탬프는 비회수라 영상이 사라져도 남고, 정리 쿼리가 그 스탬프를 보고 미션을 건너뛴다.
			assertThat(missionRepository.findById(missionId)).isPresent();
		}

		@Test
		@DisplayName("이미_스탬프가_있는_미션에_다시_올려도_보존_확인이_거절하지_않는다")
		void 이미_스탬프가_있는_미션에_다시_올려도_보존_확인이_거절하지_않는다() {
			long missionId = 진행중_팝업(63, 0);
			업로드(missionId, 키(userId));

			// 새 스탬프가 없어도 user_missions 단건 조회가 통과시킨다 — 재업로드는 정상 경로다.
			MissionVideoUploadResponseDto second = 업로드(missionId, 키(userId));

			assertThat(second.completedMissions()).isEmpty();
			assertThat(격자영상수(격자(63, 0))).isEqualTo(2);
		}

		private void 종료시킴(long missionId) {
			em.flush();
			em.createNativeQuery("UPDATE missions SET start_at = :s, end_at = :e WHERE id = :m")
				.setParameter("s", 지금().minusDays(10))
				.setParameter("e", 지금().minusDays(1))
				.setParameter("m", missionId)
				.executeUpdate();
		}
	}

	@Nested
	@DisplayName("기존 좌표 업로드 회귀")
	class 기존경로 {

		@Test
		@DisplayName("좌표_기반_일반_업로드는_종전대로_동작한다")
		void 좌표_기반_일반_업로드는_종전대로_동작한다() {
			// 미션 격자는 축제 기간에만 미션이지 평소에는 그냥 동네다 — 그 자리의 일상 업로드가 막히면 안 된다.
			진행중_팝업(70, 0);
			GridPoint center = GridEncoder.center(격자(70, 0));

			var response = videoService.saveVideo(userId, new VideoUploadRequestDto(키(userId),
				center.lat(), center.lon(), (short) 10, 지금().minusHours(1), null));

			assertThat(response.gridId()).isEqualTo(격자(70, 0));
			assertThat(response.occupied()).isTrue();
		}

		@Test
		@DisplayName("좌표_기반_업로드는_신고된_촬영_시각_가드를_타지_않는다")
		void 좌표_기반_업로드는_신고된_촬영_시각_가드를_타지_않는다() {
			진행중_팝업(71, 0);
			GridPoint center = GridEncoder.center(격자(71, 0));

			// 미션 기간 훨씬 이전의 갤러리 영상 — 가드는 미션 경유 경로에만 붙는다는 것이 계약이다.
			var response = videoService.saveVideo(userId, new VideoUploadRequestDto(키(userId),
				center.lat(), center.lon(), (short) 10, 지금().minusDays(60), null));

			assertThat(response.gridId()).isEqualTo(격자(71, 0));
		}

		@Test
		@DisplayName("일반_업로드의_S3_부재_응답은_종전_3402_그대로다")
		void 일반_업로드의_S3_부재_응답은_종전_3402_그대로다() {
			given(s3Client.headObject(any(HeadObjectRequest.class)))
				.willThrow(NoSuchKeyException.builder().message("없는 키").build());
			GridPoint center = GridEncoder.center(격자(72, 0));

			// 수렴(12409)은 미션 경유 경로에만 적용된다 — 숨길 미션이 없는 경로는 정확한 코드를 그대로 준다.
			assertThatThrownBy(() -> videoService.saveVideo(userId, new VideoUploadRequestDto(키(userId),
				center.lat(), center.lon(), (short) 10, 지금().minusHours(1), null)))
				.isInstanceOf(ApiException.class)
				.extracting("errorCode")
				.isEqualTo(VideoErrorCode.UPLOAD_NOT_FOUND);
		}
	}
}
