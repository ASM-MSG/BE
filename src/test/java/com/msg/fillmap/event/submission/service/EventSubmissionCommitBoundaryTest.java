package com.msg.fillmap.event.submission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import com.msg.fillmap.event.exception.EventErrorCode;
import com.msg.fillmap.event.submission.dto.EventSubmissionAreaRectDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionCreateRequestDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionLocationRequestDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionUpdateRequestDto;
import com.msg.fillmap.event.submission.entity.EventSubmission;
import com.msg.fillmap.event.submission.entity.EventSubmissionAreaRect;
import com.msg.fillmap.event.submission.entity.EventSubmissionLocation;
import com.msg.fillmap.event.submission.entity.EventSubmissionType;
import com.msg.fillmap.event.submission.repository.EventSubmissionRepository;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 커밋 경계에 걸린 두 동작 (MSG-498). 하나는 재제출의 조건부 UPDATE 원자 전이고, 다른 하나는 이미지 복사
 * 이후 롤백의 보상 삭제다. 둘 다 실제 커밋·롤백이 일어나야 관찰되므로 {@code @Transactional} 롤백 격리를
 * 쓸 수 없다 — 합성 행은 커밋해 두고 {@code @AfterEach} 에서 대상 지정 삭제한다 (VideoDeleteConcurrencyTest 선례).
 */
@SpringBootTest
@DisplayName("행사 등재 신청 커밋 경계 (MSG-498, 실 DB)")
class EventSubmissionCommitBoundaryTest {

	private static final long JOIN_TIMEOUT_SEC = 30L;

	@Autowired
	private EventSubmissionService eventSubmissionService;

	@Autowired
	private EventSubmissionRepository submissionRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager txManager;

	@MockitoBean
	private S3Client s3Client;

	private TransactionTemplate tx;
	private long userId;

	@BeforeEach
	void setUp() {
		given(s3Client.headObject(any(HeadObjectRequest.class)))
			.willReturn(HeadObjectResponse.builder().contentLength(2048L).build());
		given(s3Client.copyObject(any(CopyObjectRequest.class))).willReturn(CopyObjectResponse.builder().build());
		given(s3Client.deleteObject(any(DeleteObjectRequest.class))).willReturn(DeleteObjectResponse.builder().build());
		tx = new TransactionTemplate(txManager);
		tx.executeWithoutResult(status -> {
			User user = User.createLocalUser("m498-boundary-" + UUID.randomUUID() + "@fillmap.dev", "hash", "담당자");
			ReflectionTestUtils.setField(user, "role", UserRole.ORG);
			userId = userRepository.save(user).getId();
		});
	}

	@AfterEach
	void tearDown() {
		// 커밋해 둔 합성 행만 지운다(실데이터 불가침). 위치·사각형·이력은 FK ON DELETE CASCADE 가 따라 지운다.
		tx.executeWithoutResult(status -> {
			jdbcTemplate.update("DELETE FROM event_submissions WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
		});
	}

	private EventSubmissionLocationRequestDto location() {
		return new EventSubmissionLocationRequestDto(
			List.of(new EventSubmissionAreaRectDto(16859, 16861, 11509, 11515)));
	}

	private EventSubmissionCreateRequestDto createRequest() {
		return new EventSubmissionCreateRequestDto(EventSubmissionType.FESTIVAL, "부산불꽃축제",
			"부산문화관광축제조직위원회", LocalDate.now(ZoneOffset.UTC).plusDays(30),
			LocalDate.now(ZoneOffset.UTC).plusDays(31), null, "멀티불꽃쇼, 드론 라이트쇼 운영",
			"광안리해수욕장 일원에서 열리는 부산 대표 불꽃 축제",
			"event-submissions/pending/%d/%s.jpg".formatted(userId, UUID.randomUUID()), List.of(location()));
	}

	/** 이미지 키를 생략한 재제출 — 기존 이미지 유지라 S3 를 건드리지 않는다. */
	private EventSubmissionUpdateRequestDto updateRequest() {
		return new EventSubmissionUpdateRequestDto("부산불꽃축제 2026", "부산문화관광축제조직위원회",
			LocalDate.now(ZoneOffset.UTC).plusDays(30), LocalDate.now(ZoneOffset.UTC).plusDays(31), null,
			"멀티불꽃쇼, 드론 라이트쇼 운영", "광안리해수욕장 일원에서 열리는 부산 대표 불꽃 축제",
			null, List.of(location()));
	}

	/** 반려 상태의 신청 하나를 커밋해 둔다 — 관리자 심사(MSG-500) 대역이라 상태는 SQL 로 만든다. */
	private long 반려된_신청을_커밋한다() {
		return tx.execute(status -> {
			LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
			EventSubmission submission = EventSubmission.submit(
				"FM-2026-%04d".formatted(submissionRepository.nextSubmissionSequence()),
				userId, EventSubmissionType.FESTIVAL, now);
			submission.updateForm("부산불꽃축제", "부산문화관광축제조직위원회", LocalDate.of(2026, 11, 7),
				LocalDate.of(2026, 11, 7), null, "멀티불꽃쇼", "광안리 일원에서 열리는 부산 대표 불꽃 축제",
				"event-submissions/original/%d/kept.jpg".formatted(userId), now);
			submission.replaceLocations(List.of(new EventSubmissionLocation("16860_11512",
				List.of(new EventSubmissionAreaRect(16859, 16861, 11509, 11515)))));
			long id = submissionRepository.save(submission).getId();
			submissionRepository.flush();
			jdbcTemplate.update("UPDATE event_submissions SET status = 'REJECTED' WHERE id = ?", id);
			return id;
		});
	}

	// 검증: FR-EVENT-14
	@Test
	@DisplayName("동시 재제출은 한 건만 성공하고 진 쪽은 13434 다 — 조건부 UPDATE 가 전이를 원자화한다")
	void 동시_재제출은_한_건만_성공한다() throws Exception {
		long submissionId = 반려된_신청을_커밋한다();
		ExecutorService executor = Executors.newFixedThreadPool(2);
		Callable<ApiException> attempt = () -> {
			try {
				eventSubmissionService.resubmit(userId, submissionId, updateRequest());
				return null;
			} catch (ApiException e) {
				return e;
			}
		};

		Future<ApiException> first = executor.submit(attempt);
		Future<ApiException> second = executor.submit(attempt);
		executor.shutdown();
		assertThat(executor.awaitTermination(JOIN_TIMEOUT_SEC, TimeUnit.SECONDS)).isTrue();

		List<ApiException> failures = Stream.of(first.get(), second.get()).filter(Objects::nonNull).toList();
		assertThat(failures).singleElement()
			.hasFieldOrPropertyWithValue("errorCode", EventErrorCode.SUBMISSION_NOT_EDITABLE);
		// 이력은 심사 중 한 줄만 늘어난다 — 둘 다 성공했다면 두 줄이 남는다.
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM event_submission_status_history WHERE event_submission_id = ?",
			Long.class, submissionId)).isEqualTo(1L);
	}

	// 검증: FR-EVENT-13
	@Test
	@DisplayName("복사 성공 후 커밋이 실패하면 확정본이 정리된다 — 아무도 참조하지 않는 고아를 남기지 않는다")
	void 복사_성공_후_커밋이_실패하면_원본이_정리된다() {
		tx.executeWithoutResult(status -> {
			eventSubmissionService.submit(userId, createRequest());
			status.setRollbackOnly();
		});

		ArgumentCaptor<CopyObjectRequest> copied = ArgumentCaptor.forClass(CopyObjectRequest.class);
		then(s3Client).should().copyObject(copied.capture());
		ArgumentCaptor<DeleteObjectRequest> deleted = ArgumentCaptor.forClass(DeleteObjectRequest.class);
		// 롤백이라 pending 정리(커밋 후 실행)는 돌지 않는다 — 지워지는 것은 방금 복사한 확정본 하나뿐이다.
		then(s3Client).should(times(1)).deleteObject(deleted.capture());
		assertThat(deleted.getValue().key()).isEqualTo(copied.getValue().destinationKey());
		assertThat(submissionRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId)).isEmpty();
	}

	// 검증: FR-EVENT-13
	@Test
	@DisplayName("복사 응답이 유실돼도 확정본이 정리된다 — 보상 등록이 복사 호출보다 앞이라서다")
	void 복사_응답이_유실돼도_확정본이_정리된다() {
		// S3 쪽에서는 복사가 끝났는데 응답만 유실·타임아웃된 상황(클라이언트에는 예외로 보인다).
		// 보상 등록이 복사 뒤에 있었다면 등록 전에 빠져나가 아무도 참조하지 않는 original 이 영구히 남는다.
		given(s3Client.copyObject(any(CopyObjectRequest.class)))
			.willThrow(SdkClientException.create("복사 응답 유실"));

		assertThatThrownBy(() -> tx.executeWithoutResult(status ->
			eventSubmissionService.submit(userId, createRequest())))
			.isInstanceOf(SdkException.class);

		ArgumentCaptor<CopyObjectRequest> copied = ArgumentCaptor.forClass(CopyObjectRequest.class);
		then(s3Client).should().copyObject(copied.capture());
		ArgumentCaptor<DeleteObjectRequest> deleted = ArgumentCaptor.forClass(DeleteObjectRequest.class);
		then(s3Client).should(times(1)).deleteObject(deleted.capture());
		assertThat(deleted.getValue().key()).isEqualTo(copied.getValue().destinationKey());
	}
}
