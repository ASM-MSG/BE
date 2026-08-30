package com.msg.fillmap.event.submission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;

import com.msg.fillmap.event.exception.EventErrorCode;
import com.msg.fillmap.event.submission.EventSubmissionFixtures;
import com.msg.fillmap.event.submission.dto.AdminEventUnpublishRequestDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionApproveResponseDto;
import com.msg.fillmap.event.submission.entity.EventSubmission;
import com.msg.fillmap.event.submission.entity.EventSubmissionStatus;
import com.msg.fillmap.event.submission.repository.EventSubmissionRepository;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.global.mail.MailSender;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.mission.dto.MissionResponseDto;
import com.msg.fillmap.mission.entity.Mission;
import com.msg.fillmap.mission.entity.MissionType;
import com.msg.fillmap.mission.repository.MissionRepository;
import com.msg.fillmap.mission.service.MissionQueryService;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 승인의 커밋 경계 두 가지 (MSG-500 §API 3, 실 DB): 같은 신청의 동시 승인과, 커밋 후 미션 스냅숏 무효화다.
 *
 * <p>둘 다 진짜 커밋이 필요해 클래스 수준 {@code @Transactional} 롤백 격리를 쓸 수 없다 — 동시 승인은
 * 두 트랜잭션이 실제로 경합해야 하고, 스냅숏 무효화는 afterCommit 훅이라 커밋이 없으면 아예 실행되지
 * 않는다(OrgAccountApprovalConcurrencyTest 와 같은 이유). 대신 만든 행을 직접 지워 공유 로컬 DB 를
 * 되돌린다.
 *
 * <p>신청은 접수 API 가 아니라 SQL 로 심는다 — 여기서 필요한 것은 "심사 중인 신청 행"뿐이고, 접수 경로를
 * 타면 기간 검증 때문에 오늘을 포함하는 기간을 만들기가 번거롭다(스냅숏 검증은 <b>지금 활성인</b> 미션이어야
 * 성립한다).
 */
@SpringBootTest
@DisplayName("행사 등재 신청 동시 승인과 커밋 후 스냅숏 (MSG-500, 실 DB)")
class EventSubmissionApprovalConcurrencyTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	/** 광안리 3행 7열 — 대표 격자가 정중앙으로 결정되는 홀수 직사각형이다 (접수 픽스처와 같은 영역). */
	private static final int MIN_GRID_Y = 16859;
	private static final int MAX_GRID_Y = 16861;
	private static final int MIN_GRID_X = 11509;
	private static final int MAX_GRID_X = 11515;
	private static final String CENTER_GRID_ID = "16860_11512";

	@Autowired
	private AdminEventSubmissionService adminEventSubmissionService;

	@Autowired
	private AdminApprovedEventService adminApprovedEventService;

	@Autowired
	private MissionQueryService missionQueryService;

	@Autowired
	private EventSubmissionRepository submissionRepository;

	@Autowired
	private MissionRepository missionRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@MockitoBean
	private S3Client s3Client;

	/** 중지 통지 발송을 갈아 끼운다 — 이 클래스가 보는 것은 발송이 아니라 커밋 후 스냅숏이다. */
	@MockitoBean
	private MailSender mailSender;

	private Long organizerId;
	private Long submissionId;
	private String submissionNo;

	@BeforeEach
	void setUp() {
		given(s3Client.copyObject(any(CopyObjectRequest.class))).willReturn(CopyObjectResponse.builder().build());
		organizerId = transactionTemplate.execute(status -> {
			User user = User.createLocalUser("approve-" + UUID.randomUUID() + "@fillmap.dev",
				passwordEncoder.encode("Initial1234"), "김담당");
			ReflectionTestUtils.setField(user, "role", UserRole.ORG);
			return userRepository.saveAndFlush(user).getId();
		});
		submissionNo = "FM-2026-" + UUID.randomUUID().toString().substring(0, 8);
		submissionId = 심사_중_신청을_심는다();
	}

	@AfterEach
	void tearDown() {
		// 신청을 먼저 지운다 — published_mission_id FK 가 살아 있으면 미션 삭제가 제약 위반이다.
		// 위치·사각형·이력은 신청 삭제에 ON DELETE CASCADE 로 따라간다.
		jdbcTemplate.update("DELETE FROM event_submissions WHERE id = ?", submissionId);
		jdbcTemplate.update("DELETE FROM missions WHERE source = 'ORG_SUBMISSION' AND source_key = ?", submissionNo);
		jdbcTemplate.update("DELETE FROM users WHERE id = ?", organizerId);
		// 이 클래스가 지운 미션이 스냅숏에 남지 않게 비운다 — 다음 조회가 DB 를 다시 읽는다.
		missionQueryService.invalidateSnapshot();
	}

	/** 오늘을 포함하는 기간의 심사 중 신청 1건 — 승인하면 지금 활성인 미션이 된다. */
	private long 심사_중_신청을_심는다() {
		LocalDate today = LocalDate.now(KST);
		return EventSubmissionFixtures.seedInReviewSubmission(jdbcTemplate, organizerId, submissionNo,
			today.minusDays(1), today.plusDays(1), MIN_GRID_Y, MAX_GRID_Y, MIN_GRID_X, MAX_GRID_X, CENTER_GRID_ID);
	}

	private List<Mission> 승인_미션들() {
		return missionRepository.findBySource("ORG_SUBMISSION").stream()
			.filter(mission -> submissionNo.equals(mission.getSourceKey()))
			.toList();
	}

	// 검증: FR-EVENT-15
	@Test
	@DisplayName("같은 신청을 동시에 두 번 승인하면 한 번만 반영되고 늦은 쪽은 13450 이다")
	void 같은_신청을_동시에_두_번_승인하면_한_번만_반영된다() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CyclicBarrier barrier = new CyclicBarrier(2);
		try {
			Future<EventSubmissionApproveResponseDto> first = executor.submit(() -> 승인한다(barrier));
			Future<EventSubmissionApproveResponseDto> second = executor.submit(() -> 승인한다(barrier));

			List<Throwable> failures = Stream.of(결과를_받는다(first), 결과를_받는다(second))
				.filter(Objects::nonNull)
				.toList();

			assertThat(failures).hasSize(1).first().isInstanceOf(ApiException.class);
			assertThat(((ApiException) failures.getFirst()).getErrorCode())
				.isEqualTo(EventErrorCode.SUBMISSION_STATUS_NOT_REVIEWABLE);
			// 미션이 두 번 생기지 않는다 — 조건부 UPDATE 가 1차 방어이고 (source, source_key) 부분 유니크가 백스톱이다.
			assertThat(승인_미션들()).hasSize(1);
			assertThat(submissionRepository.findById(submissionId).orElseThrow().getApprovalNo()).isNotNull();
		} finally {
			executor.shutdownNow();
		}
	}

	// 검증: FR-EVENT-15
	@Test
	@DisplayName("승인 산출물 생성이 실패하면 전이와 이력까지 함께 롤백된다 — 절반만 반영되지 않는다")
	void 승인_산출물_생성이_실패하면_전이와_이력이_함께_롤백된다() {
		// 전이 뒤에 오는 단계(공개 이미지 복사)를 실패시킨다 — 상태는 이미 APPROVED 로 바뀐 시점이라,
		// 롤백이 없으면 "승인됐는데 미션이 없는" 절반이 그대로 커밋된다.
		given(s3Client.copyObject(any(CopyObjectRequest.class)))
			.willThrow(SdkClientException.create("S3 복사 실패"));

		assertThatThrownBy(() -> adminEventSubmissionService.approve(submissionId))
			.isInstanceOf(SdkException.class);

		EventSubmission rolledBack = submissionRepository.findById(submissionId).orElseThrow();
		assertThat(rolledBack.getStatus()).isEqualTo(EventSubmissionStatus.IN_REVIEW);
		assertThat(rolledBack.getApprovalNo()).isNull();
		assertThat(rolledBack.getPublishedMissionId()).isNull();
		assertThat(승인_미션들()).isEmpty();
		// 이력도 접수 한 행 그대로다 — 승인 행이 남으면 콘솔이 승인됐다 취소된 것처럼 그린다.
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM event_submission_status_history WHERE event_submission_id = ?",
			Long.class, submissionId)).isEqualTo(1L);
	}

	// 검증: FR-EVENT-15
	@Test
	@DisplayName("승인 커밋 후 스냅숏이 무효화되어 TTL 전에도 목록에 실린다")
	void 승인_커밋_후_미션_스냅숏이_무효화되어_TTL_전에도_목록에_실린다() {
		GridPoint center = GridEncoder.center(CENTER_GRID_ID);
		ViewportBounds viewport = new ViewportBounds(
			center.lat() - 0.01, center.lon() - 0.01, center.lat() + 0.01, center.lon() + 0.01);
		// 캐시를 먼저 적재한다 — 무효화가 없으면 이 스냅숏이 최대 1시간 그대로 쓰인다.
		List<MissionResponseDto> before = missionQueryService.getMissionsInViewport(viewport, MissionType.EVENT);

		long missionId = 승인_미션들().isEmpty()
			? approveAndReturnMissionId()
			: 승인_미션들().getFirst().getId();

		List<MissionResponseDto> after = missionQueryService.getMissionsInViewport(viewport, MissionType.EVENT);
		assertThat(before).extracting(MissionResponseDto::missionId).doesNotContain(missionId);
		assertThat(after).extracting(MissionResponseDto::missionId).contains(missionId);
	}

	// 검증: FR-EVENT-17
	@Test
	@DisplayName("중지 커밋 후 스냅숏이 무효화되어 TTL 전에도 목록에서 빠진다")
	void 중지_커밋_후_미션_스냅숏이_무효화되어_TTL_전에도_목록에서_빠진다() {
		GridPoint center = GridEncoder.center(CENTER_GRID_ID);
		ViewportBounds viewport = new ViewportBounds(
			center.lat() - 0.01, center.lon() - 0.01, center.lat() + 0.01, center.lon() + 0.01);
		long missionId = approveAndReturnMissionId();
		// 승인 미션이 실린 스냅숏을 적재한다 — 무효화가 없으면 중지 뒤에도 최대 1시간 이 스냅숏이 쓰인다.
		List<MissionResponseDto> before = missionQueryService.getMissionsInViewport(viewport, MissionType.EVENT);

		adminApprovedEventService.unpublish(submissionId,
			new AdminEventUnpublishRequestDto("행사가 취소되어 노출을 중지합니다"));

		List<MissionResponseDto> after = missionQueryService.getMissionsInViewport(viewport, MissionType.EVENT);
		assertThat(before).extracting(MissionResponseDto::missionId).contains(missionId);
		assertThat(after).extracting(MissionResponseDto::missionId).doesNotContain(missionId);
	}

	private long approveAndReturnMissionId() {
		adminEventSubmissionService.approve(submissionId);
		return submissionRepository.findById(submissionId).orElseThrow().getPublishedMissionId();
	}

	private EventSubmissionApproveResponseDto 승인한다(CyclicBarrier barrier) throws Exception {
		barrier.await();
		return adminEventSubmissionService.approve(submissionId);
	}

	/** 성공이면 null, 실패면 그 예외 — 어느 쪽이 이겼는지는 스케줄에 달려 있어 결과로 가른다. */
	private Throwable 결과를_받는다(Future<EventSubmissionApproveResponseDto> future) throws InterruptedException {
		try {
			future.get();
			return null;
		} catch (ExecutionException e) {
			return e.getCause();
		}
	}
}
