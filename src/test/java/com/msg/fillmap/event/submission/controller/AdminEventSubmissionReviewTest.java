package com.msg.fillmap.event.submission.controller;

import static com.msg.fillmap.event.submission.EventSubmissionFixtures.GWANGALLI_RECT;
import static com.msg.fillmap.event.submission.EventSubmissionFixtures.festivalBody;
import static com.msg.fillmap.event.submission.EventSubmissionFixtures.location;
import static com.msg.fillmap.event.submission.EventSubmissionFixtures.popupBody;
import static com.msg.fillmap.event.submission.EventSubmissionFixtures.rect;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.atLeastOnce;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
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
import com.msg.fillmap.event.submission.repository.EventSubmissionRepository;
import com.msg.fillmap.mission.entity.Mission;
import com.msg.fillmap.mission.entity.MissionGrid;
import com.msg.fillmap.mission.entity.MissionType;
import com.msg.fillmap.mission.repository.MissionGridRepository;
import com.msg.fillmap.mission.repository.MissionRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;

/**
 * 관리자 승인·반려 (MSG-500 §API 3·4, 실 DB). 검증 대상이 조건부 UPDATE 원자 전이, 미션 1건과 판정 격자
 * 삽입, 지연 FK 를 통과하는 대표 격자, 한 트랜잭션 안의 이력·산출물이라 전부 DB 동작이다.
 * <p>
 * 커밋 후 동작(스냅숏 무효화)은 여기서 검증하지 않는다 — {@code @Transactional} 롤백 격리에서는 커밋이
 * 없어 afterCommit 훅이 아예 실행되지 않기 때문이고, 무효화 자체는 MissionQueryServiceCacheTest 가
 * 단위로 본다. 여기서 보는 것은 "DB 에 활성 미션으로 들어갔는가"다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("관리자 행사 등재 심사 승인·반려 (MSG-500, 실 DB)")
class AdminEventSubmissionReviewTest {

	private static final String ADMIN_URL = "/api/admin/event-submissions";
	private static final String ORG_URL = "/api/org/event-submissions";

	/** 광안리 3행 7열을 3열 + 4열로 쪼갠 두 위치 — 합집합이 다시 홀수 직사각형이라 정중앙이 결정적이다. */
	private static final String WEST_RECT = rect(16859, 16861, 11509, 11511);
	private static final String EAST_RECT = rect(16859, 16861, 11512, 11515);
	private static final String UNION_CENTER = "16860_11512";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EventSubmissionRepository submissionRepository;

	@Autowired
	private MissionRepository missionRepository;

	@Autowired
	private MissionGridRepository missionGridRepository;

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
		User user = User.createLocalUser("review-" + UUID.randomUUID() + "@fillmap.dev",
			passwordEncoder.encode("Initial1234"), "김담당");
		ReflectionTestUtils.setField(user, "role", role);
		ReflectionTestUtils.setField(user, "orgName", "부산광역시 부산진구청");
		return userRepository.saveAndFlush(user);
	}

	private String bearer(User user) {
		return "Bearer " + tokenProvider.issueAccessToken(user.getId(), user.getRole());
	}

	private long 신청한다(String body) throws Exception {
		String response = mockMvc.perform(post(ORG_URL)
				.header(HttpHeaders.AUTHORIZATION, bearer(organizer))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(response, "$.data.id")).longValue();
	}

	private long 축제를_신청한다() throws Exception {
		return 신청한다(festivalBody(organizer.getId(), location(GWANGALLI_RECT)));
	}

	private ResultActions 승인한다(long submissionId) throws Exception {
		entityManager.flush();
		return mockMvc.perform(post(ADMIN_URL + "/" + submissionId + "/approve")
			.header(HttpHeaders.AUTHORIZATION, bearer(admin)));
	}

	private ResultActions 반려한다(long submissionId, String body) throws Exception {
		entityManager.flush();
		return mockMvc.perform(post(ADMIN_URL + "/" + submissionId + "/reject")
			.header(HttpHeaders.AUTHORIZATION, bearer(admin))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private String rejectBody(String reasonCodes, String reasonText) {
		return """
			{"reasonCodes": %s, "reasonText": "%s"}""".formatted(reasonCodes, reasonText);
	}

	private EventSubmission 저장된_신청(long submissionId) {
		entityManager.flush();
		entityManager.clear();
		return submissionRepository.findById(submissionId).orElseThrow();
	}

	/** 승인 산출물 미션 — 신청 행의 링크로 찾는다(승인이 그 링크를 남기는 것 자체가 계약이다). */
	private Mission 승인_미션(long submissionId) {
		Long missionId = 저장된_신청(submissionId).getPublishedMissionId();
		assertThat(missionId).isNotNull();
		return missionRepository.findById(missionId).orElseThrow();
	}

	@Nested
	@DisplayName("승인 — 미션 등재")
	class ApproveToMission {

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("축제 신청을 승인하면 축제 칩 미션이 생기고 활성 조회에 바로 잡힌다")
		void 축제_신청을_승인하면_축제_칩_미션이_생기고_활성_조회에_바로_잡힌다() throws Exception {
			long id = 축제를_신청한다();

			승인한다(id).andExpect(status().isOk());

			Mission mission = 승인_미션(id);
			assertThat(mission.getType()).isEqualTo(MissionType.EVENT);
			assertThat(mission.getTitle()).isEqualTo("부산불꽃축제");
			assertThat(mission.getSource()).isEqualTo("ORG_SUBMISSION");
			assertThat(mission.getSourceKey()).isEqualTo(저장된_신청(id).getSubmissionNo());
			assertThat(mission.getTargetCount()).isEqualTo(1);
			// KST 날짜 라벨 → UTC 순간 (축제 시더와 같은 규칙): 시작 KST 00:00, 끝 KST 23:59:59.
			assertThat(mission.getStartAt()).isEqualTo(LocalDateTime.of(2026, 11, 6, 15, 0, 0));
			assertThat(mission.getEndAt()).isEqualTo(LocalDateTime.of(2026, 11, 7, 14, 59, 59));
			assertThat(mission.getDescription()).isEqualTo("광안리해수욕장 일원에서 열리는 부산 대표 불꽃 축제");
			assertThat(mission.getOperationTime()).isNull();

			// 재시드·재기동 없이 기존 활성 조회 술어에 그대로 잡힌다 (기간 안의 시각으로 확인).
			assertThat(missionRepository.findActive(LocalDateTime.of(2026, 11, 7, 3, 0)))
				.extracting(Mission::getId)
				.contains(mission.getId());
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("팝업 신청을 승인하면 팝업 미션이 생기고 운영 시간이 함께 넘어간다")
		void 팝업_신청을_승인하면_팝업_미션이_생긴다() throws Exception {
			long id = 신청한다(popupBody(organizer.getId(), location(GWANGALLI_RECT)));

			승인한다(id).andExpect(status().isOk());

			Mission mission = 승인_미션(id);
			assertThat(mission.getType()).isEqualTo(MissionType.POPUP);
			assertThat(mission.getOperationTime()).isEqualTo("11:00 ~ 20:00");
			assertThat(mission.getTitle()).isEqualTo("필맵 팝업스토어");
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("판정 격자는 전 위치 셀 합집합이고 대표 격자는 합집합에 규칙을 다시 적용한 값이다")
		void 승인_미션의_격자는_전_위치_셀_합집합이고_대표_격자는_합집합_재적용_값이다() throws Exception {
			long id = 신청한다(festivalBody(organizer.getId(), location(WEST_RECT), location(EAST_RECT)));

			승인한다(id).andExpect(status().isOk());

			Mission mission = 승인_미션(id);
			List<MissionGrid> grids = missionGridRepository.findByMissionIds(List.of(mission.getId()));
			assertThat(grids).hasSize(21);   // 3행 3열 + 3행 4열, 겹침 없음
			// 위치별 저장값(3×3 중앙 16860_11510)이 아니라 합집합 3×7 의 정중앙이다.
			assertThat(mission.getRepresentativeGridId()).isEqualTo(UNION_CENTER);
			assertThat(grids).extracting(MissionGrid::getGridId).contains(UNION_CENTER);
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("승인하면 승인됨 상태와 APR 꼴 승인 번호, 승인 이력이 함께 남는다")
		void 승인하면_신청이_승인됨_상태가_되고_승인_번호와_이력이_함께_남는다() throws Exception {
			long id = 축제를_신청한다();

			승인한다(id)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("APPROVED"))
				.andExpect(jsonPath("$.data.approvalNo").value(org.hamcrest.Matchers.matchesPattern(
					"APR-\\d{4}-\\d{4,}")));

			EventSubmission approved = 저장된_신청(id);
			assertThat(approved.getStatus().name()).isEqualTo("APPROVED");
			assertThat(approved.getApprovalNo()).matches("APR-\\d{4}-\\d{4,}");
			assertThat(approved.getPublishedMissionId()).isNotNull();
			mockMvc.perform(get(ADMIN_URL + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(jsonPath("$.data.history.length()").value(2))
				.andExpect(jsonPath("$.data.history[1].status").value("APPROVED"))
				.andExpect(jsonPath("$.data.history[1].reasonCodes").isEmpty());
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("대표 이미지는 공개 프리픽스로 복사되고 미션은 그 공개 주소를 갖는다")
		void 승인_이미지는_공개_프리픽스로_복사된다() throws Exception {
			long id = 축제를_신청한다();

			승인한다(id).andExpect(status().isOk());

			ArgumentCaptor<CopyObjectRequest> captor = ArgumentCaptor.forClass(CopyObjectRequest.class);
			then(s3Client).should(atLeastOnce()).copyObject(captor.capture());
			CopyObjectRequest publicCopy = captor.getAllValues().stream()
				.filter(request -> request.destinationKey().startsWith("missions/org-submission/"))
				.findFirst()
				.orElseThrow();
			// 원본은 확정본이고 지우지 않는다 — 심사 상세와 콘솔 상세가 계속 읽는다.
			assertThat(publicCopy.sourceKey()).startsWith("event-submissions/original/");
			assertThat(승인_미션(id).getImageUrl()).contains("missions/org-submission/");
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("이미 승인된 신청의 재승인은 13450 이고 미션이 두 번 생기지 않는다")
		void 이미_승인된_신청의_재승인은_13450이다() throws Exception {
			long id = 축제를_신청한다();
			승인한다(id).andExpect(status().isOk());
			Long missionId = 저장된_신청(id).getPublishedMissionId();

			승인한다(id)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.developCode").value(13450));

			assertThat(저장된_신청(id).getPublishedMissionId()).isEqualTo(missionId);
			assertThat(missionRepository.findBySource("ORG_SUBMISSION").stream()
				.filter(mission -> mission.getSourceKey().equals(저장된_신청(id).getSubmissionNo()))
				.toList()).hasSize(1);
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("종료일이 지난 신청의 승인은 13451 이고 미션도 승인 번호도 생기지 않는다")
		void 종료일이_지난_신청의_승인은_13451이다() throws Exception {
			long id = 축제를_신청한다();
			// 접수 검증이 과거 행사를 막으므로 심사 지연을 SQL 로 재현한다 — 가드가 겨냥한 상황이 이것이다.
			entityManager.flush();
			jdbcTemplate.update("UPDATE event_submissions SET starts_on = ?, ends_on = ? WHERE id = ?",
				LocalDate.of(2020, 11, 6), LocalDate.of(2020, 11, 7), id);
			entityManager.clear();

			승인한다(id)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.developCode").value(13451));

			EventSubmission untouched = 저장된_신청(id);
			assertThat(untouched.getStatus().name()).isEqualTo("IN_REVIEW");
			assertThat(untouched.getApprovalNo()).isNull();
			assertThat(untouched.getPublishedMissionId()).isNull();
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("없는 신청의 승인은 13430 이다")
		void 없는_신청의_승인은_13430이다() throws Exception {
			승인한다(99_999_999L)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.developCode").value(13430));
		}
	}

	@Nested
	@DisplayName("반려")
	class Reject {

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("반려는 항목 코드와 서술이 이력 반려 행으로 남고 상태가 반려됨이 된다")
		void 반려는_항목_코드와_서술이_이력_반려_행으로_남고_상태가_반려됨이_된다() throws Exception {
			long id = 축제를_신청한다();

			반려한다(id, rejectBody("[\"AREA\", \"INFO\"]", "신청 영역이 행사 실제 범위보다 넓습니다"))
				.andExpect(status().isOk());

			assertThat(저장된_신청(id).getStatus().name()).isEqualTo("REJECTED");
			// 행사 운영자 콘솔 상세가 코드 변경 없이 그대로 읽는다 (MSG-498 이 이력 최신 행을 본다).
			mockMvc.perform(get(ORG_URL + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(organizer)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("REJECTED"))
				.andExpect(jsonPath("$.data.rejection.reasonCodes[0]").value("AREA"))
				.andExpect(jsonPath("$.data.rejection.reasonCodes[1]").value("INFO"))
				.andExpect(jsonPath("$.data.rejection.reasonText")
					.value("신청 영역이 행사 실제 범위보다 넓습니다"));
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("항목 코드가 비었거나 허용 밖이거나 중복이면 13454 이고 상태는 그대로다")
		void 항목_코드가_비었거나_허용_밖이면_13454다() throws Exception {
			long id = 축제를_신청한다();

			for (String reasonCodes : List.of("[]", "[\"UNKNOWN\"]", "[\"AREA\", \"AREA\"]")) {
				반려한다(id, rejectBody(reasonCodes, "사유"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.developCode").value(13454));
			}
			assertThat(저장된_신청(id).getStatus().name()).isEqualTo("IN_REVIEW");
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("심사 중이 아닌 신청의 반려는 13450 이다")
		void 심사_중이_아닌_신청의_반려는_13450이다() throws Exception {
			long id = 축제를_신청한다();
			승인한다(id).andExpect(status().isOk());

			반려한다(id, rejectBody("[\"INFO\"]", "다시 봐야 합니다"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.developCode").value(13450));
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("사유 본문이 비면 공통 400 이다")
		void 사유_본문이_비면_공통_400이다() throws Exception {
			long id = 축제를_신청한다();

			반려한다(id, rejectBody("[\"INFO\"]", " "))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(400));
		}
	}
}
