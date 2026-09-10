package com.msg.fillmap.event.submission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.time.LocalDateTime;
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

import com.msg.fillmap.event.EventTestFixtures;
import com.msg.fillmap.event.exception.EventErrorCode;
import com.msg.fillmap.event.repository.EventLocationGridRepository;
import com.msg.fillmap.event.repository.EventLocationRepository;
import com.msg.fillmap.event.repository.EventOccurrenceRepository;
import com.msg.fillmap.event.repository.EventSeriesRepository;
import com.msg.fillmap.event.submission.dto.EventSubmissionApproveResponseDto;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 같은 회차를 노리는 두 참여형 신청의 동시 승인 (MSG-500 D-9, 실 DB). 부모 회차 비관 잠금이 두 승인을
 * 직렬화하는지를 본다 — 잠금이 없으면 두 겹침 사전 검사가 <b>모두 통과</b>하고, 회차 내 격자 단일 귀속이
 * 지연 제약이라 지는 쪽이 커밋 시점 500(DataIntegrityViolation)으로 끝난다. 잠금이 있으면 늦은 쪽은
 * 이긴 쪽의 격자를 보고 <b>읽히는 13452</b> 를 받는다(다음 조작은 AREA 코드 반려다).
 *
 * <p>진짜 동시 트랜잭션이 필요해 클래스 수준 {@code @Transactional} 롤백 격리를 쓸 수 없다
 * (EventSubmissionApprovalConcurrencyTest 와 같은 이유). 만든 행은 FK 순서대로 직접 지운다.
 */
@SpringBootTest
@DisplayName("참여형 동시 승인 직렬화 (MSG-500 D-9, 실 DB)")
class EventParticipationApprovalConcurrencyTest {

	/** 서해 먼바다 — 공유 로컬 DB 의 육상 실데이터와 겹치지 않는 자리다. */
	private static final int SEA_GRID_Y = 19100;
	private static final int SEA_GRID_X = 5100;
	private static final String CONTESTED_GRID_ID = SEA_GRID_Y + "_" + SEA_GRID_X;

	@Autowired
	private AdminEventSubmissionService adminEventSubmissionService;

	@Autowired
	private EventSeriesRepository seriesRepository;

	@Autowired
	private EventOccurrenceRepository occurrenceRepository;

	@Autowired
	private EventLocationRepository locationRepository;

	@Autowired
	private EventLocationGridRepository locationGridRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TransactionTemplate transactionTemplate;

	/** 승인이 커버 이미지를 공개 프리픽스로 복사한다 — 이 클래스가 보는 것은 복사가 아니라 잠금이다. */
	@MockitoBean
	private S3Client s3Client;

	private Long userId;
	private Long occurrenceId;
	private Long seriesId;
	private long firstSubmissionId;
	private long secondSubmissionId;

	@BeforeEach
	void setUp() {
		given(s3Client.copyObject(any(CopyObjectRequest.class))).willReturn(CopyObjectResponse.builder().build());
		transactionTemplate.executeWithoutResult(status -> {
			User user = User.createLocalUser("concurrent-part-" + UUID.randomUUID() + "@fillmap.dev",
				passwordEncoder.encode("Initial1234"), "김담당");
			ReflectionTestUtils.setField(user, "role", UserRole.ORG);
			userId = userRepository.saveAndFlush(user).getId();

			EventTestFixtures fixtures = new EventTestFixtures(seriesRepository, occurrenceRepository,
				locationRepository, locationGridRepository);
			LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
			var occurrence = fixtures.회차(fixtures.시리즈(), now.minusDays(1), now.plusDays(60), CONTESTED_GRID_ID);
			occurrenceId = occurrence.getId();
			seriesId = occurrence.getSeries().getId();
		});
		// 두 신청이 같은 칸을 노린다 — 잠금이 없으면 둘 다 사전 검사를 통과한다.
		firstSubmissionId = 참여형_신청을_심는다();
		secondSubmissionId = 참여형_신청을_심는다();
	}

	@AfterEach
	void tearDown() {
		transactionTemplate.executeWithoutResult(status -> {
			jdbcTemplate.update("DELETE FROM event_submissions WHERE user_id = ?", userId);
			jdbcTemplate.update("""
				DELETE FROM event_location_grids WHERE event_location_id IN
					(SELECT id FROM event_locations WHERE event_occurrence_id = ?)
				""", occurrenceId);
			jdbcTemplate.update("DELETE FROM event_locations WHERE event_occurrence_id = ?", occurrenceId);
			jdbcTemplate.update("DELETE FROM event_occurrences WHERE id = ?", occurrenceId);
			jdbcTemplate.update("DELETE FROM event_series WHERE id = ?", seriesId);
			jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
		});
	}

	/** 접수 API 를 타지 않고 심는다 — 필요한 것은 "같은 칸을 노리는 심사 중 참여형 신청" 두 건이다. */
	private long 참여형_신청을_심는다() {
		String submissionNo = "FM-2026-" + UUID.randomUUID().toString().substring(0, 8);
		LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
		jdbcTemplate.update("""
			INSERT INTO event_submissions
				(submission_no, user_id, type, status, title, organizer_name, starts_on, ends_on,
				 participation_method, description, image_key, created_at, updated_at, parent_event_occurrence_id)
			VALUES (?, ?, 'EVENT', 'IN_REVIEW', '참여형 부스', '필맵 파트너스', ?, ?,
				'현장에서 스탬프를 찍는 방식입니다', '이벤트 참여형 부스를 운영합니다',
				'event-submissions/original/1/a.jpg', ?, ?, ?)
			""", submissionNo, userId, LocalDate.now().plusDays(1), LocalDate.now().plusDays(9), now, now,
			occurrenceId);
		Long submissionId = jdbcTemplate.queryForObject(
			"SELECT id FROM event_submissions WHERE submission_no = ?", Long.class, submissionNo);
		jdbcTemplate.update("""
			INSERT INTO event_submission_locations (event_submission_id, display_order, representative_grid_id)
			VALUES (?, 1, ?)
			""", submissionId, CONTESTED_GRID_ID);
		Long locationId = jdbcTemplate.queryForObject(
			"SELECT id FROM event_submission_locations WHERE event_submission_id = ?", Long.class, submissionId);
		jdbcTemplate.update("""
			INSERT INTO event_submission_location_rects
				(event_submission_location_id, min_grid_y, max_grid_y, min_grid_x, max_grid_x)
			VALUES (?, ?, ?, ?, ?)
			""", locationId, SEA_GRID_Y, SEA_GRID_Y, SEA_GRID_X, SEA_GRID_X);
		return submissionId;
	}

	// 검증: FR-EVENT-15
	@Test
	@DisplayName("같은 회차를 노리는 두 신청의 동시 승인은 직렬화되어 늦은 쪽이 13452 다")
	void 같은_회차를_노리는_두_신청의_동시_승인은_늦은_쪽이_13452다() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CyclicBarrier barrier = new CyclicBarrier(2);
		try {
			Future<EventSubmissionApproveResponseDto> first = executor.submit(() -> 승인한다(barrier,
				firstSubmissionId));
			Future<EventSubmissionApproveResponseDto> second = executor.submit(() -> 승인한다(barrier,
				secondSubmissionId));

			List<Throwable> failures = Stream.of(결과를_받는다(first), 결과를_받는다(second))
				.filter(Objects::nonNull)
				.toList();

			// 500(지연 제약 위반)이 아니라 읽히는 409 다 — 그 차이를 만드는 것이 회차 잠금이다.
			assertThat(failures).hasSize(1).first().isInstanceOf(ApiException.class);
			assertThat(((ApiException) failures.getFirst()).getErrorCode())
				.isEqualTo(EventErrorCode.SUBMISSION_GRID_CONFLICT);
			assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM event_locations WHERE event_occurrence_id = ?", Long.class, occurrenceId))
				.isEqualTo(1L);
		} finally {
			executor.shutdownNow();
		}
	}

	private EventSubmissionApproveResponseDto 승인한다(CyclicBarrier barrier, long submissionId) throws Exception {
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
