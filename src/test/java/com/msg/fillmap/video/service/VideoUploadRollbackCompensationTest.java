package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.hotzone.service.HotScoreCommandService;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.dto.VideoReplaceRequestDto;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;

/**
 * 업로드·교체 확정 롤백 시 S3 original 복사본 보상 삭제 (MSG-247, 실 PostGIS).
 *
 * pending→original 복사는 비트랜잭션 부수효과라, 복사 이후 트랜잭션이 실패하면 DB 는 롤백돼도
 * original 복사본이 영구 고아로 남는다(라이프사이클은 pending 전용). 복사 직후 등록한
 * afterCompletion(STATUS_ROLLED_BACK) 보상이 그 키를 지우는지를 실제 커밋/롤백 경계로 검증한다.
 *
 * 롤백 관찰이 목적이라 @Transactional 롤백 격리를 못 쓴다 — VideoRegionStatsAtomicityTest 패턴대로
 * user 는 커밋해 두고 saveVideo/replaceVideo 만 TransactionTemplate 으로 롤백시킨다. setRollbackOnly 는
 * 예외 롤백과 같은 afterCompletion(STATUS_ROLLED_BACK) 경로를 탄다. 커밋한 합성 행은 @AfterEach 에서
 * 대상 지정 삭제로 걷어낸다(truncate 아님 — 실데이터 불가침).
 */
@SpringBootTest
@DisplayName("확정 롤백 시 original 보상 삭제 (실 PostGIS)")
class VideoUploadRollbackCompensationTest {

	// 다른 테스트(VideoRegionStatsAtomicityTest 40000/108700 등)와 안 겹치는 서해 공해상 격자.
	// 무라벨(region 없음)이라 region_stats 가 안 생겨 수집률 경로와도 독립이다.
	private static final long GY = 17314L;
	private static final long GX = 7634L;
	private static final GridPoint CENTER = GridFixtures.pointAt(GY + 0.5, GX + 0.5);
	private static final double LAT = CENTER.lat();
	private static final double LON = CENTER.lon();

	@Autowired
	private VideoService videoService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@Autowired
	private PlatformTransactionManager txManager;

	@MockitoBean
	private S3Client s3Client;

	// 커밋 성공 케이스의 afterCommit 인코딩 무력화 — 실 인코딩이 돌면 없는 파일로 FAILED 마킹이 낀다.
	@MockitoBean
	private VideoEncodingService videoEncodingService;

	// 커밋 성공 케이스의 afterCommit 핫스코어 무력화 (MSG-183) — 실 빈이 돌면 공유 로컬 Redis 현재
	// 버킷에 테스트 격자 점수가 54시간 남는다. 이 테스트는 핫스코어와 무관하다 (S3 mock 과 같은 취지).
	@MockitoBean
	private HotScoreCommandService hotScoreCommandService;

	private TransactionTemplate tx;
	private long userId;
	private String gridId;
	private ListAppender<ILoggingEvent> logs;

	@BeforeEach
	void setUp() {
		logs = new ListAppender<>();
		logs.start();
		((Logger) LoggerFactory.getLogger(VideoServiceImpl.class)).addAppender(logs);

		given(s3Client.headObject(any(HeadObjectRequest.class))).willReturn(HeadObjectResponse.builder().build());
		given(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
			.willReturn(DeleteObjectsResponse.builder().build());

		tx = new TransactionTemplate(txManager);
		gridId = GridEncoder.encode(LAT, LON);
		tx.executeWithoutResult(status -> userId = userRepository.save(User.createLocalUser(
			"rollback-comp-" + System.nanoTime() + "@example.com", "hash", "보상테스터")).getId());
	}

	@AfterEach
	void tearDown() {
		((Logger) LoggerFactory.getLogger(VideoServiceImpl.class)).detachAppender(logs);
		// 커밋해 둔 합성 행만 FK 역순으로 대상 지정 삭제한다(실데이터 불가침).
		tx.executeWithoutResult(status -> {
			exec("DELETE FROM user_badges WHERE user_id = :u", "u", userId);
			exec("DELETE FROM streaks WHERE user_id = :u", "u", userId);
			exec("DELETE FROM user_grids WHERE user_id = :u", "u", userId);
			exec("DELETE FROM videos WHERE user_id = :u", "u", userId);
			exec("DELETE FROM grids WHERE grid_id = :v", "v", gridId);
			exec("DELETE FROM users WHERE id = :u", "u", userId);
		});
	}

	private void exec(String sql, String param, Object value) {
		em.createNativeQuery(sql).setParameter(param, value).executeUpdate();
	}

	private String pendingKey() {
		return "videos/pending/" + userId + "/" + UUID.randomUUID() + ".mp4";
	}

	private VideoUploadRequestDto uploadRequest(String s3Key) {
		return new VideoUploadRequestDto(s3Key, LAT, LON, (short) 10, LocalDateTime.now(ZoneOffset.UTC), "PRIVATE");
	}

	private List<String> deletedKeys() {
		ArgumentCaptor<DeleteObjectsRequest> captor = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
		then(s3Client).should().deleteObjects(captor.capture());
		return captor.getValue().delete().objects().stream().map(ObjectIdentifier::key).toList();
	}

	/**
	 * 실제 복사된 목적지 키들 — original 키가 확정 시도마다 새 UUID 로 발급되므로(MSG-247 2R)
	 * 테스트는 키를 예측하지 않고 copyObject 캡처에서 얻어 삭제 캡처와 대조한다.
	 */
	private List<String> copiedDestinations() {
		ArgumentCaptor<CopyObjectRequest> captor = ArgumentCaptor.forClass(CopyObjectRequest.class);
		then(s3Client).should(atLeast(1)).copyObject(captor.capture());
		return captor.getAllValues().stream().map(CopyObjectRequest::destinationKey).toList();
	}

	private long rowCount(String table) {
		return ((Number) em.createNativeQuery("SELECT count(*) FROM " + table + " WHERE user_id = :u")
			.setParameter("u", userId).getSingleResult()).longValue();
	}

	private String committedOriginalKey() {
		return (String) em.createNativeQuery("SELECT original_s3_key FROM videos WHERE user_id = :u")
			.setParameter("u", userId).getSingleResult();
	}

	@Test
	@DisplayName("확정 트랜잭션이 롤백되면 original 복사본을 지운다")
	void 확정_트랜잭션이_롤백되면_original_복사본을_지운다() {
		String pending = pendingKey();

		tx.executeWithoutResult(status -> {
			videoService.saveVideo(userId, uploadRequest(pending));
			status.setRollbackOnly();   // 복사 이후 단계(점령·뱃지·flush) 실패와 같은 롤백 경로
		});

		List<String> copied = copiedDestinations();
		assertThat(copied).hasSize(1);
		assertThat(copied.get(0)).startsWith("videos/original/" + userId + "/");
		assertThat(deletedKeys()).as("롤백 보상이 복사해 둔 original 키를 지워야 한다")
			.containsExactly(copied.get(0));
		tx.executeWithoutResult(status -> {
			assertThat(rowCount("videos")).as("DB 는 롤백돼 row 가 없다").isZero();
			assertThat(rowCount("user_grids")).isZero();
		});
	}

	@Test
	@DisplayName("확정 커밋이 성공하면 original 을 지우지 않는다")
	void 확정_커밋이_성공하면_original_을_지우지_않는다() {
		tx.executeWithoutResult(status -> videoService.saveVideo(userId, uploadRequest(pendingKey())));

		// 방금 확정한 원본이 지워지면 재생 불가 — 보상은 STATUS_ROLLED_BACK 에서만 돌아야 한다.
		then(s3Client).should(never()).deleteObjects(any(DeleteObjectsRequest.class));
	}

	@Test
	@DisplayName("교체 롤백은 새 original 만 지우고 옛 키는 남긴다")
	void 교체_롤백은_새_original_만_지우고_옛_키는_남긴다() {
		Long videoId = tx.execute(status -> videoService.saveVideo(userId, uploadRequest(pendingKey())).videoId());
		// 옛 블러본이 있는 상태를 흉내낸다 — 옛 키 정리(afterCommit)가 롤백에 섞여 들면 캡처에 드러난다.
		tx.executeWithoutResult(status -> exec(
			"UPDATE videos SET blurred_s3_key = 'videos/blurred/rollback-comp-old.mp4' WHERE id = :u", "u", videoId));
		String newPending = pendingKey();

		tx.executeWithoutResult(status -> {
			videoService.replaceVideo(userId, videoId,
				new VideoReplaceRequestDto(newPending, null, null, (short) 7, LocalDateTime.now(ZoneOffset.UTC)));
			status.setRollbackOnly();
		});

		// 복사는 2회다: 업로드 확정(커밋) + 교체(롤백). 삭제 대상은 교체가 복사한 새 original 뿐이어야 한다.
		List<String> copied = copiedDestinations();
		assertThat(copied).hasSize(2);
		// 롤백된 row 는 옛 파일을 그대로 가리킨다 — 옛 original·블러본이 지워지면 데이터 유실이다.
		assertThat(deletedKeys()).containsExactly(copied.get(1));
	}

	@Test
	@DisplayName("롤백 후 같은 pending 재확정 — 첫 시도의 보상이 재시도의 객체를 지우지 않는다")
	void 롤백_후_같은_pending_재확정시_보상이_재시도_객체를_지우지_않는다() {
		// Codex 2R P1: 롤백으로 유니크 락이 풀린 뒤 afterCompletion 이 돌므로, 후속 클레이머의 복사·커밋과
		// 패자의 보상 삭제는 S3 호출 순서가 미보장이다. original 키가 시도마다 새로 발급되면 목적지 공유
		// 자체가 불가능해 순서와 무관하게 안전하다 — 그 유일성을 고정한다.
		String pending = pendingKey();
		tx.executeWithoutResult(status -> {
			videoService.saveVideo(userId, uploadRequest(pending));
			status.setRollbackOnly();
		});

		// 재시도 — 롤백된 확정은 같은 pending 으로 다시 확정할 수 있어야 한다(이중 확정 차단과 별개).
		tx.executeWithoutResult(status -> videoService.saveVideo(userId, uploadRequest(pending)));

		List<String> copied = copiedDestinations();
		assertThat(copied).hasSize(2);
		assertThat(copied.get(1)).as("시도별 키 — 같은 pending 이라도 목적지가 달라야 한다")
			.isNotEqualTo(copied.get(0));
		assertThat(deletedKeys()).as("첫 시도의 보상은 자기 키만 지운다")
			.containsExactly(copied.get(0)).doesNotContain(copied.get(1));
		assertThat(committedOriginalKey()).as("재시도가 커밋한 row 는 삭제되지 않은 키를 가리킨다")
			.isEqualTo(copied.get(1));
	}

	@Test
	@DisplayName("보상 삭제가 실패해도 예외 없이 로그만 남긴다")
	void 보상_삭제가_실패해도_예외_없이_로그만_남긴다() {
		given(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
			.willThrow(SdkException.builder().message("네트워크 단절").build());

		assertThatCode(() -> tx.executeWithoutResult(status -> {
			videoService.saveVideo(userId, uploadRequest(pendingKey()));
			status.setRollbackOnly();
		})).as("이미 실패한 요청을 보상 실패로 다시 오염시키지 않는다").doesNotThrowAnyException();

		assertThat(logs.list)
			.filteredOn(event -> event.getLevel() == Level.ERROR)
			.as("베스트 에포트 — 실패는 로그로만 드러나야 한다")
			.isNotEmpty();
	}
}
