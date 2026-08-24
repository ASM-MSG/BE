package com.msg.fillmap.mission.service;

import static com.msg.fillmap.video.support.S3VideoObjectStub.givenUploadedVideoObject;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import software.amazon.awssdk.services.s3.S3Client;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.hotzone.service.HotScoreCommandService;
import com.msg.fillmap.mission.dto.MissionVideoUploadRequestDto;
import com.msg.fillmap.mission.exception.MissionErrorCode;
import com.msg.fillmap.mission.repository.MissionRepository;
import com.msg.fillmap.mission.repository.UserMissionRepository;
import com.msg.fillmap.mission.service.impl.MissionVideoServiceImpl;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.service.VideoEncodingService;
import com.msg.fillmap.video.service.VideoService;

/**
 * 미션 경유 업로드의 트랜잭션 경계 (MSG-459 D-3·D-9, 실 PostgreSQL). 관측 대상이 "실패하면 영상이
 * 남지 않는다"라 <b>실제 커밋과 롤백</b>이 필요하다 — 테스트 트랜잭션 안에서는 실패한 쓰기도 그대로
 * 보여 롤백을 관측할 수 없다. 그래서 {@code @Transactional} 을 걸지 않고 TransactionTemplate 으로
 * 경계를 직접 연다(FestivalMissionSeederIntegrationTest 의 롤백 검증 선례).
 * <p>
 * 여기서 잡는 것은 D-9 가 명시한 두 창이다. 활성 판정과 스탬프 판정 사이에 종료 정각이 끼는 경우와,
 * 업로드가 대표 격자를 읽은 뒤 팝업 재시딩이 판정 격자 집합을 갈아 끼우는 경우. 둘 다 영상만 남고
 * 스탬프가 없어 종료 정리가 그 미션을 지우는 상태이므로, 보존 확인(판정 흐름 7번)이 업로드를 통째로
 * 되돌려야 한다.
 * <p>
 * 격리(공유 로컬 DB): 서해 먼바다 격자(125.6 대역)와 합성 제목(msg459-rollback-*)만 쓰고, 실제 커밋을
 * 하므로 {@code @AfterEach} 에서 FK 역순 대상 지정 삭제로 정리한다.
 */
@SpringBootTest
@DisplayName("미션 경유 업로드 롤백 (실 PostgreSQL · 실제 커밋)")
class MissionVideoUploadRollbackTest {

	/** 서해 먼바다 기준 격자 — 업로드 서비스 테스트(125.5)와도 겹치지 않는 대역. */
	private static final GridIndex 바다 = GridEncoder.decode(GridEncoder.encode(34.0, 125.6));

	private static final String SOURCE = "MSG459RB";
	private static final String TITLE_PREFIX = "msg459-rollback-";

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
	private PlatformTransactionManager transactionManager;

	/** 공유 EntityManager 프록시 — 트랜잭션 템플릿이 연 트랜잭션에 그때그때 합류한다. */
	@Autowired
	private EntityManager em;

	@MockitoBean
	private S3Client s3Client;

	@MockitoBean
	private VideoEncodingService videoEncodingService;

	@MockitoBean
	private HotScoreCommandService hotScoreCommandService;

	private TransactionTemplate tx;
	private long userId;

	@BeforeEach
	void setUp() {
		givenUploadedVideoObject(s3Client);
		tx = new TransactionTemplate(transactionManager);
		userId = tx.execute(status -> userRepository.save(User.createLocalUser(
			"msg459-rollback-" + UUID.randomUUID() + "@example.com", "hash", "롤백테스터")).getId());
	}

	@AfterEach
	void 정리() {
		tx.executeWithoutResult(status -> List.of(
			"DELETE FROM user_grids WHERE user_id = " + userId,
			"DELETE FROM region_stats WHERE user_id = " + userId,
			"DELETE FROM videos WHERE user_id = " + userId,
			"DELETE FROM streaks WHERE user_id = " + userId,
			"DELETE FROM user_badges WHERE user_id = " + userId,
			"DELETE FROM user_missions WHERE user_id = " + userId,
			"DELETE FROM mission_grids WHERE mission_id IN "
				+ "(SELECT id FROM missions WHERE source = '" + SOURCE + "')",
			"DELETE FROM missions WHERE source = '" + SOURCE + "'",
			"DELETE FROM users WHERE id = " + userId
		).forEach(sql -> em.createNativeQuery(sql).executeUpdate()));
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

	private String 키() {
		return "videos/pending/" + userId + "/" + UUID.randomUUID() + ".mp4";
	}

	private MissionVideoService service(Clock clock, MissionAwardService award) {
		return new MissionVideoServiceImpl(missionRepository, userMissionRepository, videoService, award, clock);
	}

	/** 팝업 1건 + 판정 격자 1칸(=대표 격자)을 실제로 커밋한다. */
	private long 팝업(LocalDateTime startAt, LocalDateTime endAt, String gridId) {
		return tx.execute(status -> {
			long missionId = ((Number) em.createNativeQuery("""
					INSERT INTO missions (type, title, start_at, end_at, target_count, source)
					VALUES ('POPUP', :title, :startAt, :endAt, 1, :source) RETURNING id
					""")
				.setParameter("title", TITLE_PREFIX + UUID.randomUUID())
				.setParameter("startAt", startAt)
				.setParameter("endAt", endAt)
				.setParameter("source", SOURCE)
				.getSingleResult()).longValue();
			em.createNativeQuery("INSERT INTO mission_grids (mission_id, grid_id) VALUES (:m, :g)")
				.setParameter("m", missionId).setParameter("g", gridId).executeUpdate();
			em.createNativeQuery("UPDATE missions SET representative_grid_id = :g WHERE id = :m")
				.setParameter("g", gridId).setParameter("m", missionId).executeUpdate();
			return missionId;
		});
	}

	private long 내영상수() {
		return tx.execute(status -> ((Number) em
			.createNativeQuery("SELECT count(*) FROM videos WHERE user_id = :u")
			.setParameter("u", userId).getSingleResult()).longValue());
	}

	private void 업로드(MissionVideoService service, long missionId, LocalDateTime recordedAt) {
		tx.executeWithoutResult(status ->
			service.upload(userId, missionId, new MissionVideoUploadRequestDto(키(), (short) 10, recordedAt)));
	}

	@Test
	@DisplayName("스탬프_발급이_실패하면_영상도_롤백된다")
	void 스탬프_발급이_실패하면_영상도_롤백된다() {
		long missionId = 팝업(지금().minusDays(1), 지금().plusDays(1), 격자(0, 0));
		// 발급 단계에서 예외를 주입한다 — 확정이 먼저 커밋되면 영상만 남고 스탬프가 없는 상태가 굳는다.
		MissionAwardService 터지는발급 = (uid, gridId) -> {
			throw new IllegalStateException("스탬프 발급 실패");
		};

		assertThatThrownBy(() -> 업로드(service(Clock.systemUTC(), 터지는발급), missionId, 지금().minusHours(1)))
			.isInstanceOf(IllegalStateException.class);

		assertThat(내영상수()).isZero();
	}

	@Test
	@DisplayName("판정_시점에_미션이_종료돼_스탬프를_못_받으면_업로드_전체가_롤백된다")
	void 판정_시점에_미션이_종료돼_스탬프를_못_받으면_업로드_전체가_롤백된다() {
		// 첫째 창: 활성 판정은 통과했는데(주입 클럭이 기간 안) 판정 쿼리의 statement_timestamp() 시점에는
		// 이미 종료라 후보가 비는 상황이다. 마감 정각을 가로지르는 업로드가 이 모양이 된다.
		LocalDateTime endAt = 지금().minusMinutes(1);
		long missionId = 팝업(지금().minusDays(1), endAt, 격자(1, 0));
		MissionVideoService service = service(
			Clock.fixed(endAt.minusMinutes(1).toInstant(ZoneOffset.UTC), ZoneOffset.UTC), missionAwardService);

		assertThatThrownBy(() -> 업로드(service, missionId, endAt.minusHours(1)))
			.isInstanceOf(ApiException.class)
			.extracting("errorCode")
			.isEqualTo(MissionErrorCode.MISSION_UPLOAD_UNAVAILABLE);

		// 영상만 남고 스탬프가 없으면 그 미션은 종료 정리에 지워진다 — 그래서 업로드째 되돌린다.
		assertThat(내영상수()).isZero();
	}

	@Test
	@DisplayName("판정_직전에_판정_격자_집합이_바뀌어_스탬프를_못_받아도_영상이_남지_않는다")
	void 판정_직전에_판정_격자_집합이_바뀌어_스탬프를_못_받아도_영상이_남지_않는다() {
		// 둘째 창: 업로드가 대표 격자를 읽은 뒤 커밋하기 전에 팝업 시더가 판정 격자를 새 집합으로 갈아
		// 끼우고 커밋하면, 판정 조인이 옛 대표 격자를 후보로 찾지 못한다.
		long missionId = 팝업(지금().minusDays(1), 지금().plusDays(1), 격자(2, 0));
		MissionAwardService 재시딩후_발급 = (uid, gridId) -> {
			재시딩(missionId, 격자(2, 1));
			return missionAwardService.awardOnUpload(uid, gridId);
		};

		assertThatThrownBy(() -> 업로드(service(Clock.systemUTC(), 재시딩후_발급), missionId, 지금().minusHours(1)))
			.isInstanceOf(ApiException.class)
			.extracting("errorCode")
			.isEqualTo(MissionErrorCode.MISSION_UPLOAD_UNAVAILABLE);

		assertThat(내영상수()).isZero();
	}

	/**
	 * 팝업 주간 갱신의 좌표 정정을 재현한다 — 새 격자를 넣고 대표 격자를 옮긴 뒤 옛 격자를 지운다.
	 * 별도 트랜잭션에서 커밋해야 진행 중인 업로드가 그 결과를 보게 된다(REQUIRES_NEW).
	 */
	private void 재시딩(long missionId, String newGridId) {
		TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
		requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		requiresNew.executeWithoutResult(status -> {
			em.createNativeQuery("INSERT INTO mission_grids (mission_id, grid_id) VALUES (:m, :g)")
				.setParameter("m", missionId).setParameter("g", newGridId).executeUpdate();
			em.createNativeQuery("UPDATE missions SET representative_grid_id = :g WHERE id = :m")
				.setParameter("g", newGridId).setParameter("m", missionId).executeUpdate();
			em.createNativeQuery(
					"DELETE FROM mission_grids WHERE mission_id = :m AND grid_id <> :g")
				.setParameter("m", missionId).setParameter("g", newGridId).executeUpdate();
		});
	}
}
