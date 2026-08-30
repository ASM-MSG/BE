package com.msg.fillmap.event.submission.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.user.entity.OrgEmailChangeRequest;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.repository.OrgEmailChangeRequestRepository;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 심사 스키마 V51 (MSG-500, 실 DB). 검증 대상이 전부 CHECK 제약이라 엔티티만 봐서는 성립하지 않는다 —
 * "승인 행에만 승인 번호", "중지 기록은 짝으로", "반려 행에만 사유"는 DB 가 강제하는 성질이고, 이 뒤의
 * 모든 심사 로직이 그 강제를 백스톱으로 깔고 짜인다.
 * <p>
 * 위반 검사는 한 테스트에 하나씩 둔다 — PostgreSQL 은 제약 위반이 트랜잭션을 통째로 중단시켜, 같은
 * 트랜잭션에서 두 번째 문장을 실행할 수 없다. {@code @Transactional} 롤백 격리로 공유 로컬 DB 에
 * 계정·신청을 남기지 않는다 (OrgAccountRequestPersistenceTest 선례).
 */
@SpringBootTest
@Transactional
@DisplayName("행사 등재 심사 스키마 (V51, 실 DB)")
class EventSubmissionReviewSchemaTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private OrgEmailChangeRequestRepository emailChangeRequestRepository;

	@Autowired
	private EventSubmissionRepository submissionRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private EntityManager entityManager;

	private User organizer;

	@BeforeEach
	void setUp() {
		organizer = saveOrganizer();
	}

	private User saveOrganizer() {
		User user = User.createLocalUser("organizer-" + UUID.randomUUID() + "@fillmap.dev",
			passwordEncoder.encode("Initial1234"), "담당자");
		ReflectionTestUtils.setField(user, "role", UserRole.ORG);
		return userRepository.saveAndFlush(user);
	}

	private LocalDateTime now() {
		return LocalDateTime.now(ZoneOffset.UTC);
	}

	/** 신청 1건을 SQL 로 심는다 — 접수 경로(MSG-498)를 타지 않고 스키마만 보는 테스트라서다. */
	private long 신청을_심는다(String status, String approvalNo) {
		String submissionNo = "FM-2026-" + UUID.randomUUID().toString().substring(0, 8);
		jdbcTemplate.update("""
			INSERT INTO event_submissions
				(submission_no, user_id, type, status, title, organizer_name, starts_on, ends_on,
				 program_description, description, image_key, created_at, updated_at, approval_no)
			VALUES (?, ?, 'FESTIVAL', ?, '부산불꽃축제', '부산문화관광축제조직위원회', ?, ?,
				'멀티불꽃쇼', '광안리 일원에서 열리는 부산 대표 불꽃 축제', 'event-submissions/original/1/a.jpg',
				?, ?, ?)
			""", submissionNo, organizer.getId(), status, LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 7),
			now(), now(), approvalNo);
		return jdbcTemplate.queryForObject(
			"SELECT id FROM event_submissions WHERE submission_no = ?", Long.class, submissionNo);
	}

	private long 접수한_아이디_변경_요청() {
		emailChangeRequestRepository.upsertPending(organizer.getId(), "new-" + UUID.randomUUID() + "@fillmap.dev",
			now());
		entityManager.clear();
		return emailChangeRequestRepository.findAllByUserId(organizer.getId()).get(0).getId();
	}

	@Nested
	@DisplayName("승인 흔적")
	class ApprovalTrace {

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("승인 번호 없는 승인 행은 저장되지 않는다")
		void 승인_번호_없는_승인_행은_저장되지_않는다() {
			assertThatThrownBy(() -> 신청을_심는다("APPROVED", null))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("chk_event_sub_approval");
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("심사 중인 신청에는 승인 번호가 붙지 않는다")
		void 심사_중인_신청에는_승인_번호가_붙지_않는다() {
			assertThatThrownBy(() -> 신청을_심는다("IN_REVIEW", "APR-2026-0001"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("chk_event_sub_approval");
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("승인 번호는 신청 사이에서 중복되지 않는다")
		void 승인_번호는_신청_사이에서_중복되지_않는다() {
			신청을_심는다("APPROVED", "APR-2026-9001");

			assertThatThrownBy(() -> 신청을_심는다("APPROVED", "APR-2026-9001"))
				.isInstanceOf(DataIntegrityViolationException.class);
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("승인된 신청은 승인 번호와 산출물 링크를 엔티티로 읽는다")
		void 승인된_신청은_승인_번호와_산출물_링크를_엔티티로_읽는다() {
			long id = 신청을_심는다("APPROVED", "APR-2026-9002");
			entityManager.clear();

			assertThat(submissionRepository.findById(id)).get()
				.satisfies(submission -> {
					assertThat(submission.getApprovalNo()).isEqualTo("APR-2026-9002");
					assertThat(submission.getPublishedMissionId()).isNull();
					assertThat(submission.getUnpublishedAt()).isNull();
					assertThat(submission.getUnpublishReason()).isNull();
				});
		}
	}

	@Nested
	@DisplayName("노출 중지 기록")
	class UnpublishTrace {

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("중지 시각과 사유는 짝으로만 저장된다")
		void 중지_시각과_사유는_짝으로만_저장된다() {
			long id = 신청을_심는다("APPROVED", "APR-2026-9003");

			assertThatThrownBy(() -> jdbcTemplate.update(
				"UPDATE event_submissions SET unpublished_at = ? WHERE id = ?", now(), id))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("chk_event_sub_unpublish_pair");
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("승인되지 않은 신청은 중지 기록을 가질 수 없다")
		void 승인되지_않은_신청은_중지_기록을_가질_수_없다() {
			long id = 신청을_심는다("IN_REVIEW", null);

			assertThatThrownBy(() -> jdbcTemplate.update(
				"UPDATE event_submissions SET unpublished_at = ?, unpublish_reason = ? WHERE id = ?",
				now(), "행사가 취소되었습니다", id))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("chk_event_sub_unpublish_approved");
		}
	}

	@Nested
	@DisplayName("확장 컬럼")
	class AddedColumns {

		/**
		 * 컬럼 이름 오타를 잡는 유일한 자리다 — 없는 컬럼을 물으면 쿼리 자체가 실패한다. 값 단언("아직 아무도
		 * 안 채웠다")은 이 6종을 쓰는 코드가 M6 뿐이라 성립하고, 표가 비어 있어도 COUNT 0 이라 공유 로컬 DB 의
		 * 데이터 유무에 끌려가지 않는다.
		 */
		// 검증: FR-EVENT-15
		@Test
		@DisplayName("행사 위치의 참여 속성 6종은 아직 어느 행에도 채워지지 않았다")
		void 행사_위치의_참여_속성은_아직_어느_행에도_없다() {
			assertThat(jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM event_locations
				WHERE organizer_name IS NOT NULL OR description IS NOT NULL
				   OR starts_on IS NOT NULL OR ends_on IS NOT NULL
				   OR participation_method IS NOT NULL OR image_key IS NOT NULL
				""", Long.class)).isZero();
		}
	}

	@Nested
	@DisplayName("아이디 변경 요청 처리 흔적")
	class EmailChangeTrace {

		// 검증: FR-USER-16
		@Test
		@DisplayName("접수된 요청은 처리 시각도 반려 사유도 없다")
		void 접수된_요청은_처리_시각도_반려_사유도_없다() {
			long requestId = 접수한_아이디_변경_요청();

			OrgEmailChangeRequest request = emailChangeRequestRepository.findById(requestId).orElseThrow();
			assertThat(request.getProcessedAt()).isNull();
			assertThat(request.getRejectReason()).isNull();
		}

		// 검증: FR-USER-16
		@Test
		@DisplayName("처리된 요청에는 처리 시각이 반드시 있다")
		void 처리된_요청에는_처리_시각이_반드시_있다() {
			long requestId = 접수한_아이디_변경_요청();

			assertThatThrownBy(() -> jdbcTemplate.update(
				"UPDATE org_email_change_requests SET status = 'APPROVED' WHERE id = ?", requestId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("chk_org_email_change_processed");
		}

		// 검증: FR-USER-16
		@Test
		@DisplayName("반려 사유 없는 반려 행은 저장되지 않는다")
		void 반려_사유_없는_반려_행은_저장되지_않는다() {
			long requestId = 접수한_아이디_변경_요청();

			assertThatThrownBy(() -> jdbcTemplate.update(
				"UPDATE org_email_change_requests SET status = 'REJECTED', processed_at = ? WHERE id = ?",
				now(), requestId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("chk_org_email_change_reject_reason");
		}

		// 검증: FR-USER-16
		@Test
		@DisplayName("승인 행에는 반려 사유가 붙지 않는다")
		void 승인_행에는_반려_사유가_붙지_않는다() {
			long requestId = 접수한_아이디_변경_요청();

			assertThatThrownBy(() -> jdbcTemplate.update("""
				UPDATE org_email_change_requests
				SET status = 'APPROVED', processed_at = ?, reject_reason = '사유'
				WHERE id = ?
				""", now(), requestId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("chk_org_email_change_reject_reason");
		}
	}
}
