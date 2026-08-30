package com.msg.fillmap.event.submission.controller;

import static com.msg.fillmap.event.submission.EventSubmissionFixtures.GWANGALLI_CENTER;
import static com.msg.fillmap.event.submission.EventSubmissionFixtures.GWANGALLI_RECT;
import static com.msg.fillmap.event.submission.EventSubmissionFixtures.festivalBody;
import static com.msg.fillmap.event.submission.EventSubmissionFixtures.festivalBodyWithDescription;
import static com.msg.fillmap.event.submission.EventSubmissionFixtures.festivalBodyWithTitle;
import static com.msg.fillmap.event.submission.EventSubmissionFixtures.location;
import static com.msg.fillmap.event.submission.EventSubmissionFixtures.pastFestivalBody;
import static com.msg.fillmap.event.submission.EventSubmissionFixtures.pendingKey;
import static com.msg.fillmap.event.submission.EventSubmissionFixtures.rect;
import static com.msg.fillmap.event.submission.EventSubmissionFixtures.updateBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
import com.msg.fillmap.event.submission.entity.EventSubmission;
import com.msg.fillmap.event.submission.entity.EventSubmissionLocation;
import com.msg.fillmap.event.submission.repository.EventSubmissionRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;

/**
 * 행사 등재 신청 제출·조회·재제출 (MSG-498, 실 DB). 신청 번호 시퀀스, 애그리거트 전체 교체, 조건부 UPDATE
 * 전이, 존재 은닉이 전부 DB 동작이라 목으로는 검증이 성립하지 않는다. {@code @Transactional} 롤백 격리로
 * 공유 로컬 DB 에 계정·신청을 남기지 않는다 (OrgAccountControllerTest 선례).
 * <p>
 * 반려 상태는 관리자 심사(MSG-500)가 만드는데 아직 없으므로 테스트가 그 행위를 SQL 로 대역한다 —
 * 이 티켓은 반려 행을 읽기만 하고 쓰지 않는다는 스펙 계약을 테스트 코드가 그대로 반영한 것이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("행사 등재 신청 (MSG-498, 실 DB)")
class EventSubmissionControllerTest {

	private static final String URL = "/api/org/event-submissions";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EventSubmissionRepository submissionRepository;

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
	private User otherOrganizer;

	@BeforeEach
	void setUp() {
		organizer = saveOrganizer();
		otherOrganizer = saveOrganizer();
		given(s3Client.headObject(any(HeadObjectRequest.class)))
			.willReturn(HeadObjectResponse.builder().contentLength(2048L).build());
		given(s3Client.copyObject(any(CopyObjectRequest.class))).willReturn(CopyObjectResponse.builder().build());
		given(thumbnailUrlPresigner.presign(anyString())).willReturn("https://signed.example/image.jpg");
	}

	private User saveOrganizer() {
		User user = User.createLocalUser("organizer-" + UUID.randomUUID() + "@fillmap.dev",
			passwordEncoder.encode("Initial1234"), "담당자");
		ReflectionTestUtils.setField(user, "role", UserRole.ORG);
		return userRepository.saveAndFlush(user);
	}

	private String bearer(User user) {
		return "Bearer " + tokenProvider.issueAccessToken(user.getId(), user.getRole());
	}

	/** 신청 하나를 제출하고 id 를 돌려준다 — 조회·재제출 테스트의 준비물이다. */
	private long 신청한다(User user, String body) throws Exception {
		String response = mockMvc.perform(post(URL)
				.header(HttpHeaders.AUTHORIZATION, bearer(user))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(response, "$.data.id")).longValue();
	}

	private long 축제를_신청한다(User user) throws Exception {
		return 신청한다(user, festivalBody(user.getId(), location(GWANGALLI_RECT)));
	}

	/**
	 * 관리자 심사(MSG-500) 대역 — 이 티켓은 반려 행을 쓰지 않으므로 SQL 로 만든다.
	 * JDBC 는 영속성 컨텍스트를 우회하므로 flush 로 앞선 쓰기를 DB 에 내린 뒤에 실행한다.
	 */
	private void 반려한다(long submissionId, String reasonCodes, String reasonText) {
		entityManager.flush();
		jdbcTemplate.update("UPDATE event_submissions SET status = 'REJECTED' WHERE id = ?", submissionId);
		jdbcTemplate.update("""
			INSERT INTO event_submission_status_history
				(event_submission_id, status, reason_codes, reason_text, created_at)
			VALUES (?, 'REJECTED', ?, ?, ?)
			""", submissionId, reasonCodes, reasonText, LocalDateTime.now(ZoneOffset.UTC));
		entityManager.clear();
	}

	/** 승인 번호를 함께 넣는다 — V51 의 chk_event_sub_approval 이 "승인 행 = 승인 번호 있는 행"을 강제한다(MSG-500). */
	private void 승인한다(long submissionId) {
		entityManager.flush();
		jdbcTemplate.update("UPDATE event_submissions SET status = 'APPROVED', approval_no = ? WHERE id = ?",
			"APR-2026-" + submissionId, submissionId);
		entityManager.clear();
	}

	private EventSubmission 저장된_신청(long submissionId, User owner) {
		entityManager.flush();
		entityManager.clear();
		return submissionRepository.findByIdAndUserId(submissionId, owner.getId()).orElseThrow();
	}

	@Nested
	@DisplayName("제출")
	class Submit {

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("신청하면 심사 중 상태와 FM 꼴 신청 번호가 부여되고 대표 격자가 저장된다")
		void 신청하면_심사_중_상태와_FM꼴_신청_번호가_부여된다() throws Exception {
			long id = 축제를_신청한다(organizer);

			EventSubmission saved = 저장된_신청(id, organizer);
			assertThat(saved.getSubmissionNo()).matches("FM-\\d{4}-\\d{4,}");
			assertThat(saved.getStatus().name()).isEqualTo("IN_REVIEW");
			assertThat(saved.getImageKey()).startsWith("event-submissions/original/" + organizer.getId() + "/");
			assertThat(saved.getLocations()).singleElement()
				.extracting(EventSubmissionLocation::getRepresentativeGridId)
				.isEqualTo(GWANGALLI_CENTER);
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("신청 번호는 제출마다 증가하고 겹치지 않는다")
		void 신청_번호는_제출마다_증가하고_겹치지_않는다() throws Exception {
			String first = 저장된_신청(축제를_신청한다(organizer), organizer).getSubmissionNo();
			String second = 저장된_신청(축제를_신청한다(organizer), organizer).getSubmissionNo();

			assertThat(first).isNotEqualTo(second);
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("신청하면 이력에 심사 중 행이 남는다")
		void 신청하면_이력에_심사_중_행이_남는다() throws Exception {
			long id = 축제를_신청한다(organizer);

			mockMvc.perform(get(URL + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(organizer)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.history.length()").value(1))
				.andExpect(jsonPath("$.data.history[0].status").value("IN_REVIEW"))
				.andExpect(jsonPath("$.data.history[0].reasonCodes").isEmpty())
				.andExpect(jsonPath("$.data.history[0].changedAt").exists());
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("합집합 82칸 위치는 13432 로 거부한다")
		void 위치_사각형_합산이_82칸이면_거부한다() throws Exception {
			신청_실패(organizer, festivalBody(organizer.getId(),
				location(rect(100, 108, 200, 208), rect(300, 300, 400, 400))), 400, 13432);
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("사각형이 뒤집힌 위치는 13431 로 거부한다")
		void 사각형의_min이_max보다_크면_거부한다() throws Exception {
			신청_실패(organizer, festivalBody(organizer.getId(), location(rect(108, 100, 200, 208))), 400, 13431);
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("이미 끝난 행사 신청은 13433 으로 거부한다")
		void 종료일이_오늘_이전이면_거부한다() throws Exception {
			신청_실패(organizer, pastFestivalBody(organizer.getId(), location(GWANGALLI_RECT)), 400, 13433);
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("남의 pending 키로 신청하면 13435 로 거부한다")
		void 남의_pending_키로_신청하면_거부한다() throws Exception {
			신청_실패(organizer, festivalBody(pendingKey(otherOrganizer.getId()), location(GWANGALLI_RECT)),
				400, 13435);
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("소개가 10자 미만이거나 2000자를 넘거나 제목이 100자를 넘으면 공통 400 이다")
		void 서술_항목_길이_위반은_400이다() throws Exception {
			for (String body : List.of(
				festivalBodyWithDescription(organizer.getId(), "짧은소개", location(GWANGALLI_RECT)),
				festivalBodyWithDescription(organizer.getId(), "가".repeat(2001), location(GWANGALLI_RECT)),
				festivalBodyWithTitle(organizer.getId(), "가".repeat(101), location(GWANGALLI_RECT)))) {
				신청_실패(organizer, body, 400, 400);
			}
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("위치나 사각형 자리에 null 이 들어오면 400 이다 — 도메인까지 내려가 500 이 되지 않는다")
		void 목록_원소가_null이면_400이다() throws Exception {
			신청_실패(organizer, festivalBody(organizer.getId(), "null"), 400, 400);
			신청_실패(organizer, festivalBody(organizer.getId(), location("null")), 400, 400);
		}

		// 검증: FR-EVENT-13
		@Test
		@DisplayName("정의되지 않은 등록 유형은 400 이다")
		void 알_수_없는_유형은_400이다() throws Exception {
			신청_실패(organizer,
				festivalBody(organizer.getId(), location(GWANGALLI_RECT)).replace("\"FESTIVAL\"", "\"CONCERT\""),
				400, 400);
		}

		private void 신청_실패(User user, String body, int httpStatus, int developCode) throws Exception {
			mockMvc.perform(post(URL)
					.header(HttpHeaders.AUTHORIZATION, bearer(user))
					.contentType(MediaType.APPLICATION_JSON)
					.content(body))
				.andExpect(status().is(httpStatus))
				.andExpect(jsonPath("$.developCode").value(developCode));
		}
	}

	@Nested
	@DisplayName("내 신청 목록")
	class MyList {

		// 검증: FR-EVENT-14
		@Test
		@DisplayName("상태별 건수가 실리고 목록은 최신 제출부터 온다")
		void 내_신청_목록에_상태별_건수가_실린다() throws Exception {
			long first = 축제를_신청한다(organizer);
			long second = 축제를_신청한다(organizer);
			반려한다(second, "AREA", "영역이 너무 넓습니다");

			mockMvc.perform(get(URL + "/my").header(HttpHeaders.AUTHORIZATION, bearer(organizer)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.counts.inReview").value(1))
				.andExpect(jsonPath("$.data.counts.approved").value(0))
				.andExpect(jsonPath("$.data.counts.rejected").value(1))
				.andExpect(jsonPath("$.data.submissions.length()").value(2))
				.andExpect(jsonPath("$.data.submissions[0].id").value(second))
				.andExpect(jsonPath("$.data.submissions[0].status").value("REJECTED"))
				.andExpect(jsonPath("$.data.submissions[1].id").value(first))
				.andExpect(jsonPath("$.data.submissions[0].updatedAt").exists());
		}

		// 검증: FR-EVENT-14
		@Test
		@DisplayName("남의 신청은 목록과 건수에 잡히지 않는다")
		void 남의_신청은_목록과_건수에_잡히지_않는다() throws Exception {
			축제를_신청한다(otherOrganizer);

			mockMvc.perform(get(URL + "/my").header(HttpHeaders.AUTHORIZATION, bearer(organizer)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.counts.inReview").value(0))
				.andExpect(jsonPath("$.data.submissions.length()").value(0));
		}
	}

	@Nested
	@DisplayName("신청 상세")
	class Detail {

		// 검증: FR-EVENT-14
		@Test
		@DisplayName("위치 순번과 표시명 재료와 칸 수와 제출 원본 사각형이 실린다")
		void 상세에_위치_순번과_표시명_재료와_칸수가_실린다() throws Exception {
			long id = 축제를_신청한다(organizer);

			mockMvc.perform(get(URL + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(organizer)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.type").value("FESTIVAL"))
				.andExpect(jsonPath("$.data.operatingHours").isEmpty())
				.andExpect(jsonPath("$.data.imageUrl").value("https://signed.example/image.jpg"))
				.andExpect(jsonPath("$.data.locations[0].order").value(1))
				.andExpect(jsonPath("$.data.locations[0].representativeGridId").value(GWANGALLI_CENTER))
				.andExpect(jsonPath("$.data.locations[0].cellCount").value(21))
				.andExpect(jsonPath("$.data.locations[0].areaRects[0].minGridY").value(16859))
				.andExpect(jsonPath("$.data.locations[0].areaRects[0].maxGridX").value(11515))
				.andExpect(jsonPath("$.data.rejection").isEmpty());
		}

		// 검증: FR-EVENT-14
		@Test
		@DisplayName("반려된 신청 상세에 반려 코드 배열과 사유가 실린다")
		void 반려된_신청_상세에_반려_코드_배열과_사유가_실린다() throws Exception {
			long id = 축제를_신청한다(organizer);
			반려한다(id, "AREA,INFO", "영역과 기본 정보를 다시 확인해 주세요");

			mockMvc.perform(get(URL + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(organizer)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("REJECTED"))
				.andExpect(jsonPath("$.data.rejection.reasonCodes[0]").value("AREA"))
				.andExpect(jsonPath("$.data.rejection.reasonCodes[1]").value("INFO"))
				.andExpect(jsonPath("$.data.rejection.reasonText").value("영역과 기본 정보를 다시 확인해 주세요"))
				.andExpect(jsonPath("$.data.history.length()").value(2));
		}

		// 검증: FR-EVENT-14
		@Test
		@DisplayName("없는 신청과 남의 신청의 실패 응답이 완전히 같다")
		void 없는_신청과_남의_신청의_실패_응답이_같다() throws Exception {
			long othersId = 축제를_신청한다(otherOrganizer);

			String missing = 상세_실패(999_999_999L);
			String others = 상세_실패(othersId);

			assertThat(others).isEqualTo(missing);
			assertThat(missing).contains("13430");
		}

		private String 상세_실패(long submissionId) throws Exception {
			return mockMvc.perform(get(URL + "/" + submissionId)
					.header(HttpHeaders.AUTHORIZATION, bearer(organizer)))
				.andExpect(status().isNotFound())
				.andReturn().getResponse().getContentAsString();
		}
	}

	@Nested
	@DisplayName("수정 재제출")
	class Resubmit {

		// 검증: FR-EVENT-14
		@Test
		@DisplayName("반려된 신청을 수정하면 심사 중으로 돌아가고 신청 번호는 그대로다")
		void 반려된_신청을_수정하면_심사_중으로_돌아간다() throws Exception {
			long id = 축제를_신청한다(organizer);
			String submissionNo = 저장된_신청(id, organizer).getSubmissionNo();
			반려한다(id, "INFO", "기본 정보를 확인해 주세요");

			수정한다(organizer, id, updateBody("부산불꽃축제 2026", null, location(GWANGALLI_RECT)))
				.andExpect(jsonPath("$.data.status").value("IN_REVIEW"))
				.andExpect(jsonPath("$.data.submissionNo").value(submissionNo));

			EventSubmission saved = 저장된_신청(id, organizer);
			assertThat(saved.getTitle()).isEqualTo("부산불꽃축제 2026");
			assertThat(saved.getStatus().name()).isEqualTo("IN_REVIEW");
			mockMvc.perform(get(URL + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(organizer)))
				.andExpect(jsonPath("$.data.history.length()").value(3))
				.andExpect(jsonPath("$.data.history[2].status").value("IN_REVIEW"))
				// 재제출 뒤에도 과거 반려는 이력에 남아 사유를 계속 확인할 수 있다.
				.andExpect(jsonPath("$.data.history[1].reasonText").value("기본 정보를 확인해 주세요"))
				.andExpect(jsonPath("$.data.rejection").isEmpty());
		}

		// 검증: FR-EVENT-14
		@Test
		@DisplayName("수정하면 위치와 대표 격자가 전체 교체된다")
		void 수정하면_위치와_대표_격자가_전체_교체된다() throws Exception {
			long id = 축제를_신청한다(organizer);
			반려한다(id, "AREA", "위치를 다시 지정해 주세요");

			수정한다(organizer, id, updateBody("부산불꽃축제", null,
				location(rect(100, 102, 200, 202)), location(rect(300, 300, 400, 400))));

			assertThat(저장된_신청(id, organizer).getLocations())
				.extracting(EventSubmissionLocation::getDisplayOrder,
					EventSubmissionLocation::getRepresentativeGridId)
				.containsExactly(tuple(1, "101_201"), tuple(2, "300_400"));
		}

		// 검증: FR-EVENT-14
		@Test
		@DisplayName("이미지 키를 생략하면 기존 이미지가 유지된다")
		void 수정에서_이미지_키를_생략하면_기존_이미지가_유지된다() throws Exception {
			long id = 축제를_신청한다(organizer);
			String imageKey = 저장된_신청(id, organizer).getImageKey();
			반려한다(id, "INFO", "기본 정보를 확인해 주세요");

			수정한다(organizer, id, updateBody("부산불꽃축제", null, location(GWANGALLI_RECT)));

			assertThat(저장된_신청(id, organizer).getImageKey()).isEqualTo(imageKey);
		}

		// 검증: FR-EVENT-14
		@Test
		@DisplayName("이미지 키를 보내면 새 확정본으로 교체된다")
		void 수정에서_이미지_키를_보내면_교체된다() throws Exception {
			long id = 축제를_신청한다(organizer);
			String imageKey = 저장된_신청(id, organizer).getImageKey();
			반려한다(id, "IMAGE", "대표 이미지를 바꿔 주세요");

			수정한다(organizer, id,
				updateBody("부산불꽃축제", pendingKey(organizer.getId()), location(GWANGALLI_RECT)));

			assertThat(저장된_신청(id, organizer).getImageKey())
				.isNotEqualTo(imageKey)
				.startsWith("event-submissions/original/" + organizer.getId() + "/");
		}

		// 검증: FR-EVENT-14
		@Test
		@DisplayName("재제출도 위치 자리의 null 을 400 으로 막는다 — 상태 전이 앞에서 걸린다")
		void 재제출_목록_원소가_null이면_400이다() throws Exception {
			long id = 축제를_신청한다(organizer);
			반려한다(id, "AREA", "위치를 다시 지정해 주세요");

			mockMvc.perform(patch(URL + "/" + id)
					.header(HttpHeaders.AUTHORIZATION, bearer(organizer))
					.contentType(MediaType.APPLICATION_JSON)
					.content(updateBody("부산불꽃축제", null, "null")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(400));

			// 검증 실패가 전이 앞이라 상태는 그대로 반려다.
			assertThat(저장된_신청(id, organizer).getStatus().name()).isEqualTo("REJECTED");
		}

		// 검증: FR-EVENT-14
		@Test
		@DisplayName("심사 중이거나 승인된 신청의 수정은 13434 로 거부한다")
		void 심사_중과_승인된_신청의_수정은_거부한다() throws Exception {
			long inReview = 축제를_신청한다(organizer);
			수정_실패(organizer, inReview, 409, 13434);

			long approved = 축제를_신청한다(organizer);
			승인한다(approved);
			수정_실패(organizer, approved, 409, 13434);
		}

		// 검증: FR-EVENT-14
		@Test
		@DisplayName("남의 신청 수정 요청도 같은 13430 이다 — 남의 반려 신청이 13434 로 새지 않는다")
		void 남의_신청_수정_요청도_같은_실패_응답이다() throws Exception {
			long othersRejected = 축제를_신청한다(otherOrganizer);
			반려한다(othersRejected, "INFO", "기본 정보를 확인해 주세요");

			수정_실패(organizer, othersRejected, 404, 13430);
			수정_실패(organizer, 999_999_999L, 404, 13430);
			// 남의 신청은 요청으로 바뀌지 않는다 — UPDATE 술어의 userId 가 막는다.
			assertThat(저장된_신청(othersRejected, otherOrganizer).getStatus().name()).isEqualTo("REJECTED");
		}

		private ResultActions 수정한다(User user, long id, String body) throws Exception {
			return mockMvc.perform(patch(URL + "/" + id)
					.header(HttpHeaders.AUTHORIZATION, bearer(user))
					.contentType(MediaType.APPLICATION_JSON)
					.content(body))
				.andExpect(status().isOk());
		}

		private void 수정_실패(User user, long id, int httpStatus, int developCode) throws Exception {
			mockMvc.perform(patch(URL + "/" + id)
					.header(HttpHeaders.AUTHORIZATION, bearer(user))
					.contentType(MediaType.APPLICATION_JSON)
					.content(updateBody("부산불꽃축제", null, location(GWANGALLI_RECT))))
				.andExpect(status().is(httpStatus))
				.andExpect(jsonPath("$.developCode").value(developCode));
		}
	}
}
