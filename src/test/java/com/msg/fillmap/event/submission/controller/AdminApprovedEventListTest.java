package com.msg.fillmap.event.submission.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.event.repository.EventLocationGridRepository;
import com.msg.fillmap.event.repository.EventLocationRepository;
import com.msg.fillmap.event.repository.EventOccurrenceRepository;
import com.msg.fillmap.event.submission.dto.AdminApprovedEventItemResponseDto;
import com.msg.fillmap.event.submission.dto.AdminApprovedEventListResponseDto;
import com.msg.fillmap.event.submission.repository.EventSubmissionRepository;
import com.msg.fillmap.event.submission.service.AdminApprovedEventService;
import com.msg.fillmap.global.mail.MailSender;
import com.msg.fillmap.mission.service.MissionRegistrationService;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 승인 행사 목록의 파생 탭 (MSG-500 §API 5, 실 DB). 탭이 <b>저장값이 아니라 KST 오늘과 기간의 파생</b>이라,
 * 검증하려면 "오늘"을 고정해야 한다 — 그래서 이 클래스는 고정 클럭을 꽂은 서비스를 직접 만들어 쓰고,
 * 컨트롤러 경로(파라미터 거절·인가)만 실제 빈으로 확인한다.
 *
 * <p>고정 시각이 {@code 2026-11-07T15:30Z}(= KST 11-08 00:30)인 것이 이 파일의 핵심이다. UTC 날짜로
 * 판정하면 오늘이 11-07 이 되어 아래 네 행의 탭이 통째로 한 칸씩 밀린다 — 그 어긋남을 잡는 것이 목적이다.
 *
 * <p>건수 단언은 증분이다: 탭 건수가 전역 집계라 공유 로컬 DB 의 기존 승인 행이 그대로 더해진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("승인 행사 목록 파생 탭 (MSG-500, 실 DB)")
class AdminApprovedEventListTest {

	private static final String URL = "/api/admin/events";

	/** UTC 2026-11-07T15:30Z = KST 2026-11-08T00:30 — 두 시간대의 날짜가 갈리는 순간이다. */
	private static final Clock KST_NEW_DAY = Clock.fixed(Instant.parse("2026-11-07T15:30:00Z"), ZoneOffset.UTC);
	private static final LocalDate KST_TODAY = LocalDate.of(2026, 11, 8);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private EventSubmissionRepository submissionRepository;

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
	private TokenProvider tokenProvider;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@Autowired
	private EntityManager entityManager;

	private AdminApprovedEventService service;
	private User organizer;
	private User admin;

	@BeforeEach
	void setUp() {
		organizer = saveUser(UserRole.ORG);
		admin = saveUser(UserRole.ADMIN);
		// 목록은 미션도 메일도 건드리지 않는다 — 중지 경로 의존이라 목으로 채운다.
		service = new AdminApprovedEventService(submissionRepository, mock(MissionRegistrationService.class),
			userRepository, mock(MailSender.class), transactionTemplate, occurrenceRepository, locationRepository,
			locationGridRepository, KST_NEW_DAY);
	}

	private User saveUser(UserRole role) {
		User user = User.createLocalUser("tab-" + UUID.randomUUID() + "@fillmap.dev",
			passwordEncoder.encode("Initial1234"), "김담당");
		ReflectionTestUtils.setField(user, "role", role);
		ReflectionTestUtils.setField(user, "orgName", "부산광역시 부산진구청");
		return userRepository.saveAndFlush(user);
	}

	/** 승인된 신청 1건 — 승인 번호는 CHECK 가 요구하므로 상태와 함께 넣는다. */
	private long 승인된_행사를_심는다(LocalDate startsOn, LocalDate endsOn) {
		String submissionNo = "FM-2026-" + UUID.randomUUID().toString().substring(0, 8);
		LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
		jdbcTemplate.update("""
			INSERT INTO event_submissions
				(submission_no, user_id, type, status, approval_no, title, organizer_name, starts_on, ends_on,
				 program_description, description, image_key, created_at, updated_at)
			VALUES (?, ?, 'FESTIVAL', 'APPROVED', ?, '광안리 불꽃축제', '부산문화관광축제조직위원회', ?, ?,
				'멀티불꽃쇼', '광안리 일원에서 열리는 부산 대표 불꽃 축제',
				'event-submissions/original/1/a.jpg', ?, ?)
			""", submissionNo, organizer.getId(), "APR-2026-" + UUID.randomUUID().toString().substring(0, 8),
			startsOn, endsOn, now, now);
		return jdbcTemplate.queryForObject(
			"SELECT id FROM event_submissions WHERE submission_no = ?", Long.class, submissionNo);
	}

	private void 중지_기록을_남긴다(long submissionId, String reason) {
		jdbcTemplate.update(
			"UPDATE event_submissions SET unpublished_at = ?, unpublish_reason = ? WHERE id = ?",
			LocalDateTime.now(ZoneOffset.UTC), reason, submissionId);
	}

	private AdminApprovedEventListResponseDto 목록(String tab) {
		entityManager.flush();
		entityManager.clear();
		return service.getEvents(tab, 0, 100);
	}

	@Nested
	@DisplayName("파생 탭")
	class DerivedTab {

		// 검증: FR-EVENT-19
		@Test
		@DisplayName("탭 상태는 KST 오늘과 기간으로 파생되고 경계일은 양끝 포함이다")
		void 승인_행사_목록의_탭_상태는_KST_오늘과_기간으로_파생된다() {
			long 시작일_당일 = 승인된_행사를_심는다(KST_TODAY, KST_TODAY.plusDays(2));
			long 종료일_당일 = 승인된_행사를_심는다(KST_TODAY.minusDays(3), KST_TODAY);
			long 예정 = 승인된_행사를_심는다(KST_TODAY.plusDays(1), KST_TODAY.plusDays(2));
			long 종료 = 승인된_행사를_심는다(KST_TODAY.minusDays(3), KST_TODAY.minusDays(1));

			// 시작일 당일도 종료일 당일도 노출 중이다 — UTC 오늘(11-07)로 판정하면 둘 다 다른 탭으로 밀린다.
			assertThat(목록("EXPOSED").events()).extracting(AdminApprovedEventItemResponseDto::submissionId)
				.contains(시작일_당일, 종료일_당일)
				.doesNotContain(예정, 종료);
			assertThat(목록("UPCOMING").events()).extracting(AdminApprovedEventItemResponseDto::submissionId)
				.contains(예정)
				.doesNotContain(시작일_당일, 종료일_당일, 종료);
			assertThat(목록("ENDED").events()).extracting(AdminApprovedEventItemResponseDto::submissionId)
				.contains(종료)
				.doesNotContain(시작일_당일, 종료일_당일, 예정);
			// 항목의 status 도 같은 식에서 나온다 — 필터와 표시가 갈리면 탭에 다른 상태가 섞여 보인다.
			assertThat(목록("EXPOSED").events()).allMatch(event -> "EXPOSED".equals(event.status()));
		}

		// 검증: FR-EVENT-19
		@Test
		@DisplayName("탭 건수 3종은 탭 필터와 무관한 전체 집계다")
		void 탭_건수는_필터와_무관한_전체_집계다() {
			AdminApprovedEventListResponseDto 처음 = 목록("EXPOSED");

			승인된_행사를_심는다(KST_TODAY, KST_TODAY);
			승인된_행사를_심는다(KST_TODAY.plusDays(1), KST_TODAY.plusDays(2));
			승인된_행사를_심는다(KST_TODAY.minusDays(3), KST_TODAY.minusDays(1));

			AdminApprovedEventListResponseDto 나중 = 목록("EXPOSED");
			assertThat(나중.exposedCount()).isEqualTo(처음.exposedCount() + 1);
			assertThat(나중.upcomingCount()).isEqualTo(처음.upcomingCount() + 1);
			assertThat(나중.endedCount()).isEqualTo(처음.endedCount() + 1);
		}

		// 검증: FR-EVENT-19
		@Test
		@DisplayName("중지된 행사는 파생 탭에 남고 unpublished 가 참이다")
		void 중지된_행사는_파생_탭에_남고_unpublished가_참이다() {
			long 중지됨 = 승인된_행사를_심는다(KST_TODAY.minusDays(1), KST_TODAY.plusDays(1));
			중지_기록을_남긴다(중지됨, "행사가 취소되어 노출을 중지합니다");

			assertThat(목록("EXPOSED").events())
				.filteredOn(event -> event.submissionId().equals(중지됨))
				.singleElement()
				.satisfies(event -> {
					assertThat(event.unpublished()).isTrue();
					assertThat(event.unpublishedAt()).isNotNull();
					assertThat(event.unpublishReason()).isEqualTo("행사가 취소되어 노출을 중지합니다");
					assertThat(event.orgName()).isEqualTo("부산광역시 부산진구청");
					assertThat(event.approvalNo()).startsWith("APR-2026-");
				});
		}
	}

	@Nested
	@DisplayName("파라미터와 인가")
	class Guards {

		// 검증: FR-EVENT-19
		@Test
		@DisplayName("지원하지 않는 탭은 13455, 페이지 범위 밖은 13456 이다")
		void 파라미터_거절은_심사_큐와_같은_코드다() throws Exception {
			mockMvc.perform(get(URL + "?status=UNKNOWN").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(13455));
			mockMvc.perform(get(URL + "?size=101").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(13456));
		}

		// 검증: FR-EVENT-19
		@Test
		@DisplayName("관리자가 아니면 승인 행사 목록에 접근할 수 없다")
		void 관리자가_아니면_접근할_수_없다() throws Exception {
			mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer(organizer)))
				.andExpect(status().isForbidden());
			mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
		}

		// 검증: FR-EVENT-19
		@Test
		@DisplayName("기본 탭은 노출 중이다")
		void 기본_탭은_노출_중이다() throws Exception {
			mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.events[?(@.status != 'EXPOSED')]").doesNotExist());
		}

		private String bearer(User user) {
			return "Bearer " + tokenProvider.issueAccessToken(user.getId(), user.getRole());
		}
	}
}
