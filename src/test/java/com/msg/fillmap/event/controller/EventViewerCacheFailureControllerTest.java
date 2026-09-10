package com.msg.fillmap.event.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 캐시 장애 격리 통합 테스트 (MSG-443 D3, FR-19) — 죽은 포트(6390) Redis 템플릿을 주입해 열람 집계
 * 호출이 실제 연결 예외를 던지게 하고, 필터체인 전체를 거쳐 heartbeat 는 200·조회는 viewerCount null 을
 * 확인한다. 두 경로 모두 비로그인이라 토큰 블랙리스트 조회(별도 Redis 의존)는 거치지 않는다 — 스펙의
 * "격리 범위의 한정"이 말하는 그 범위만 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("EventViewerController 캐시 장애 격리")
class EventViewerCacheFailureControllerTest {

	private static final long OCCURRENCE_ID = 999_000_301L;
	private static final String SESSION_HEADER = "X-Viewer-Session";

	@Autowired
	private MockMvc mockMvc;

	@TestConfiguration
	static class DeadRedisConfig {

		/** 아무도 듣지 않는 포트 — 첫 명령에서 연결 예외를 던진다 (HotScoreCommandServiceImplTest 방식). */
		@Bean
		@Primary
		StringRedisTemplate deadStringRedisTemplate() {
			LettuceConnectionFactory deadFactory = new LettuceConnectionFactory("localhost", 6390);
			deadFactory.afterPropertiesSet();
			return new StringRedisTemplate(deadFactory);
		}
	}

	// 검증: FR-EVENT-11
	@Test
	void 캐시_예외_시_열람_인원은_null이고_예외가_전파되지_않는다() throws Exception {
		mockMvc.perform(get("/api/event-occurrences/{occurrenceId}/viewer-count", OCCURRENCE_ID))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.viewerCount").isEmpty());
	}

	@Test
	void heartbeat_캐시_예외는_삼켜지고_응답은_200이다() throws Exception {
		mockMvc.perform(post("/api/event-occurrences/{occurrenceId}/heartbeat", OCCURRENCE_ID)
				.header(SESSION_HEADER, "cache-down-session"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200));
	}
}
