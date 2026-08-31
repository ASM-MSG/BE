package com.msg.fillmap.event.submission.controller;

import static com.msg.fillmap.event.submission.EventSubmissionFixtures.GWANGALLI_RECT;
import static com.msg.fillmap.event.submission.EventSubmissionFixtures.festivalBody;
import static com.msg.fillmap.event.submission.EventSubmissionFixtures.location;
import static com.msg.fillmap.event.submission.EventSubmissionFixtures.rect;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;

/**
 * 관리자 심사 큐·상세 (MSG-500 §API 1·2, 실 DB). 검증 대상이 신청 계정 조인, 위치 수 상관 서브쿼리,
 * 상태 필터와 무관한 전체 건수, 저장된 사각형에서 접는 노출 영역이라 전부 DB 동작이다 — 목으로는
 * 프로젝션이 실제로 조립되는지조차 확인되지 않는다.
 * <p>
 * 건수 단언은 절대값이 아니라 <b>증분</b>이다: 건수 3종이 필터와 무관한 전역 집계라 공유 로컬 DB 의 기존
 * 신청이 그대로 더해지기 때문이다. {@code @Transactional} 롤백 격리로 계정·신청을 남기지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("관리자 행사 등재 심사 조회 (MSG-500, 실 DB)")
class AdminEventSubmissionControllerTest {

	private static final String ADMIN_URL = "/api/admin/event-submissions";
	private static final String ORG_URL = "/api/org/event-submissions";

	/** 광안리 위치와 겹치지 않는 둘째 위치 — 노출 영역이 두 위치를 함께 감싸는지 보려면 떨어져 있어야 한다. */
	private static final String HAEUNDAE_RECT = rect(16870, 16872, 11520, 11525);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private TokenProvider tokenProvider;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	@MockitoBean
	private S3Client s3Client;

	@MockitoBean
	private ThumbnailUrlPresigner thumbnailUrlPresigner;

	private User organizer;
	private User admin;

	@BeforeEach
	void setUp() {
		organizer = saveUser(UserRole.ORG);
		admin = saveUser(UserRole.ADMIN);
		given(s3Client.headObject(any(HeadObjectRequest.class)))
			.willReturn(HeadObjectResponse.builder().contentLength(2048L).build());
		given(s3Client.copyObject(any(CopyObjectRequest.class))).willReturn(CopyObjectResponse.builder().build());
		given(thumbnailUrlPresigner.presign(anyString())).willReturn("https://signed.example/image.jpg");
	}

	private User saveUser(UserRole role) {
		// createOrgUser 를 쓰지 않는다 — 강제 변경 플래그가 서면 게이트 인터셉터가 /api/org 제출을 막는다.
		User user = User.createLocalUser("admin-sub-" + UUID.randomUUID() + "@fillmap.dev",
			passwordEncoder.encode("Initial1234"), "김담당");
		ReflectionTestUtils.setField(user, "role", role);
		ReflectionTestUtils.setField(user, "orgName", "부산광역시 부산진구청");
		return userRepository.saveAndFlush(user);
	}

	private String bearer(User user) {
		return "Bearer " + tokenProvider.issueAccessToken(user.getId(), user.getRole());
	}

	private long 신청한다(String... locations) throws Exception {
		String response = mockMvc.perform(post(ORG_URL)
				.header(HttpHeaders.AUTHORIZATION, bearer(organizer))
				.contentType(MediaType.APPLICATION_JSON)
				.content(festivalBody(organizer.getId(), locations)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(response, "$.data.id")).longValue();
	}

	/** 승인 대역 — 승인 경로(M3)는 아직 없고 여기서 필요한 것은 "승인 상태 행"뿐이다. */
	private void 승인_상태로_바꾼다(long submissionId) {
		entityManager.flush();
		jdbcTemplate.update("UPDATE event_submissions SET status = 'APPROVED', approval_no = ? WHERE id = ?",
			"APR-2026-" + submissionId, submissionId);
		entityManager.clear();
	}

	private ResultActions 큐를_조회한다(String query) throws Exception {
		entityManager.flush();
		entityManager.clear();
		return mockMvc.perform(get(ADMIN_URL + query).header(HttpHeaders.AUTHORIZATION, bearer(admin)));
	}

	private ResultActions 상세를_조회한다(long submissionId) throws Exception {
		entityManager.flush();
		entityManager.clear();
		return mockMvc.perform(get(ADMIN_URL + "/" + submissionId)
			.header(HttpHeaders.AUTHORIZATION, bearer(admin)));
	}

	private long 큐_건수(String field, String query) throws Exception {
		String response = 큐를_조회한다(query).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(response, "$.data.counts." + field)).longValue();
	}

	@Nested
	@DisplayName("심사 큐")
	class Queue {

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("건수 3종은 상태 필터와 무관한 전체 집계다")
		void 심사_큐는_상태_필터와_무관한_건수_3종을_함께_준다() throws Exception {
			long 처음_심사_중 = 큐_건수("inReview", "");
			long 처음_승인 = 큐_건수("approved", "");

			신청한다(location(GWANGALLI_RECT));
			승인_상태로_바꾼다(신청한다(location(GWANGALLI_RECT)));

			// 필터가 심사 중이어도 승인 건수가 함께 늘어난다 — 탭 뱃지 재료라 필터를 타지 않는다.
			큐를_조회한다("?status=IN_REVIEW")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.counts.inReview").value(처음_심사_중 + 1))
				.andExpect(jsonPath("$.data.counts.approved").value(처음_승인 + 1));
			// 목록 자체는 필터를 탄다 — 방금 승인한 건은 심사 중 목록에 없다.
			큐를_조회한다("?status=APPROVED&size=100")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.submissions[?(@.status == 'IN_REVIEW')]").isEmpty());
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("항목에 신청 계정의 기관명과 위치 수가 실린다")
		void 심사_큐_항목은_기관명과_위치_수를_함께_준다() throws Exception {
			long id = 신청한다(location(GWANGALLI_RECT), location(HAEUNDAE_RECT));

			큐를_조회한다("?status=IN_REVIEW&size=100")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.submissions[?(@.id == %d)].orgName".formatted(id))
					.value("부산광역시 부산진구청"))
				.andExpect(jsonPath("$.data.submissions[?(@.id == %d)].locationCount".formatted(id)).value(2))
				.andExpect(jsonPath("$.data.submissions[?(@.id == %d)].organizerName".formatted(id))
					.value("부산문화관광축제조직위원회"))
				.andExpect(jsonPath("$.data.submissions[?(@.id == %d)].type".formatted(id)).value("FESTIVAL"));
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("지원하지 않는 상태 필터는 13455 다")
		void 지원하지_않는_상태_필터는_13455다() throws Exception {
			큐를_조회한다("?status=UNKNOWN")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(13455));
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("페이지 번호나 크기가 범위 밖이면 13456 이다")
		void 페이지_범위_밖_요청은_13456이다() throws Exception {
			for (String query : new String[] {"?page=-1", "?size=0", "?size=101"}) {
				큐를_조회한다(query)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.developCode").value(13456));
			}
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("관리자가 아니면 심사 큐에 접근할 수 없다")
		void 관리자가_아니면_심사_큐에_접근할_수_없다() throws Exception {
			mockMvc.perform(get(ADMIN_URL).header(HttpHeaders.AUTHORIZATION, bearer(organizer)))
				.andExpect(status().isForbidden());
			mockMvc.perform(get(ADMIN_URL)).andExpect(status().isUnauthorized());
		}
	}

	@Nested
	@DisplayName("심사 상세")
	class Detail {

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("노출 영역 경계 사각형과 신청 계정 정보를 준다")
		void 심사_상세는_노출_영역_경계_사각형과_신청_계정_정보를_준다() throws Exception {
			long id = 신청한다(location(GWANGALLI_RECT), location(HAEUNDAE_RECT));

			상세를_조회한다(id)
				.andExpect(status().isOk())
				// 두 위치를 함께 감싸는 하나의 사각형 — 위치별 사각형이 아니라 합집합의 경계다.
				.andExpect(jsonPath("$.data.exposureRect.minGridY").value(16859))
				.andExpect(jsonPath("$.data.exposureRect.maxGridY").value(16872))
				.andExpect(jsonPath("$.data.exposureRect.minGridX").value(11509))
				.andExpect(jsonPath("$.data.exposureRect.maxGridX").value(11525))
				.andExpect(jsonPath("$.data.orgName").value("부산광역시 부산진구청"))
				.andExpect(jsonPath("$.data.contactName").value("김담당"))
				.andExpect(jsonPath("$.data.email").value(organizer.getEmail()))
				.andExpect(jsonPath("$.data.locations.length()").value(2))
				.andExpect(jsonPath("$.data.locations[0].representativeGridId").value("16860_11512"))
				.andExpect(jsonPath("$.data.locations[0].cellCount").value(21))
				.andExpect(jsonPath("$.data.imageUrl").value("https://signed.example/image.jpg"))
				.andExpect(jsonPath("$.data.history.length()").value(1))
				.andExpect(jsonPath("$.data.history[0].status").value("IN_REVIEW"))
				// 참여형 재료 둘은 축제·팝업 신청에서 키만 있고 값이 없다 (EVENT 전용) — 키 자체는
				// 스키마 계약상 항상 나간다(required + nullable 병기).
				.andExpect(jsonPath("$.data.participationMethod").isEmpty())
				.andExpect(jsonPath("$.data.parentEvent").isEmpty());
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("없는 신청은 존재 은닉 없이 그대로 13430 이다")
		void 없는_신청의_상세는_13430이다() throws Exception {
			상세를_조회한다(99_999_999L)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.developCode").value(13430));
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("남의 신청도 관리자에게는 그대로 보인다")
		void 남의_신청도_관리자에게는_보인다() throws Exception {
			long id = 신청한다(location(GWANGALLI_RECT));

			상세를_조회한다(id)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("IN_REVIEW"))
				.andExpect(jsonPath("$.data.submissionNo").exists());
		}
	}
}
