package com.msg.fillmap.event.submission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;

import com.msg.fillmap.event.exception.EventErrorCode;
import com.msg.fillmap.event.submission.dto.EventSubmissionApproveResponseDto;
import com.msg.fillmap.event.submission.repository.EventSubmissionRepository;
import com.msg.fillmap.global.exception.ApiException;
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
		jdbcTemplate.update("""
			INSERT INTO event_submissions
				(submission_no, user_id, type, status, title, organizer_name, starts_on, ends_on,
				 program_description, description, image_key, created_at, updated_at)
			VALUES (?, ?, 'FESTIVAL', 'IN_REVIEW', '광안리 불꽃축제', '부산문화관광축제조직위원회', ?, ?,
				'멀티불꽃쇼', '광안리 일원에서 열리는 부산 대표 불꽃 축제',
				'event-submissions/original/1/a.jpg', ?, ?)
			""", submissionNo, organizerId, today.minusDays(1), today.plusDays(1),
			LocalDateTime.now(ZoneOffset.UTC), LocalDateTime.now(ZoneOffset.UTC));
		Long id = jdbcTemplate.queryForObject(
			"SELECT id FROM event_submissions WHERE submission_no = ?", Long.class, submissionNo);

		jdbcTemplate.update("""
			INSERT INTO event_submission_locations (event_submission_id, display_order, representative_grid_id)
			VALUES (?, 1, ?)
			""", id, CENTER_GRID_ID);
		Long locationId = jdbcTemplate.queryForObject(
			"SELECT id FROM event_submission_locations WHERE event_submission_id = ?", Long.class, id);
		jdbcTemplate.update("""
			INSERT INTO event_submission_location_rects
				(event_submission_location_id, min_grid_y, max_grid_y, min_grid_x, max_grid_x)
			VALUES (?, ?, ?, ?, ?)
			""", locationId, MIN_GRID_Y, MAX_GRID_Y, MIN_GRID_X, MAX_GRID_X);
		return id;
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
