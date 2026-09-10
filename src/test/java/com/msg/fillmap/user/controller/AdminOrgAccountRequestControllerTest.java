package com.msg.fillmap.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;

import com.jayway.jsonpath.JsonPath;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.global.mail.MailSender;
import com.msg.fillmap.user.entity.AuthProvider;
import com.msg.fillmap.user.entity.OrgAccountRequest;
import com.msg.fillmap.user.entity.OrgAccountRequestStatus;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.repository.OrgAccountRequestRepository;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 관리자 계정 발급 요청 큐와 심사 (MSG-499 API 2~5, 실 DB). 검증 대상이 행 잠금·검토 시점 대조·계정
 * 생성·커밋 후 발송까지 걸쳐 있어 목으로는 잡히지 않는다 — 메일 발송만 목으로 갈아 끼워 수신자와
 * 본문을 단언하고, 초기 비밀번호 평문이 응답·로그 어디에도 없음을 로그 캡처로 확인한다.
 *
 * <p><b>클래스 수준 {@code @Transactional} 을 쓰지 않는다</b> (Codex 1R 지적). 롤백 격리를 걸면 서비스의
 * TransactionTemplate 이 테스트 트랜잭션에 합류해 커밋이 한 번도 일어나지 않고, 그러면 이 티켓의 계약인
 * "발송은 커밋 뒤"와 "발송이 실패해도 발급은 커밋된 채 남는다"가 검증되지 않은 채 통과한다. 대신 만든
 * 행을 {@link #정리한다()} 가 직접 지워 공유 로컬 DB 를 되돌린다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("관리자 계정 발급 요청 심사 (MSG-499, 실 DB)")
class AdminOrgAccountRequestControllerTest {

	private static final String QUEUE_URL = "/api/admin/org-account-requests";
	private static final String PASSWORD_LABEL = "초기 비밀번호: ";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private OrgAccountRequestRepository requestRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private TokenProvider tokenProvider;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private TransactionTemplate transactionTemplate;

	/** 발송을 갈아 끼워 수신자·본문을 잡고 실패도 주입한다 — 평문의 유일한 정당한 출구가 여기다. */
	@MockitoBean
	private MailSender mailSender;

	/** 발송 시점에 트랜잭션이 살아 있었는지 — 커밋 후 발송 계약의 직접 증거다 (미호출이면 null). */
	private Boolean 발송_시점_트랜잭션_활성;
	private final List<String> 정리할_이메일 = new ArrayList<>();
	private String adminToken;
	private ListAppender<ILoggingEvent> logs;

	@BeforeEach
	void setUp() {
		발송_시점_트랜잭션_활성 = null;
		willAnswer(invocation -> {
			발송_시점_트랜잭션_활성 = TransactionSynchronizationManager.isActualTransactionActive();
			return null;
		}).given(mailSender).send(any(), any(), any());
		// 반려 안내 메일(MSG-575)은 HTML 동봉 오버로드로 나간다 — 같은 트랜잭션 증거를 남긴다.
		willAnswer(invocation -> {
			발송_시점_트랜잭션_활성 = TransactionSynchronizationManager.isActualTransactionActive();
			return null;
		}).given(mailSender).send(any(), any(), any(), any());

		User admin = saveUser(uniqueEmail(), UserRole.ADMIN);
		adminToken = "Bearer " + tokenProvider.issueAccessToken(admin.getId(), admin.getRole());
		// 루트 로거에 붙여 서비스 계층 전량을 잡는다 — 특정 클래스만 보면 다른 빈이 흘린 평문을 놓친다.
		logs = new ListAppender<>();
		logs.start();
		rootLogger().addAppender(logs);
	}

	@AfterEach
	void tearDown() {
		rootLogger().detachAppender(logs);
		logs.stop();
		정리한다();
	}

	/** 커밋된 행을 직접 지운다 — 요청이 먼저다(issued_user_id FK 가 살아 있으면 계정 삭제가 막힌다). */
	private void 정리한다() {
		if (정리할_이메일.isEmpty()) {
			return;
		}
		transactionTemplate.executeWithoutResult(status -> {
			entityManager.createQuery("DELETE FROM OrgAccountRequest r WHERE r.email IN :emails")
				.setParameter("emails", 정리할_이메일)
				.executeUpdate();
			entityManager.createQuery("DELETE FROM User u WHERE u.email IN :emails")
				.setParameter("emails", 정리할_이메일)
				.executeUpdate();
		});
		정리할_이메일.clear();
	}

	private Logger rootLogger() {
		return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
	}

	/** 이 클래스의 모든 이메일이 이 문을 거친다 — 여기서 등록해야 정리에서 빠지는 행이 없다. */
	private String uniqueEmail() {
		String email = "msg499-req-" + UUID.randomUUID() + "@fillmap.dev";
		정리할_이메일.add(email);
		return email;
	}

	private User saveUser(String email, UserRole role) {
		return transactionTemplate.execute(status -> {
			User user = User.createLocalUser(email, passwordEncoder.encode("Initial1234"), "담당자");
			ReflectionTestUtils.setField(user, "role", role);
			return userRepository.saveAndFlush(user);
		});
	}

	private LocalDateTime now() {
		return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
	}

	private OrgAccountRequest 접수한다(String email, LocalDateTime at) {
		transactionTemplate.executeWithoutResult(status -> requestRepository.upsertPending("부산진구청", "김담당",
			"010-1234-5678", email, "서면 겨울 축제", "계정을 신청합니다", at));
		return 이메일로_찾는다(email).getFirst();
	}

	/** 커밋된 상태를 별도 트랜잭션에서 다시 읽는다 — 테스트가 서비스와 컨텍스트를 공유하지 않는다. */
	private List<OrgAccountRequest> 이메일로_찾는다(String email) {
		return transactionTemplate.execute(status -> entityManager
			.createQuery("SELECT r FROM OrgAccountRequest r WHERE r.email = :email ORDER BY r.id DESC",
				OrgAccountRequest.class)
			.setParameter("email", email)
			.getResultList());
	}

	private void 반려_상태로_만든다(Long requestId, String reason) {
		transactionTemplate.executeWithoutResult(status ->
			requestRepository.findById(requestId).orElseThrow().reject(reason, now()));
	}

	/** 상세 응답의 updatedAt 문자열 — 승인·반려가 그대로 에코해야 하는 값이라 API 왕복으로 얻는다. */
	private String 검토_기준_시각(Long requestId) throws Exception {
		String body = mockMvc.perform(get(QUEUE_URL + "/" + requestId).header(HttpHeaders.AUTHORIZATION, adminToken))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.data.updatedAt");
	}

	private String 발송된_초기_비밀번호() {
		ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
		then(mailSender).should().send(any(), any(), bodyCaptor.capture());
		return 본문에서_비밀번호를_뽑는다(bodyCaptor.getValue());
	}

	private static String 본문에서_비밀번호를_뽑는다(String body) {
		int begin = body.indexOf(PASSWORD_LABEL) + PASSWORD_LABEL.length();
		return body.substring(begin, body.indexOf('\n', begin));
	}

	/**
	 * 로그 한 건의 전체 렌더링 — 포맷된 메시지와 예외(원인 체인 포함) 양쪽이다. 메시지만 보면 예외에
	 * 실려 온 평문이 스택 트레이스로 새는 경로를 놓친다 (Codex 1R 지적).
	 */
	private String 렌더링한다(ILoggingEvent event) {
		String rendered = event.getFormattedMessage();
		return event.getThrowableProxy() == null
			? rendered
			: rendered + '\n' + ThrowableProxyUtil.asString(event.getThrowableProxy());
	}

	private void 로그에_평문이_없다(String plainPassword) {
		assertThat(logs.list)
			.extracting(this::렌더링한다)
			.noneMatch(rendered -> rendered.contains(plainPassword));
	}

	private void 발송은_커밋_뒤였다() {
		assertThat(발송_시점_트랜잭션_활성)
			.as("발송이 호출되지 않았거나 트랜잭션 안에서 일어났다")
			.isFalse();
	}

	/** 본문에서 평문을 뽑아 예외 메시지를 만드는 발송 실패 스텁 — 발송 시점의 트랜잭션 상태도 함께 기록한다. */
	private void 발송을_실패시킨다(UnaryOperator<String> 예외_메시지) {
		willAnswer(invocation -> {
			발송_시점_트랜잭션_활성 = TransactionSynchronizationManager.isActualTransactionActive();
			throw new IllegalStateException(예외_메시지.apply(본문에서_비밀번호를_뽑는다(invocation.getArgument(2))));
		}).given(mailSender).send(any(), any(), any());
	}

	@Nested
	@DisplayName("요청 큐와 상세")
	class Queue {

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("요청 큐가 상태별 건수와 함께 마지막 접수 최신순으로 조회된다")
		void 요청_큐가_상태별_건수와_함께_마지막_접수_최신순으로_조회된다() throws Exception {
			LocalDateTime base = now();
			접수한다(uniqueEmail(), base);
			String newer = uniqueEmail();
			접수한다(newer, base.plusMinutes(1));

			String body = mockMvc.perform(get(QUEUE_URL)
					.header(HttpHeaders.AUTHORIZATION, adminToken)
					.param("size", "100"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.requests[0].email").value(newer))
				.andExpect(jsonPath("$.data.requests[0].status").value("PENDING"))
				.andReturn().getResponse().getContentAsString();

			Number pendingCount = JsonPath.read(body, "$.data.pendingCount");
			Number issuedCount = JsonPath.read(body, "$.data.issuedCount");
			Number rejectedCount = JsonPath.read(body, "$.data.rejectedCount");
			assertThat(pendingCount.longValue()).isGreaterThanOrEqualTo(2);
			assertThat(issuedCount.longValue()).isNotNegative();
			assertThat(rejectedCount.longValue()).isNotNegative();
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("지원하지 않는 상태 필터는 1424 다")
		void 지원하지_않는_상태_필터는_1424다() throws Exception {
			mockMvc.perform(get(QUEUE_URL).header(HttpHeaders.AUTHORIZATION, adminToken).param("status", "UNKNOWN"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(1424));
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("페이지 범위 밖은 1425 다")
		void 페이지_범위_밖은_1425다() throws Exception {
			for (String[] params : new String[][] {{"page", "-1"}, {"size", "0"}, {"size", "101"}}) {
				mockMvc.perform(get(QUEUE_URL)
						.header(HttpHeaders.AUTHORIZATION, adminToken)
						.param(params[0], params[1]))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.developCode").value(1425));
			}
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("없는 요청 상세는 1421 이다")
		void 없는_요청_상세는_1421이다() throws Exception {
			mockMvc.perform(get(QUEUE_URL + "/99999999").header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.developCode").value(1421));
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("반려된 요청 상세에 반려 사유가 실린다")
		void 반려된_요청_상세에_반려_사유가_실린다() throws Exception {
			OrgAccountRequest request = 접수한다(uniqueEmail(), now());
			반려_상태로_만든다(request.getId(), "기관 확인 서류 누락");

			mockMvc.perform(get(QUEUE_URL + "/" + request.getId()).header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("REJECTED"))
				.andExpect(jsonPath("$.data.rejectReason").value("기관 확인 서류 누락"))
				.andExpect(jsonPath("$.data.contactPhone").value("010-1234-5678"))
				.andExpect(jsonPath("$.data.content").value("계정을 신청합니다"));
		}
	}

	@Nested
	@DisplayName("승인")
	class Approve {

		private String approveBody(String updatedAt) {
			return """
				{"updatedAt": "%s"}""".formatted(updatedAt);
		}

		private void 승인한다(Long requestId, String updatedAt, int expectedStatus) throws Exception {
			mockMvc.perform(post(QUEUE_URL + "/" + requestId + "/approve")
					.header(HttpHeaders.AUTHORIZATION, adminToken)
					.contentType(MediaType.APPLICATION_JSON)
					.content(approveBody(updatedAt)))
				.andExpect(status().is(expectedStatus));
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("승인하면 ORG·LOCAL 계정이 초기 비밀번호 변경 강제 상태로 생성된다")
		void 승인하면_ORG_LOCAL_계정이_초기_비밀번호_변경_강제_상태로_생성된다() throws Exception {
			String email = uniqueEmail();
			OrgAccountRequest request = 접수한다(email, now());

			String body = mockMvc.perform(post(QUEUE_URL + "/" + request.getId() + "/approve")
					.header(HttpHeaders.AUTHORIZATION, adminToken)
					.contentType(MediaType.APPLICATION_JSON)
					.content(approveBody(검토_기준_시각(request.getId()))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.emailSent").value(true))
				.andReturn().getResponse().getContentAsString();

			발송은_커밋_뒤였다();
			Long userId = ((Number) JsonPath.read(body, "$.data.userId")).longValue();
			User issued = transactionTemplate.execute(status -> userRepository.findById(userId).orElseThrow());
			assertThat(issued.getEmail()).isEqualTo(email);
			assertThat(issued.getRole()).isEqualTo(UserRole.ORG);
			assertThat(issued.getProvider()).isEqualTo(AuthProvider.LOCAL);
			assertThat(issued.isPasswordMustChange()).isTrue();
			assertThat(issued.getOrgName()).isEqualTo("부산진구청");
			assertThat(issued.getNickname()).isEqualTo("김담당");
			OrgAccountRequest processed = 이메일로_찾는다(email).getFirst();
			assertThat(processed.getStatus()).isEqualTo(OrgAccountRequestStatus.ISSUED);
			assertThat(processed.getIssuedUserId()).isEqualTo(userId);
			assertThat(processed.getProcessedAt()).isNotNull();
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("승인 시 초기 비밀번호가 공식 이메일로 발송되고 응답에 평문이 없다")
		void 승인_시_초기_비밀번호가_공식_이메일로_발송되고_응답에_평문이_없다() throws Exception {
			String email = uniqueEmail();
			OrgAccountRequest request = 접수한다(email, now());

			String body = mockMvc.perform(post(QUEUE_URL + "/" + request.getId() + "/approve")
					.header(HttpHeaders.AUTHORIZATION, adminToken)
					.contentType(MediaType.APPLICATION_JSON)
					.content(approveBody(검토_기준_시각(request.getId()))))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

			ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
			then(mailSender).should().send(toCaptor.capture(), any(), any());
			assertThat(toCaptor.getValue()).isEqualTo(email);
			String plainPassword = 발송된_초기_비밀번호();
			assertThat(plainPassword).hasSize(16).containsPattern("[0-9]").containsPattern("[A-Za-z]");
			assertThat(body).doesNotContain(plainPassword);
			// 저장된 것은 해시뿐이다 — 평문이 그대로 들어갔으면 여기서 걸린다.
			Long userId = ((Number) JsonPath.read(body, "$.data.userId")).longValue();
			String storedHash = transactionTemplate.execute(status ->
				userRepository.findById(userId).orElseThrow().getPasswordHash());
			assertThat(storedHash).isNotEqualTo(plainPassword);
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("승인 시 서비스 계층 로그에 초기 비밀번호 평문이 남지 않는다")
		void 승인_시_서비스_계층_로그에_초기_비밀번호_평문이_남지_않는다() throws Exception {
			OrgAccountRequest request = 접수한다(uniqueEmail(), now());

			승인한다(request.getId(), 검토_기준_시각(request.getId()), 200);

			로그에_평문이_없다(발송된_초기_비밀번호());
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("메일 발송이 실패해도 계정과 발급됨 상태는 유지되고 발송 실패가 응답에 실린다")
		void 메일_발송이_실패해도_계정과_발급됨_상태는_유지되고_발송_실패가_응답에_실린다() throws Exception {
			발송을_실패시킨다(password -> "SES 접수 실패");
			String email = uniqueEmail();
			OrgAccountRequest request = 접수한다(email, now());

			String body = mockMvc.perform(post(QUEUE_URL + "/" + request.getId() + "/approve")
					.header(HttpHeaders.AUTHORIZATION, adminToken)
					.contentType(MediaType.APPLICATION_JSON)
					.content(approveBody(검토_기준_시각(request.getId()))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.emailSent").value(false))
				.andReturn().getResponse().getContentAsString();

			// 별도 트랜잭션 재조회 — 발송이 실패한 뒤에도 발급이 커밋된 채 남아 있는지가 계약이다.
			발송은_커밋_뒤였다();
			Long userId = ((Number) JsonPath.read(body, "$.data.userId")).longValue();
			Optional<User> issued = transactionTemplate.execute(status -> userRepository.findById(userId));
			assertThat(issued).isPresent();
			assertThat(이메일로_찾는다(email).getFirst().getStatus()).isEqualTo(OrgAccountRequestStatus.ISSUED);
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("발송 실패 ERROR 로그에 평문이 섞이지 않는다")
		void 발송_실패_ERROR_로그에_평문이_섞이지_않는다() throws Exception {
			발송을_실패시킨다(password -> "SES 접수 실패");
			OrgAccountRequest request = 접수한다(uniqueEmail(), now());

			승인한다(request.getId(), 검토_기준_시각(request.getId()), 200);

			assertThat(logs.list)
				.extracting(ILoggingEvent::getFormattedMessage)
				.anyMatch(message -> message.contains("초기 비밀번호 메일 발송 실패"));
			로그에_평문이_없다(발송된_초기_비밀번호());
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("발송 예외 메시지에 평문이 실려 와도 로그로 새지 않는다 — 스택 트레이스까지 검사")
		void 발송_예외에_평문이_실려도_로그로_새지_않는다() throws Exception {
			// 발송 구현이나 SDK 가 본문을 예외에 담아 올리는 최악의 경우 — 예외를 그대로 로거에 넘기면
			// 스택 트레이스를 통해 평문이 로그 파일로 나간다.
			발송을_실패시킨다(password -> "SES rejected body: " + password);
			OrgAccountRequest request = 접수한다(uniqueEmail(), now());

			승인한다(request.getId(), 검토_기준_시각(request.getId()), 200);

			assertThat(logs.list)
				.extracting(AdminOrgAccountRequestControllerTest.this::렌더링한다)
				.anyMatch(rendered -> rendered.contains("초기 비밀번호 메일 발송 실패"));
			로그에_평문이_없다(발송된_초기_비밀번호());
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("상세 조회 후 익명 재접수가 일어나면 승인은 1426 이다")
		void 상세_조회_후_익명_재접수가_일어나면_승인은_1426이다() throws Exception {
			String email = uniqueEmail();
			LocalDateTime first = now();
			OrgAccountRequest request = 접수한다(email, first);
			String reviewedAt = 검토_기준_시각(request.getId());
			접수한다(email, first.plusMinutes(3));

			mockMvc.perform(post(QUEUE_URL + "/" + request.getId() + "/approve")
					.header(HttpHeaders.AUTHORIZATION, adminToken)
					.contentType(MediaType.APPLICATION_JSON)
					.content(approveBody(reviewedAt)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.developCode").value(1426));

			then(mailSender).should(never()).send(any(), any(), any());
			assertThat(이메일로_찾는다(email).getFirst().getStatus()).isEqualTo(OrgAccountRequestStatus.PENDING);
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("이미 계정이 있는 이메일의 승인은 1409 다")
		void 이미_계정이_있는_이메일의_승인은_1409다() throws Exception {
			String email = uniqueEmail();
			saveUser(email, UserRole.USER);
			OrgAccountRequest request = 접수한다(email, now());
			String reviewedAt = 검토_기준_시각(request.getId());

			mockMvc.perform(post(QUEUE_URL + "/" + request.getId() + "/approve")
					.header(HttpHeaders.AUTHORIZATION, adminToken)
					.contentType(MediaType.APPLICATION_JSON)
					.content(approveBody(reviewedAt)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.developCode").value(1409));

			then(mailSender).should(never()).send(any(), any(), any());
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("이미 처리된 요청의 승인은 1422 다")
		void 이미_처리된_요청의_승인은_1422다() throws Exception {
			OrgAccountRequest request = 접수한다(uniqueEmail(), now());
			String reviewedAt = 검토_기준_시각(request.getId());
			반려_상태로_만든다(request.getId(), "이미 처리");

			mockMvc.perform(post(QUEUE_URL + "/" + request.getId() + "/approve")
					.header(HttpHeaders.AUTHORIZATION, adminToken)
					.contentType(MediaType.APPLICATION_JSON)
					.content(approveBody(reviewedAt)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.developCode").value(1422));
		}
	}

	@Nested
	@DisplayName("반려")
	class Reject {

		private String rejectBody(String reason, String updatedAt) {
			return """
				{"reason": "%s", "updatedAt": "%s"}""".formatted(reason, updatedAt);
		}

		private static final String REJECT_MAIL_SUBJECT = "[필맵] 행사 운영자 계정 발급 요청 반려 안내";

		private String[] 반려_메일_본문을_잡는다(String email) {
			ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
			ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
			then(mailSender).should().send(eq(email), eq(REJECT_MAIL_SUBJECT), text.capture(), html.capture());
			return new String[] {text.getValue(), html.getValue()};
		}

		// 검증: FR-AUTH-18, AC-575-01, AC-575-02, AC-575-04
		@Test
		@DisplayName("반려하면 사유가 저장되고 커밋 뒤 요청자에게 반려 사유를 담은 필맵 서식 메일이 발송된다")
		void 반려하면_요청자에게_반려_안내_메일이_발송된다() throws Exception {
			String email = uniqueEmail();
			OrgAccountRequest request = 접수한다(email, now());

			mockMvc.perform(post(QUEUE_URL + "/" + request.getId() + "/reject")
					.header(HttpHeaders.AUTHORIZATION, adminToken)
					.contentType(MediaType.APPLICATION_JSON)
					.content(rejectBody("기관 확인 서류 누락", 검토_기준_시각(request.getId()))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200))
				.andExpect(jsonPath("$.data.emailSent").value(true));

			OrgAccountRequest rejected = 이메일로_찾는다(email).getFirst();
			assertThat(rejected.getStatus()).isEqualTo(OrgAccountRequestStatus.REJECTED);
			assertThat(rejected.getRejectReason()).isEqualTo("기관 확인 서류 누락");
			assertThat(rejected.getProcessedAt()).isNotNull();

			String[] bodies = 반려_메일_본문을_잡는다(email);
			for (String body : bodies) {
				assertThat(body).contains("부산진구청", "김담당", "서면 겨울 축제", "기관 확인 서류 누락",
					"다시 요청하실 수 있습니다", "https://fillmap.kr");
			}
			assertThat(bodies[1]).contains("FillMap");
			발송은_커밋_뒤였다();
		}

		// 검증: FR-AUTH-18, AC-575-03
		@Test
		@DisplayName("반려 사유의 태그 문자는 HTML 본문에서 이스케이프되고 줄바꿈은 br 이 된다")
		void 반려_사유의_태그_문자는_HTML_본문에서_이스케이프된다() throws Exception {
			String email = uniqueEmail();
			OrgAccountRequest request = 접수한다(email, now());

			mockMvc.perform(post(QUEUE_URL + "/" + request.getId() + "/reject")
					.header(HttpHeaders.AUTHORIZATION, adminToken)
					.contentType(MediaType.APPLICATION_JSON)
					.content(rejectBody("서류 누락 <script>alert(1)</script>\\n보완 요망", 검토_기준_시각(request.getId()))))
				.andExpect(status().isOk());

			String[] bodies = 반려_메일_본문을_잡는다(email);
			// 평문은 원문 그대로, HTML 은 마크업으로 해석되지 않게 — 사유가 깨지거나 링크가 주입되는 것을 막는다.
			assertThat(bodies[0]).contains("서류 누락 <script>alert(1)</script>\n보완 요망");
			assertThat(bodies[1])
				.contains("서류 누락 &lt;script&gt;alert(1)&lt;/script&gt;<br>보완 요망")
				.doesNotContain("<script>");
		}

		// 검증: FR-AUTH-18, AC-575-05
		@Test
		@DisplayName("발송이 실패해도 반려는 유지되고 응답의 emailSent 만 false 다")
		void 메일_발송이_실패해도_반려는_유지되고_발송_실패가_응답에_실린다() throws Exception {
			String email = uniqueEmail();
			OrgAccountRequest request = 접수한다(email, now());
			willThrow(new IllegalStateException("SES unavailable")).given(mailSender).send(any(), any(), any(), any());

			mockMvc.perform(post(QUEUE_URL + "/" + request.getId() + "/reject")
					.header(HttpHeaders.AUTHORIZATION, adminToken)
					.contentType(MediaType.APPLICATION_JSON)
					.content(rejectBody("기관 확인 서류 누락", 검토_기준_시각(request.getId()))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.emailSent").value(false));

			OrgAccountRequest rejected = 이메일로_찾는다(email).getFirst();
			assertThat(rejected.getStatus()).isEqualTo(OrgAccountRequestStatus.REJECTED);
			assertThat(rejected.getRejectReason()).isEqualTo("기관 확인 서류 누락");
			assertThat(rejected.getProcessedAt()).isNotNull();
			assertThat(logs.list)
				.extracting(ILoggingEvent::getFormattedMessage)
				.anyMatch(message -> message.contains("반려 안내 메일 발송 실패"));
		}

		// 검증: FR-AUTH-18, AC-575-06
		@Test
		@DisplayName("이미 처리된 요청의 반려는 1422 이고 메일이 없다")
		void 이미_처리된_요청의_반려는_1422이고_메일이_없다() throws Exception {
			String email = uniqueEmail();
			OrgAccountRequest request = 접수한다(email, now());
			String reviewedAt = 검토_기준_시각(request.getId());
			반려_상태로_만든다(request.getId(), "이미 처리");

			mockMvc.perform(post(QUEUE_URL + "/" + request.getId() + "/reject")
					.header(HttpHeaders.AUTHORIZATION, adminToken)
					.contentType(MediaType.APPLICATION_JSON)
					.content(rejectBody("기관 확인 서류 누락", reviewedAt)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.developCode").value(1422));

			then(mailSender).should(never()).send(any(), any(), any(), any());
		}

		// 검증: FR-AUTH-13
		@Test
		@DisplayName("사유 없는 반려는 400 이다")
		void 사유_없는_반려는_400이다() throws Exception {
			OrgAccountRequest request = 접수한다(uniqueEmail(), now());
			String reviewedAt = 검토_기준_시각(request.getId());

			for (String body : new String[] {"""
				{"updatedAt": "%s"}""".formatted(reviewedAt), rejectBody("   ", reviewedAt)}) {
				mockMvc.perform(post(QUEUE_URL + "/" + request.getId() + "/reject")
						.header(HttpHeaders.AUTHORIZATION, adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.developCode").value(400));
			}
		}

		// 검증: FR-AUTH-13, FR-AUTH-18, AC-575-06
		@Test
		@DisplayName("상세 조회 후 익명 재접수가 일어나면 반려도 1426 이고 메일은 나가지 않는다")
		void 상세_조회_후_익명_재접수가_일어나면_반려도_1426이다() throws Exception {
			String email = uniqueEmail();
			LocalDateTime first = now();
			OrgAccountRequest request = 접수한다(email, first);
			String reviewedAt = 검토_기준_시각(request.getId());
			접수한다(email, first.plusMinutes(3));

			mockMvc.perform(post(QUEUE_URL + "/" + request.getId() + "/reject")
					.header(HttpHeaders.AUTHORIZATION, adminToken)
					.contentType(MediaType.APPLICATION_JSON)
					.content(rejectBody("기관 확인 서류 누락", reviewedAt)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.developCode").value(1426));

			assertThat(이메일로_찾는다(email).getFirst().getStatus()).isEqualTo(OrgAccountRequestStatus.PENDING);
			then(mailSender).should(never()).send(any(), any(), any(), any());
		}
	}
}
