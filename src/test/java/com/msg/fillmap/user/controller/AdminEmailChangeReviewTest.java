package com.msg.fillmap.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.global.mail.MailSender;
import com.msg.fillmap.user.entity.OrgEmailChangeRequest;
import com.msg.fillmap.user.entity.OrgEmailChangeStatus;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.repository.OrgEmailChangeRequestRepository;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 아이디 변경 요청 심사 (MSG-500 §API 7, 실 DB). 검증의 핵심은 <b>검토 시점 가드</b>다 — 접수가 같은 대기
 * 행을 제자리 갱신하므로(재요청), 상태만 보고 처리하면 관리자가 본 적 없는 이메일을 승인한다. 그 성질은
 * UPSERT·부분 유니크·조건부 UPDATE 가 함께 만드는 것이라 실제 DB 없이는 재현되지 않는다.
 *
 * <p>{@code @Transactional} 롤백 격리로 계정·요청을 남기지 않는다. 동시 승인 경합과 컬럼 보존은 진짜 커밋이
 * 필요해 {@code EmailChangeApprovalConcurrencyTest} 가 따로 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("아이디 변경 요청 심사 (MSG-500, 실 DB)")
class AdminEmailChangeReviewTest {

	private static final String QUEUE_URL = "/api/admin/email-change-requests";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private OrgEmailChangeRequestRepository requestRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private TokenProvider tokenProvider;

	@Autowired
	private EntityManager entityManager;

	@MockitoBean
	private MailSender mailSender;

	private User organizer;
	private User admin;
	private String requestedEmail;
	private long requestId;

	@BeforeEach
	void setUp() {
		organizer = saveUser(UserRole.ORG);
		admin = saveUser(UserRole.ADMIN);
		requestedEmail = "changed-" + UUID.randomUUID() + "@fillmap.dev";
		requestId = 접수한다(requestedEmail);
	}

	private User saveUser(UserRole role) {
		User user = User.createLocalUser("before-" + UUID.randomUUID() + "@fillmap.dev",
			passwordEncoder.encode("Initial1234"), "김담당");
		ReflectionTestUtils.setField(user, "role", role);
		ReflectionTestUtils.setField(user, "orgName", "부산광역시 부산진구청");
		return userRepository.saveAndFlush(user);
	}

	/** 행사 운영자 접수 경로(MSG-497)를 그대로 탄다 — 재접수가 같은 행을 덮어쓰는 성질이 이 테스트의 전제다. */
	private long 접수한다(String email) {
		entityManager.flush();
		requestRepository.upsertPending(organizer.getId(), email,
			LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
		entityManager.clear();
		return 저장된_요청().getId();
	}

	private OrgEmailChangeRequest 저장된_요청() {
		entityManager.flush();
		entityManager.clear();
		return requestRepository.findAllByUserId(organizer.getId()).stream()
			.max((left, right) -> left.getId().compareTo(right.getId()))
			.orElseThrow();
	}

	private String bearer(User user) {
		return "Bearer " + tokenProvider.issueAccessToken(user.getId(), user.getRole());
	}

	/** 저장된 접수 시각을 그대로 되돌려 보내는 형태 — 이 값이 검토 기준 시각이다(UTC 표기). */
	private String reviewedAt() {
		return 저장된_요청().getCreatedAt() + "Z";
	}

	private ResultActions 승인한다(long targetRequestId, String requestedAt) throws Exception {
		entityManager.flush();
		return mockMvc.perform(post(QUEUE_URL + "/" + targetRequestId + "/approve")
			.header(HttpHeaders.AUTHORIZATION, bearer(admin))
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"requestedAt": "%s"}""".formatted(requestedAt)));
	}

	private ResultActions 승인한다() throws Exception {
		return 승인한다(requestId, reviewedAt());
	}

	private ResultActions 반려한다(long targetRequestId, String requestedAt, String reason) throws Exception {
		entityManager.flush();
		return mockMvc.perform(post(QUEUE_URL + "/" + targetRequestId + "/reject")
			.header(HttpHeaders.AUTHORIZATION, bearer(admin))
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"requestedAt": "%s", "reason": "%s"}""".formatted(requestedAt, reason)));
	}

	private User 저장된_계정() {
		entityManager.flush();
		entityManager.clear();
		return userRepository.findById(organizer.getId()).orElseThrow();
	}

	@Nested
	@DisplayName("승인")
	class Approve {

		// 검증: FR-USER-16
		@Test
		@DisplayName("승인하면 요청이 승인됨이 되고 로그인 이메일이 요청값으로 바뀐다")
		void 승인하면_요청이_승인됨이_되고_로그인_이메일이_요청값으로_바뀐다() throws Exception {
			승인한다()
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.email").value(requestedEmail))
				.andExpect(jsonPath("$.data.emailSent").value(true));

			// 두 갱신이 한 트랜잭션이다 — 요청만 승인되고 이메일이 그대로인 절반이 남지 않는다.
			OrgEmailChangeRequest processed = 저장된_요청();
			assertThat(processed.getStatus()).isEqualTo(OrgEmailChangeStatus.APPROVED);
			assertThat(processed.getProcessedAt()).isNotNull();
			assertThat(processed.getRejectReason()).isNull();
			assertThat(저장된_계정().getEmail()).isEqualTo(requestedEmail);
		}

		// 검증: FR-USER-16
		@Test
		@DisplayName("승인 통지는 새 이메일로 가고 발송이 실패해도 승인은 유지된다")
		void 승인_통지_메일이_새_이메일로_발송되고_실패해도_승인은_유지된다() throws Exception {
			승인한다().andExpect(status().isOk());

			ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
			// 옛 주소로 보내면 계정 접근을 잃은 사람에게 안내가 가지 않는다 — 수신자가 새 이메일이어야 한다.
			then(mailSender).should().send(eq(requestedEmail), anyString(), body.capture());
			assertThat(body.getValue()).contains(requestedEmail);
		}

		// 검증: FR-USER-16
		@Test
		@DisplayName("발송이 실패해도 이메일 교체는 커밋된 채 남고 emailSent 가 거짓이다")
		void 발송이_실패해도_승인은_유지된다() throws Exception {
			willThrow(new IllegalStateException("SES 장애")).given(mailSender)
				.send(anyString(), anyString(), anyString());

			승인한다()
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.emailSent").value(false));

			assertThat(저장된_계정().getEmail()).isEqualTo(requestedEmail);
		}

		// 검증: FR-USER-16
		@Test
		@DisplayName("요청 이메일이 이미 다른 계정에 있으면 1409 이고 아이디는 그대로다")
		void 요청_이메일이_이미_다른_계정에_있으면_1409로_거부된다() throws Exception {
			String taken = "taken-" + UUID.randomUUID() + "@fillmap.dev";
			User other = saveUser(UserRole.ORG);
			ReflectionTestUtils.setField(other, "email", taken);
			userRepository.saveAndFlush(other);
			long conflictRequestId = 접수한다(taken);

			승인한다(conflictRequestId, reviewedAt())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.developCode").value(1409));

			assertThat(저장된_계정().getEmail()).isNotEqualTo(taken);
			assertThat(저장된_요청().getStatus()).isEqualTo(OrgEmailChangeStatus.PENDING);
		}

		// 검증: FR-USER-16
		@Test
		@DisplayName("이미 처리된 요청의 재처리는 1428 이다")
		void 이미_처리된_요청의_재처리는_1428이다() throws Exception {
			String reviewedAt = reviewedAt();
			승인한다(requestId, reviewedAt).andExpect(status().isOk());

			승인한다(requestId, reviewedAt)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.developCode").value(1428));
			반려한다(requestId, reviewedAt, "뒤늦은 반려")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.developCode").value(1428));
		}

		// 검증: FR-USER-16
		@Test
		@DisplayName("없는 요청은 1427 이다")
		void 없는_요청은_1427이다() throws Exception {
			승인한다(99_999_999L, reviewedAt())
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.developCode").value(1427));
		}
	}

	@Nested
	@DisplayName("검토 시점 가드")
	class ReviewedAtGuard {

		// 검증: FR-USER-16
		@Test
		@DisplayName("검토 후 재접수된 요청의 승인과 반려는 1429 로 거부된다")
		void 검토_후_재제출된_요청의_승인과_반려는_1429로_거부된다() throws Exception {
			String 검토한_시각 = reviewedAt();
			// 관리자가 큐를 띄운 뒤 행사 운영자가 다른 이메일로 다시 접수한다 — 같은 행이 제자리 갱신된다.
			String 새_요청_이메일 = "resubmitted-" + UUID.randomUUID() + "@fillmap.dev";
			접수한다(새_요청_이메일);

			승인한다(requestId, 검토한_시각)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.developCode").value(1429));
			반려한다(requestId, 검토한_시각, "낡은 사유")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.developCode").value(1429));

			// 본 적 없는 이메일이 승인되지 않았다 — 가드가 없으면 여기서 바뀐다.
			assertThat(저장된_계정().getEmail()).isNotEqualTo(새_요청_이메일);
			assertThat(저장된_요청().getStatus()).isEqualTo(OrgEmailChangeStatus.PENDING);
		}

		// 검증: FR-USER-16
		@Test
		@DisplayName("다시 읽은 접수 시각으로는 승인된다 — 가드는 재검토를 요구할 뿐 막지 않는다")
		void 다시_읽은_접수_시각으로는_승인된다() throws Exception {
			String 새_요청_이메일 = "resubmitted-" + UUID.randomUUID() + "@fillmap.dev";
			접수한다(새_요청_이메일);

			승인한다(requestId, reviewedAt()).andExpect(status().isOk());

			assertThat(저장된_계정().getEmail()).isEqualTo(새_요청_이메일);
		}
	}

	@Nested
	@DisplayName("반려")
	class Reject {

		// 검증: FR-USER-16
		@Test
		@DisplayName("반려는 사유가 저장되고 이메일은 바뀌지 않으며 메일도 나가지 않는다")
		void 반려는_사유가_저장되고_이메일은_바뀌지_않는다() throws Exception {
			String before = 저장된_계정().getEmail();

			반려한다(requestId, reviewedAt(), "기관 도메인이 아닌 이메일이라 반려합니다")
				.andExpect(status().isOk());

			OrgEmailChangeRequest rejected = 저장된_요청();
			assertThat(rejected.getStatus()).isEqualTo(OrgEmailChangeStatus.REJECTED);
			assertThat(rejected.getRejectReason()).isEqualTo("기관 도메인이 아닌 이메일이라 반려합니다");
			assertThat(rejected.getProcessedAt()).isNotNull();
			assertThat(저장된_계정().getEmail()).isEqualTo(before);
			// 반려 통보는 수기다 — 저장된 사유가 그 재료이고 자동 발송은 없다.
			then(mailSender).should(never()).send(anyString(), anyString(), anyString());
		}

		// 검증: FR-USER-16
		@Test
		@DisplayName("처리 후 같은 계정이 다시 접수할 수 있다 — 부분 유니크가 풀린다")
		void 처리_후_같은_계정이_다시_접수할_수_있다() throws Exception {
			반려한다(requestId, reviewedAt(), "사유").andExpect(status().isOk());

			long 재접수 = 접수한다("again-" + UUID.randomUUID() + "@fillmap.dev");

			// 새 행이 생긴다 — 처리된 행은 PENDING 이 아니라 부분 유니크 인덱스에 걸리지 않는다.
			assertThat(재접수).isNotEqualTo(requestId);
			assertThat(requestRepository.findAllByUserId(organizer.getId())).hasSize(2);
		}

		// 검증: FR-USER-16
		@Test
		@DisplayName("사유가 비면 공통 400 이다")
		void 사유가_비면_공통_400이다() throws Exception {
			반려한다(requestId, reviewedAt(), " ")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(400));
		}
	}

	@Nested
	@DisplayName("요청 큐")
	class Queue {

		// 검증: FR-USER-16
		@Test
		@DisplayName("큐 항목은 현재 아이디와 바꾸려는 이메일을 나란히 준다")
		void 큐_항목은_현재_아이디와_요청_이메일을_함께_준다() throws Exception {
			mockMvc.perform(get(QUEUE_URL + "?size=100").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.requests[?(@.id == %d)].email".formatted(requestId))
					.value(organizer.getEmail()))
				.andExpect(jsonPath("$.data.requests[?(@.id == %d)].requestedEmail".formatted(requestId))
					.value(requestedEmail))
				.andExpect(jsonPath("$.data.requests[?(@.id == %d)].orgName".formatted(requestId))
					.value("부산광역시 부산진구청"))
				.andExpect(jsonPath("$.data.requests[?(@.id == %d)].status".formatted(requestId))
					.value("PENDING"));
		}

		// 검증: FR-USER-16
		@Test
		@DisplayName("건수 3종은 상태 필터와 무관한 전체 집계다")
		void 건수는_상태_필터와_무관한_전체_집계다() throws Exception {
			long 처음_승인 = 건수("approvedCount");

			승인한다().andExpect(status().isOk());

			// 필터가 대기여도 승인 건수가 함께 늘어난다 — 탭 뱃지 재료라 필터를 타지 않는다.
			assertThat(건수("approvedCount")).isEqualTo(처음_승인 + 1);
		}

		// 검증: FR-USER-16
		@Test
		@DisplayName("파라미터 거절은 발급 요청 큐와 같은 코드다 — 1424·1425")
		void 파라미터_거절은_발급_요청_큐와_같은_코드다() throws Exception {
			mockMvc.perform(get(QUEUE_URL + "?status=UNKNOWN").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(1424));
			mockMvc.perform(get(QUEUE_URL + "?size=101").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(1425));
		}

		// 검증: FR-USER-16
		@Test
		@DisplayName("관리자가 아니면 요청 큐에 접근할 수 없다")
		void 관리자가_아니면_요청_큐에_접근할_수_없다() throws Exception {
			mockMvc.perform(get(QUEUE_URL).header(HttpHeaders.AUTHORIZATION, bearer(organizer)))
				.andExpect(status().isForbidden());
			mockMvc.perform(get(QUEUE_URL)).andExpect(status().isUnauthorized());
		}

		private long 건수(String field) throws Exception {
			entityManager.flush();
			entityManager.clear();
			String response = mockMvc.perform(get(QUEUE_URL).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
			return ((Number) com.jayway.jsonpath.JsonPath.read(response, "$.data." + field)).longValue();
		}
	}
}
