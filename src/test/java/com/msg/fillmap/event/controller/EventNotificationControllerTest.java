package com.msg.fillmap.event.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.event.entity.EventOccurrence;
import com.msg.fillmap.event.entity.EventSeries;
import com.msg.fillmap.event.repository.EventOccurrenceRepository;
import com.msg.fillmap.event.repository.EventSeriesRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 행사 알림 구독 API (MSG-442) — 실제 SecurityFilterChain 경유. 조회 세 경로의 permitAll 은 GET 한정이라
 * 이 PUT 이 인증 대상으로 남았는지가 배선 검증의 핵심이고, 필터체인을 타지 않으면 확인되지 않는다
 * (EventPublicAccessHttpTest 와 짝).
 * <p>
 * 격리(공유 로컬 DB): 합성 자연키(msg442-*)로 커밋해 쓰고 {@code @AfterEach} 에서 지운다 — MockMvc 요청이
 * 별도 트랜잭션이라 롤백 격리가 안 통한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("EventNotificationController")
class EventNotificationControllerTest {

	private static final String URL = "/api/event-occurrences/{occurrenceId}/notification";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@Autowired
	private EventSeriesRepository seriesRepository;

	@Autowired
	private EventOccurrenceRepository occurrenceRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@Autowired
	private PlatformTransactionManager txManager;

	private TransactionTemplate tx;
	private long userId;
	private long seriesId;
	private long occurrenceId;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(txManager);
		tx.executeWithoutResult(status -> {
			userId = userRepository.save(User.createLocalUser(
				"msg442-api-" + UUID.randomUUID() + "@example.com", "hash", "테스터")).getId();
			EventSeries series = seriesRepository.save(
				new EventSeries("msg442-api-series-" + 짧은키(), "합성 시리즈"));
			seriesId = series.getId();
			// 지금 진행 중인 회차 — 실 Clock 을 쓰는 프로덕션 빈이 LIVE 로 판정하도록 창을 넉넉히 잡는다
			LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
			EventOccurrence occurrence = new EventOccurrence(series, "msg442-api-occ-" + 짧은키());
			occurrence.update(series, "합성 회차", "부산", now.minusDays(1), now.plusDays(30),
				90000, 90001, 90500, 90501);
			occurrenceId = occurrenceRepository.save(occurrence).getId();
		});
	}

	/** 자연키 컬럼 폭(series_key 50자)에 맞춘 합성 접미사. */
	private String 짧은키() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	@AfterEach
	void tearDown() {
		tx.executeWithoutResult(status -> {
			em.createNativeQuery("DELETE FROM users WHERE id = :id").setParameter("id", userId).executeUpdate();
			em.createNativeQuery("DELETE FROM event_occurrences WHERE event_series_id = :id")
				.setParameter("id", seriesId).executeUpdate();
			em.createNativeQuery("DELETE FROM event_series WHERE id = :id").setParameter("id", seriesId)
				.executeUpdate();
		});
	}

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(userId, UserRole.USER);
	}

	// 검증: FR-EVENT-06
	@Test
	@DisplayName("구독 ON 요청은 enabled true 를 돌려주고 OFF 로 되돌릴 수 있다")
	void 구독_ON_요청은_enabled_true를_돌려준다() throws Exception {
		mockMvc.perform(put(URL, occurrenceId)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"enabled\":true}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.enabled").value(true));

		mockMvc.perform(put(URL, occurrenceId)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"enabled\":false}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.enabled").value(false));
	}

	// 검증: FR-EVENT-06
	@Test
	@DisplayName("비로그인 요청은 401 이다 — 행사 permitAll 은 GET 한정이라 쓰기가 열리지 않는다")
	void 비로그인_요청은_401이다() throws Exception {
		mockMvc.perform(put(URL, occurrenceId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"enabled\":true}"))
			.andExpect(status().isUnauthorized());
	}

	// 검증: FR-EVENT-06
	@Test
	@DisplayName("enabled 누락은 400 이다 — @NotNull 검증")
	void enabled_누락은_400이다() throws Exception {
		mockMvc.perform(put(URL, occurrenceId)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isBadRequest());
	}

	// 검증: FR-EVENT-06
	@Test
	@DisplayName("없는 회차는 404 + developCode 13404 다")
	void 없는_회차는_404다() throws Exception {
		mockMvc.perform(put(URL, -1L)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"enabled\":true}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.developCode").value(13404));
	}
}
