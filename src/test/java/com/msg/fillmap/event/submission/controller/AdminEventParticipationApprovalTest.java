package com.msg.fillmap.event.submission.controller;

import static com.msg.fillmap.event.submission.EventSubmissionFixtures.eventBody;
import static com.msg.fillmap.event.submission.EventSubmissionFixtures.location;
import static com.msg.fillmap.event.submission.EventSubmissionFixtures.rect;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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
import com.msg.fillmap.event.EventTestFixtures;
import com.msg.fillmap.event.entity.EventLocation;
import com.msg.fillmap.event.entity.EventLocationType;
import com.msg.fillmap.event.entity.EventOccurrence;
import com.msg.fillmap.event.repository.EventLocationGridRepository;
import com.msg.fillmap.event.repository.EventLocationRepository;
import com.msg.fillmap.event.repository.EventOccurrenceRepository;
import com.msg.fillmap.event.repository.EventSeriesRepository;
import com.msg.fillmap.event.submission.repository.EventSubmissionRepository;
import com.msg.fillmap.global.mail.MailSender;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;

/**
 * 이벤트 참여형 승인과 그 중지 (MSG-500 D-8·D-9·D-3, 실 DB). 미션 경로와 달리 산출물이 <b>부모 회차 아래
 * 행사 위치</b>라, 검증 대상이 위치·격자 삽입, 참여 속성 복사, 노출 영역 확장·축소, 회차 내 격자 단일
 * 귀속(지연 제약)과의 관계처럼 전부 DB 동작이다.
 *
 * <p>실입력은 <b>대표 위치 한 곳</b>이다 (PRD v2.2 FR-8 — 접수가 2곳 이상을 13439 로 막는다). 구현은 위치
 * N개에서도 성립하게 짜여 있어, 그 일반성(이름 순번·위치 상호 겹침)은 접수를 우회해 SQL 로 심은 신청으로
 * 확인한다 — 승인 경로만 태우고 접수 정책을 흔들지 않는다.
 *
 * <p>부모 회차는 서해 먼바다 격자에 만든다(EventTestFixtures 규약) — 공유 로컬 DB 의 육상 실데이터와
 * 겹치지 않아야 회차 내 격자 단일 귀속이 남의 데이터로 깨지지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("이벤트 참여형 승인·중지 (MSG-500, 실 DB)")
class AdminEventParticipationApprovalTest {

	private static final String ADMIN_URL = "/api/admin/event-submissions";
	private static final String ORG_URL = "/api/org/event-submissions";

	/** 서해 먼바다 — 육상 실데이터와 겹치지 않는 자리다. 부모 회차와 신청 위치가 이 근방을 쓴다. */
	private static final int SEA_GRID_Y = 19000;
	private static final int SEA_GRID_X = 5000;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EventSubmissionRepository submissionRepository;

	@Autowired
	private EventSeriesRepository seriesRepository;

	@Autowired
	private EventOccurrenceRepository occurrenceRepository;

	@Autowired
	private EventLocationRepository locationRepository;

	@Autowired
	private EventLocationGridRepository locationGridRepository;

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

	@MockitoBean
	private MailSender mailSender;

	private EventTestFixtures fixtures;
	private User organizer;
	private User admin;
	private EventOccurrence parent;

	@BeforeEach
	void setUp() {
		fixtures = new EventTestFixtures(seriesRepository, occurrenceRepository, locationRepository,
			locationGridRepository);
		organizer = saveUser(UserRole.ORG);
		admin = saveUser(UserRole.ADMIN);
		given(s3Client.headObject(any(HeadObjectRequest.class)))
			.willReturn(HeadObjectResponse.builder().contentLength(2048L).build());
		given(s3Client.copyObject(any(CopyObjectRequest.class))).willReturn(CopyObjectResponse.builder().build());
		given(thumbnailUrlPresigner.presign(anyString())).willReturn("https://signed.example/image.jpg");

		LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
		// 부모 회차는 신청 기간(오늘+30~39일)을 품도록 넉넉히 잡는다 — 끝난 회차는 승인이 13451 로 막힌다.
		parent = fixtures.회차(fixtures.시리즈(), now.minusDays(1), now.plusDays(60), gridId(0, 0));
	}

	private User saveUser(UserRole role) {
		User user = User.createLocalUser("part-" + UUID.randomUUID() + "@fillmap.dev",
			passwordEncoder.encode("Initial1234"), "김담당");
		ReflectionTestUtils.setField(user, "role", role);
		return userRepository.saveAndFlush(user);
	}

	private static String gridId(int dy, int dx) {
		return (SEA_GRID_Y + dy) + "_" + (SEA_GRID_X + dx);
	}

	private String bearer(User user) {
		return "Bearer " + tokenProvider.issueAccessToken(user.getId(), user.getRole());
	}

	/** 참여형 신청 1건 — 접수 API 를 그대로 탄다(대표 위치 한 곳, v2.2 FR-8). */
	private long 참여를_신청한다(int dy, int dx) throws Exception {
		String body = eventBody(organizer.getId(), parent.getId(),
			location(rect(SEA_GRID_Y + dy, SEA_GRID_Y + dy, SEA_GRID_X + dx, SEA_GRID_X + dx)));
		String response = mockMvc.perform(post(ORG_URL)
				.header(HttpHeaders.AUTHORIZATION, bearer(organizer))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(response, "$.data.id")).longValue();
	}

	private ResultActions 승인한다(long submissionId) throws Exception {
		entityManager.flush();
		return mockMvc.perform(post(ADMIN_URL + "/" + submissionId + "/approve")
			.header(HttpHeaders.AUTHORIZATION, bearer(admin)));
	}

	private ResultActions 중지한다(long submissionId) throws Exception {
		entityManager.flush();
		return mockMvc.perform(post("/api/admin/events/" + submissionId + "/unpublish")
			.header(HttpHeaders.AUTHORIZATION, bearer(admin))
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"reason": "행사가 취소되어 노출을 중지합니다"}"""));
	}

	private List<EventLocation> 승인된_위치들(long submissionId) {
		entityManager.flush();
		entityManager.clear();
		String submissionNo = submissionRepository.findById(submissionId).orElseThrow().getSubmissionNo();
		return locationRepository.findByLocationKeyStartingWith("sub-%s-".formatted(submissionNo));
	}

	private EventOccurrence 저장된_회차() {
		entityManager.flush();
		entityManager.clear();
		return occurrenceRepository.findById(parent.getId()).orElseThrow();
	}

	/**
	 * 접수를 우회해 위치 두 곳짜리 참여형 신청을 심는다. 실입력에는 없는 형태이지만(v2.2 FR-8 이 한 곳으로
	 * 제한한다) 승인 구현은 N개에서도 성립해야 하므로, 이름 순번과 위치 상호 겹침은 이 경로로만 확인된다.
	 */
	private long 두_위치_신청을_심는다(String title, int firstDx, int secondDx) {
		entityManager.flush();
		String submissionNo = "FM-2026-" + UUID.randomUUID().toString().substring(0, 8);
		LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
		jdbcTemplate.update("""
			INSERT INTO event_submissions
				(submission_no, user_id, type, status, title, organizer_name, starts_on, ends_on,
				 participation_method, description, image_key, created_at, updated_at,
				 parent_event_occurrence_id)
			VALUES (?, ?, 'EVENT', 'IN_REVIEW', ?, '필맵 주식회사', ?, ?,
				'현장에서 스탬프를 찍고 굿즈를 받는 방식입니다', '이벤트 참여형 부스를 운영합니다',
				'event-submissions/original/1/a.jpg', ?, ?, ?)
			""", submissionNo, organizer.getId(), title, LocalDate.now().plusDays(1), LocalDate.now().plusDays(9),
			now, now, parent.getId());
		Long submissionId = jdbcTemplate.queryForObject(
			"SELECT id FROM event_submissions WHERE submission_no = ?", Long.class, submissionNo);

		int order = 1;
		for (int dx : new int[] {firstDx, secondDx}) {
			jdbcTemplate.update("""
				INSERT INTO event_submission_locations (event_submission_id, display_order, representative_grid_id)
				VALUES (?, ?, ?)
				""", submissionId, order++, gridId(0, dx));
			Long locationId = jdbcTemplate.queryForObject(
				"SELECT MAX(id) FROM event_submission_locations WHERE event_submission_id = ?",
				Long.class, submissionId);
			jdbcTemplate.update("""
				INSERT INTO event_submission_location_rects
					(event_submission_location_id, min_grid_y, max_grid_y, min_grid_x, max_grid_x)
				VALUES (?, ?, ?, ?, ?)
				""", locationId, SEA_GRID_Y, SEA_GRID_Y, SEA_GRID_X + dx, SEA_GRID_X + dx);
		}
		entityManager.clear();
		return submissionId;
	}

	@Nested
	@DisplayName("승인 — 참여형 전개")
	class Approve {

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("승인하면 부모 회차 아래 위치와 격자가 생기고 회차 노출 영역이 넓어진다")
		void 참여형_신청을_승인하면_부모_회차_아래_위치와_격자가_생기고_회차_노출_영역이_넓어진다() throws Exception {
			int 기존_최대열 = parent.getMaxGridX();
			long id = 참여를_신청한다(0, 50);

			승인한다(id).andExpect(status().isOk());

			List<EventLocation> created = 승인된_위치들(id);
			assertThat(created).singleElement().satisfies(location -> {
				assertThat(location.getOccurrence().getId()).isEqualTo(parent.getId());
				assertThat(location.getType()).isEqualTo(EventLocationType.ETC);
				assertThat(location.getRepresentativeGridId()).isEqualTo(gridId(0, 50));
				assertThat(location.getOperatingHours()).isNull();
				assertThat(location.getHiddenAt()).isNull();
				assertThat(locationGridRepository.findByIdEventLocationId(location.getId()))
					.extracting(grid -> grid.getGridId())
					.containsExactly(gridId(0, 50));
			});
			// 노출 영역이 새 위치를 품도록 넓어진다 — 안 넓어지면 그 뷰포트에서 부모 행사가 안 보인다.
			assertThat(저장된_회차().getMaxGridX()).isEqualTo(Math.max(기존_최대열, SEA_GRID_X + 50));
			// 승인 산출물이 미션이 아니라 위치라 신청 행의 미션 링크는 비어 있다.
			assertThat(submissionRepository.findById(id).orElseThrow().getPublishedMissionId()).isNull();
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("참여 속성이 신청 행에서 위치 행으로 복사되어 위치 목록 응답에 실린다")
		void 참여_속성이_신청_행에서_위치_행으로_복사되어_위치_목록_응답에_실린다() throws Exception {
			long id = 참여를_신청한다(0, 51);
			승인한다(id).andExpect(status().isOk());

			EventLocation created = 승인된_위치들(id).getFirst();
			assertThat(created.getOrganizerName()).isEqualTo("필맵 파트너스");
			assertThat(created.getParticipationMethod()).isNotBlank();
			assertThat(created.getStartsOn()).isNotNull();
			assertThat(created.getEndsOn()).isNotNull();
			assertThat(created.getImageKey()).startsWith("event-locations/org-submission/");

			mockMvc.perform(get("/api/event-occurrences/{id}/locations", parent.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[?(@.locationId == %d)].organizerName".formatted(created.getId()))
					.value("필맵 파트너스"))
				.andExpect(jsonPath("$.data[?(@.locationId == %d)].participationMethod".formatted(created.getId()))
					.exists())
				// 키가 아니라 조립된 공개 주소가 나간다 — 화면이 그대로 <img> 에 넣는 값이다.
				.andExpect(jsonPath("$.data[?(@.locationId == %d)].imageUrl".formatted(created.getId()))
					.value(org.hamcrest.Matchers.hasItem(
						org.hamcrest.Matchers.containsString("event-locations/org-submission/"))));
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("시드 위치의 참여 속성 필드는 전부 null 이다 — 기존 화면이 불변이다")
		void 시드_위치의_참여_속성_필드는_전부_null이다() throws Exception {
			EventLocation seeded = fixtures.위치(parent, "시드 위치", gridId(0, 90));

			mockMvc.perform(get("/api/event-occurrences/{id}/locations", parent.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[?(@.locationId == %d)].organizerName".formatted(seeded.getId()))
					.value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())))
				.andExpect(jsonPath("$.data[?(@.locationId == %d)].imageUrl".formatted(seeded.getId()))
					.value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())))
				.andExpect(jsonPath("$.data[?(@.locationId == %d)].participationStartsOn".formatted(seeded.getId()))
					.value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())));
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("심사 상세가 참여 방식과 부모 이벤트를 준다 — 무엇에 실리는지 보고 승인한다")
		void 심사_상세는_참여_방식과_부모_이벤트를_준다() throws Exception {
			long id = 참여를_신청한다(0, 53);

			mockMvc.perform(get(ADMIN_URL + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.type").value("EVENT"))
				.andExpect(jsonPath("$.data.participationMethod").isNotEmpty())
				// 부모를 특정할 재료가 없으면 관리자가 어느 이벤트에 실리는지 모르는 채 승인하게 된다.
				.andExpect(jsonPath("$.data.parentEvent.occurrenceId").value(parent.getId()))
				.andExpect(jsonPath("$.data.parentEvent.name").value(parent.getTitle()));
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("노출 영역 확장은 일정 개정 번호를 올리지 않는다 — 변경 알림이 나가지 않는다")
		void 노출_영역_확장은_일정_개정_번호를_올리지_않는다() throws Exception {
			int 기존_개정 = 저장된_회차().getScheduleRevision();
			long id = 참여를_신청한다(0, 52);

			승인한다(id).andExpect(status().isOk());

			assertThat(저장된_회차().getScheduleRevision()).isEqualTo(기존_개정);
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("부모 회차의 기존 위치와 격자가 겹치면 13452 로 거부된다")
		void 부모_회차의_기존_위치와_격자가_겹치면_13452로_거부된다() throws Exception {
			fixtures.위치(parent, "기존 위치", gridId(0, 60));
			long id = 참여를_신청한다(0, 60);

			승인한다(id)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.developCode").value(13452));

			// 삽입 전에 막혔다 — 지연 제약이 커밋 때 터지는 500 이 아니라 읽히는 409 다.
			// 승인 흔적(전이·승인 번호)의 원복은 여기서 관찰할 수 없다: 롤백 격리 클래스라 서비스 트랜잭션이
			// 테스트 트랜잭션에 합류해 진짜 롤백이 일어나지 않는다(M3 승인 원자성 테스트와 같은 제약).
			assertThat(승인된_위치들(id)).isEmpty();
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("같은 신청의 신규 위치끼리 격자가 겹쳐도 삽입 전 13452 로 거부된다")
		void 같은_신청의_신규_위치끼리_격자가_겹쳐도_삽입_전_13452로_거부된다() throws Exception {
			// 접수 검증은 위치를 독립적으로 보므로 위치 간 겹침은 심사까지 통과해 온다 — 그 자리를 막는다.
			long id = 두_위치_신청을_심는다("참여형 부스", 70, 70);

			승인한다(id)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.developCode").value(13452));

			assertThat(승인된_위치들(id)).isEmpty();
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("위치가 여럿이면 이름이 순번 접미사를 포함해 100자 안으로 잘린다")
		void 위치가_여럿이면_이름이_순번_접미사를_포함해_100자_안으로_잘린다() throws Exception {
			String 백자_제목 = "가".repeat(100);
			long id = 두_위치_신청을_심는다(백자_제목, 80, 81);

			승인한다(id).andExpect(status().isOk());

			List<EventLocation> created = 승인된_위치들(id);
			assertThat(created).hasSize(2);
			assertThat(created).allSatisfy(location -> {
				assertThat(location.getName()).hasSizeLessThanOrEqualTo(100);
				assertThat(location.getName()).startsWith("가");
			});
			assertThat(created).extracting(EventLocation::getName)
				.containsExactlyInAnyOrder(백자_제목.substring(0, 98) + " 1", 백자_제목.substring(0, 98) + " 2");
		}

		// 검증: FR-EVENT-15
		@Test
		@DisplayName("종료된 부모 회차로는 승인할 수 없다 — 13451")
		void 종료된_부모_회차의_승인은_13451이다() throws Exception {
			long id = 참여를_신청한다(0, 55);
			// 심사 지연 사이에 부모가 끝난 상황 — 반영할 노출이 없다.
			entityManager.flush();
			jdbcTemplate.update("UPDATE event_occurrences SET ends_at = ? WHERE id = ?",
				LocalDateTime.now(ZoneOffset.UTC).minusDays(1), parent.getId());
			entityManager.clear();

			승인한다(id)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.developCode").value(13451));
			assertThat(승인된_위치들(id)).isEmpty();
		}
	}

	@Nested
	@DisplayName("중지 — 참여형 경로")
	class Unpublish {

		// 검증: FR-EVENT-18
		@Test
		@DisplayName("중지하면 참여형 위치가 회차 상세와 격자 조회에서 사라진다")
		void 중지하면_참여형_위치가_회차_상세와_격자_조회에서_사라진다() throws Exception {
			long id = 참여를_신청한다(0, 40);
			승인한다(id).andExpect(status().isOk());
			long locationId = 승인된_위치들(id).getFirst().getId();

			mockMvc.perform(get("/api/event-occurrences/{id}/locations", parent.getId()))
				.andExpect(jsonPath("$.data[?(@.locationId == %d)]".formatted(locationId)).exists());

			중지한다(id).andExpect(status().isOk());

			entityManager.flush();
			entityManager.clear();
			mockMvc.perform(get("/api/event-occurrences/{id}/locations", parent.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[?(@.locationId == %d)]".formatted(locationId)).doesNotExist());
			mockMvc.perform(get("/api/grids/{gridId}/event-locations", gridId(0, 40)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[?(@.locationId == %d)]".formatted(locationId)).doesNotExist());
			// 업로드 확인도 같은 404 로 막힌다(위치 자체가 없는 것과 구분되지 않는다).
			assertThat(locationRepository.findById(locationId).orElseThrow().getHiddenAt()).isNotNull();
		}

		// 검증: FR-EVENT-18
		@Test
		@DisplayName("중지하면 부모 회차 노출 영역이 남은 가시 위치 범위로 줄어든다")
		void 중지하면_부모_회차_노출_영역이_남은_가시_위치_범위로_줄어든다() throws Exception {
			fixtures.위치(parent, "남는 위치", gridId(0, 1));
			long id = 참여를_신청한다(0, 45);
			승인한다(id).andExpect(status().isOk());
			assertThat(저장된_회차().getMaxGridX()).isEqualTo(SEA_GRID_X + 45);
			int 기존_개정 = 저장된_회차().getScheduleRevision();

			중지한다(id).andExpect(status().isOk());

			// 남은 가시 위치(열 +1)만 감싸는 범위로 돌아온다 — 안 줄이면 숨긴 위치 뷰포트에서 계속 노출된다.
			assertThat(저장된_회차().getMaxGridX()).isEqualTo(SEA_GRID_X + 1);
			assertThat(저장된_회차().getScheduleRevision()).isEqualTo(기존_개정);
		}

		// 검증: FR-EVENT-18
		@Test
		@DisplayName("중지된 참여형 위치의 격자는 남아 있어 같은 칸의 재승인이 13452 로 막힌다")
		void 중지된_위치의_격자는_남아_회차_내_단일_귀속을_지킨다() throws Exception {
			long first = 참여를_신청한다(0, 46);
			승인한다(first).andExpect(status().isOk());
			중지한다(first).andExpect(status().isOk());

			long second = 참여를_신청한다(0, 46);

			// 숨긴 위치의 격자도 uq_event_grid_per_occ 에 그대로 남아 있다 — 겹침 검사가 가시 격자만 보면
			// 여기서 통과하고 커밋 시점 500 이 된다.
			승인한다(second)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.developCode").value(13452));
		}
	}
}
