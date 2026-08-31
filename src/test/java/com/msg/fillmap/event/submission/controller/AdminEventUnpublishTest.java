package com.msg.fillmap.event.submission.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.event.submission.EventSubmissionFixtures;
import com.msg.fillmap.event.submission.repository.EventSubmissionRepository;
import com.msg.fillmap.event.submission.service.AdminEventSubmissionService;
import com.msg.fillmap.global.mail.MailSender;
import com.msg.fillmap.mission.entity.Mission;
import com.msg.fillmap.mission.repository.MissionProgressProjection;
import com.msg.fillmap.mission.repository.MissionRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 승인 행사 노출 중지 (MSG-500 §API 6, 실 DB). 검증의 무게는 API 응답이 아니라 <b>중지 뒤에 무엇이
 * 사라지는가</b>에 있다 — 지도 칩 목록·격자 역조회·미션 상세·영상 피드·스탬프 후보 판정·미션 경유 업로드가
 * 전부 다른 쿼리라, 한 곳만 빠뜨리면 "목록에서는 사라졌는데 알던 id 로는 그대로 열리는" 반쪽 중지가 된다.
 *
 * <p>신청은 SQL 로 심는다 — 산출물 미션이 <b>지금 활성</b>이어야 "사라진다"를 관찰할 수 있어서 기간이
 * 오늘을 포함해야 한다(접수 폼의 고정 날짜로는 성립하지 않는다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("승인 행사 노출 중지 (MSG-500, 실 DB)")
class AdminEventUnpublishTest {

	private static final String UNPUBLISH_URL = "/api/admin/events/%d/unpublish";
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final int MIN_GRID_Y = 16859;
	private static final int MAX_GRID_Y = 16861;
	private static final int MIN_GRID_X = 11509;
	private static final int MAX_GRID_X = 11515;
	private static final String CENTER_GRID_ID = "16860_11512";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AdminEventSubmissionService adminEventSubmissionService;

	@Autowired
	private EventSubmissionRepository submissionRepository;

	@Autowired
	private MissionRepository missionRepository;

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
	private MailSender mailSender;

	private User organizer;
	private User admin;
	private long submissionId;
	private long missionId;

	@BeforeEach
	void setUp() {
		given(s3Client.copyObject(any(CopyObjectRequest.class))).willReturn(CopyObjectResponse.builder().build());
		organizer = saveUser(UserRole.ORG);
		admin = saveUser(UserRole.ADMIN);

		LocalDate today = LocalDate.now(KST);
		submissionId = EventSubmissionFixtures.seedInReviewSubmission(jdbcTemplate, organizer.getId(),
			"FM-2026-" + UUID.randomUUID().toString().substring(0, 8), today.minusDays(1), today.plusDays(1),
			MIN_GRID_Y, MAX_GRID_Y, MIN_GRID_X, MAX_GRID_X, CENTER_GRID_ID);
		adminEventSubmissionService.approve(submissionId);
		missionId = submissionRepository.findById(submissionId).orElseThrow().getPublishedMissionId();
	}

	private User saveUser(UserRole role) {
		User user = User.createLocalUser("unpublish-" + UUID.randomUUID() + "@fillmap.dev",
			passwordEncoder.encode("Initial1234"), "김담당");
		ReflectionTestUtils.setField(user, "role", role);
		return userRepository.saveAndFlush(user);
	}

	private String bearer(User user) {
		return "Bearer " + tokenProvider.issueAccessToken(user.getId(), user.getRole());
	}

	private ResultActions 중지한다(long targetSubmissionId, String reason) throws Exception {
		entityManager.flush();
		return mockMvc.perform(post(UNPUBLISH_URL.formatted(targetSubmissionId))
			.header(HttpHeaders.AUTHORIZATION, bearer(admin))
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"reason": "%s"}""".formatted(reason)));
	}

	private ResultActions 중지한다() throws Exception {
		return 중지한다(submissionId, "행사가 취소되어 노출을 중지합니다");
	}

	private boolean 활성_목록에_있다() {
		entityManager.flush();
		entityManager.clear();
		return missionRepository.findActive(LocalDateTime.now(java.time.ZoneOffset.UTC)).stream()
			.anyMatch(mission -> mission.getId() == missionId);
	}

	@Nested
	@DisplayName("사용자 대면 노출")
	class UserFacing {

		// 검증: FR-EVENT-18
		@Test
		@DisplayName("중지하면 미션이 활성 조회와 스탬프 후보 판정에서 사라진다")
		void 중지하면_미션이_활성_조회와_스탬프_판정에서_사라진다() throws Exception {
			assertThat(활성_목록에_있다()).isTrue();
			assertThat(missionRepository.findAwardCandidateIds(CENTER_GRID_ID, organizer.getId()))
				.contains(missionId);

			중지한다().andExpect(status().isOk());

			assertThat(활성_목록에_있다()).isFalse();
			// 스탬프 후보에서 빠진다 — 중지된 미션이 계속 지급되면 "사라진 미션의 뱃지"가 생긴다.
			assertThat(missionRepository.findAwardCandidateIds(CENTER_GRID_ID, organizer.getId()))
				.doesNotContain(missionId);
		}

		// 검증: FR-EVENT-18
		@Test
		@DisplayName("중지된 미션은 격자 선택 조회에서도 빠진다")
		void 중지된_미션은_격자_선택_조회에서도_빠진다() throws Exception {
			mockMvc.perform(get("/api/grids/" + CENTER_GRID_ID + "/missions"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[?(@.missionId == %d)]".formatted(missionId)).exists());

			중지한다().andExpect(status().isOk());

			entityManager.flush();
			entityManager.clear();
			mockMvc.perform(get("/api/grids/" + CENTER_GRID_ID + "/missions"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[?(@.missionId == %d)]".formatted(missionId)).doesNotExist());
		}

		// 검증: FR-EVENT-18
		@Test
		@DisplayName("중지된 미션의 상세와 영상 피드는 없는 미션과 같은 404 다")
		void 중지된_미션의_상세와_영상_피드는_404다() throws Exception {
			mockMvc.perform(get("/api/missions/" + missionId)).andExpect(status().isOk());
			mockMvc.perform(get("/api/missions/" + missionId + "/videos")).andExpect(status().isOk());

			중지한다().andExpect(status().isOk());

			entityManager.flush();
			entityManager.clear();
			mockMvc.perform(get("/api/missions/" + missionId))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.developCode").value(12404));
			// 빈 200 이 아니라 404 다 — 빈 페이지면 "존재하지만 비어 있다"로 읽혀 은닉이 갈라진다.
			mockMvc.perform(get("/api/missions/" + missionId + "/videos"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.developCode").value(12404));
		}

		// 검증: FR-EVENT-18
		@Test
		@DisplayName("중지해도 진행도 조회는 남는다 — 기록은 회수하지 않는다")
		void 이미_완료한_사용자의_진행도는_중지_후에도_남는다() throws Exception {
			중지한다().andExpect(status().isOk());

			entityManager.flush();
			entityManager.clear();
			List<MissionProgressProjection> progress =
				missionRepository.findProgress(organizer.getId(), List.of(missionId));
			// 진행도에는 숨김 술어를 걸지 않는다 — 종료 미션도 진행도가 나오는 규칙(D11)과 같은 이유다.
			assertThat(progress).hasSize(1);
			assertThat(progress.getFirst().getMissionId()).isEqualTo(missionId);
		}
	}

	@Nested
	@DisplayName("중지 처리")
	class Unpublishing {

		// 검증: FR-EVENT-18
		@Test
		@DisplayName("중지 사유가 신청 계정의 공식 이메일로 발송된다")
		void 중지_사유가_공식_이메일로_발송된다() throws Exception {
			중지한다()
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.emailSent").value(true))
				.andExpect(jsonPath("$.data.unpublishedAt").exists());

			ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
			then(mailSender).should().send(org.mockito.ArgumentMatchers.eq(organizer.getEmail()), anyString(),
				body.capture());
			assertThat(body.getValue()).contains("행사가 취소되어 노출을 중지합니다");
		}

		// 검증: FR-EVENT-18
		@Test
		@DisplayName("발송이 실패해도 중지는 유지되고 emailSent 가 거짓이다")
		void 발송이_실패해도_중지는_유지된다() throws Exception {
			willThrow(new IllegalStateException("SES 장애")).given(mailSender)
				.send(anyString(), anyString(), anyString());

			중지한다()
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.emailSent").value(false));

			entityManager.clear();
			assertThat(submissionRepository.findById(submissionId).orElseThrow().getUnpublishedAt()).isNotNull();
			assertThat(활성_목록에_있다()).isFalse();
		}

		// 검증: FR-EVENT-18
		@Test
		@DisplayName("이미 중지된 행사의 재중지는 13453 이고 첫 중지 사유가 유지된다")
		void 이미_중지된_행사의_재중지는_13453이다() throws Exception {
			중지한다().andExpect(status().isOk());

			중지한다(submissionId, "두 번째 사유")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.developCode").value(13453));

			entityManager.clear();
			assertThat(submissionRepository.findById(submissionId).orElseThrow().getUnpublishReason())
				.isEqualTo("행사가 취소되어 노출을 중지합니다");
		}

		// 검증: FR-EVENT-18
		@Test
		@DisplayName("승인되지 않은 신청과 없는 id 의 중지는 둘 다 13430 이다")
		void 미승인_신청_id의_중지는_13430이다() throws Exception {
			LocalDate today = LocalDate.now(KST);
			long inReviewId = EventSubmissionFixtures.seedInReviewSubmission(jdbcTemplate, organizer.getId(),
				"FM-2026-" + UUID.randomUUID().toString().substring(0, 8), today, today.plusDays(1),
				MIN_GRID_Y, MAX_GRID_Y, MIN_GRID_X, MAX_GRID_X, CENTER_GRID_ID);

			중지한다(inReviewId, "사유")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.developCode").value(13430));
			중지한다(99_999_999L, "사유")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.developCode").value(13430));
		}

		// 검증: FR-EVENT-18
		@Test
		@DisplayName("중지된 뒤에도 미션 행은 남는다 — 삭제가 아니라 숨김이다")
		void 중지는_삭제가_아니라_숨김이다() throws Exception {
			중지한다().andExpect(status().isOk());

			entityManager.flush();
			entityManager.clear();
			Mission hidden = missionRepository.findById(missionId).orElseThrow();
			assertThat(hidden.getHiddenAt()).isNotNull();
			assertThat(hidden.getTitle()).isEqualTo("광안리 불꽃축제");
		}
	}
}
