package com.msg.fillmap.event.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.user.entity.UserRole;

/**
 * 행사방 열람 인원 API 통합 테스트 (MSG-443) — 실제 SecurityFilterChain + 실제 Redis(localhost:6379).
 * 익명·로그인 판별은 필터체인 경유가 필수다: shouldNotFilter 오배선으로 principal 이 null 이 되는
 * D4 배선 결함은 컨트롤러 단위 테스트로 못 잡는다. 키는 테스트 전용 occurrenceId 대역으로 격리하고
 * 전후로 지운다 (공유 로컬 Redis).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("EventViewerController")
class EventViewerControllerTest {

	private static final long USER_ID = 42L;
	private static final long OCCURRENCE_ID = 999_000_101L;
	private static final String KEY = "eventviewer:" + OCCURRENCE_ID;
	private static final String HEARTBEAT_URL = "/api/event-occurrences/{occurrenceId}/heartbeat";
	private static final String VIEWER_COUNT_URL = "/api/event-occurrences/{occurrenceId}/viewer-count";
	private static final String SESSION_HEADER = "X-Viewer-Session";
	private static final String SESSION_ID = "11111111-2222-3333-4444-555555555555";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@BeforeEach
	void setUp() {
		redisTemplate.delete(List.of(KEY));
	}

	@AfterEach
	void tearDown() {
		redisTemplate.delete(List.of(KEY));
	}

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	@Nested
	@DisplayName("세션 식별 (D4)")
	class 세션_식별 {

		// 검증: FR-EVENT-11
		@Test
		void 비로그인_세션도_헤더_식별자로_집계된다() throws Exception {
			mockMvc.perform(post(HEARTBEAT_URL, OCCURRENCE_ID).header(SESSION_HEADER, SESSION_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200));

			assertThat(redisTemplate.opsForZSet().score(KEY, "s:" + SESSION_ID)).isNotNull();

			mockMvc.perform(get(VIEWER_COUNT_URL, OCCURRENCE_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.viewerCount").value(1));
		}

		@Test
		void 같은_사용자는_로그인_상태면_헤더가_있어도_userId로_센다() throws Exception {
			// 탭마다 헤더 세션 ID 가 달라도 principal 우선이라 u:{userId} 하나로 수렴해야 한다
			mockMvc.perform(post(HEARTBEAT_URL, OCCURRENCE_ID)
					.header(HttpHeaders.AUTHORIZATION, bearer())
					.header(SESSION_HEADER, "tab-one-session"))
				.andExpect(status().isOk());
			mockMvc.perform(post(HEARTBEAT_URL, OCCURRENCE_ID)
					.header(HttpHeaders.AUTHORIZATION, bearer())
					.header(SESSION_HEADER, "tab-two-session"))
				.andExpect(status().isOk());

			assertThat(redisTemplate.opsForZSet().score(KEY, "u:" + USER_ID)).isNotNull();
			assertThat(redisTemplate.opsForZSet().size(KEY)).isEqualTo(1);

			mockMvc.perform(get(VIEWER_COUNT_URL, OCCURRENCE_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.viewerCount").value(1));
		}
	}

	@Nested
	@DisplayName("API 경계")
	class API_경계 {

		@Test
		void heartbeat는_본문_없이_200이다() throws Exception {
			mockMvc.perform(post(HEARTBEAT_URL, OCCURRENCE_ID).header(SESSION_HEADER, SESSION_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200))
				.andExpect(jsonPath("$.data").doesNotExist());
		}

		@Test
		void 비로그인인데_세션_헤더가_없으면_400이다() throws Exception {
			mockMvc.perform(post(HEARTBEAT_URL, OCCURRENCE_ID))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(400));
		}

		@Test
		void 빈_세션_헤더는_400이다() throws Exception {
			mockMvc.perform(post(HEARTBEAT_URL, OCCURRENCE_ID).header(SESSION_HEADER, "   "))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(400));
		}

		@Test
		void 육십사자를_넘는_세션_헤더는_400이다() throws Exception {
			mockMvc.perform(post(HEARTBEAT_URL, OCCURRENCE_ID).header(SESSION_HEADER, "a".repeat(65)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.developCode").value(400));
		}

		@Test
		void 없는_회차_id로_보낸_heartbeat도_200이다() throws Exception {
			// 존재 검증 없음(MSG-438 후속) — 키 하나가 생겼다 TTL 120초 뒤 소멸할 뿐이라 무해하다
			long unknownOccurrenceId = 999_000_199L;
			try {
				mockMvc.perform(post(HEARTBEAT_URL, unknownOccurrenceId).header(SESSION_HEADER, SESSION_ID))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.developCode").value(200));
			} finally {
				redisTemplate.delete("eventviewer:" + unknownOccurrenceId);
			}
		}

		@Test
		void viewer_count_응답은_viewerCount_필드_하나다() throws Exception {
			mockMvc.perform(post(HEARTBEAT_URL, OCCURRENCE_ID).header(SESSION_HEADER, SESSION_ID))
				.andExpect(status().isOk());

			mockMvc.perform(get(VIEWER_COUNT_URL, OCCURRENCE_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200))
				.andExpect(jsonPath("$.data.viewerCount").value(1))
				.andExpect(jsonPath("$.data.length()").value(1));
		}
	}
}
