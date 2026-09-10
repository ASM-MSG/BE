package com.msg.fillmap.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 실제 Redis(localhost:6379)를 사용하는 집계 계층 테스트 (MSG-443) — 로컬은 fillmap-local-redis,
 * CI 는 redis 서비스 컨테이너 (HotScoreCommandServiceImplTest 방식). 시각은 이동 가능한 Clock 주입으로
 * 90초 경계를 결정적으로 재현하고, 키는 테스트 전용 occurrenceId 대역(999_000_00x)으로 격리한다.
 */
@DisplayName("EventViewerServiceImpl")
class EventViewerServiceImplTest {

	private static final Instant FIXED_INSTANT = Instant.parse("2000-01-01T00:00:00Z");
	private static final long OCCURRENCE_ID = 999_000_001L;
	private static final long OTHER_OCCURRENCE_ID = 999_000_002L;
	private static final String KEY = "eventviewer:" + OCCURRENCE_ID;
	private static final String OTHER_KEY = "eventviewer:" + OTHER_OCCURRENCE_ID;
	private static final String USER_KEY = "u:42";
	private static final String ANON_KEY = "s:11111111-2222-3333-4444-555555555555";

	private static LettuceConnectionFactory connectionFactory;
	private static StringRedisTemplate redisTemplate;

	private AtomicReference<Instant> now;
	private EventViewerServiceImpl service;

	@BeforeAll
	static void beforeAll() {
		connectionFactory = new LettuceConnectionFactory("localhost", 6379);
		connectionFactory.afterPropertiesSet();
		redisTemplate = new StringRedisTemplate(connectionFactory);
		redisTemplate.afterPropertiesSet();
	}

	@BeforeEach
	void setUp() {
		// 공유 로컬 Redis — 이전 실행 잔재까지 지우고 시작한다
		redisTemplate.delete(List.of(KEY, OTHER_KEY));
		now = new AtomicReference<>(FIXED_INSTANT);
		service = new EventViewerServiceImpl(redisTemplate, movableClock(now));
	}

	@AfterEach
	void tearDown() {
		redisTemplate.delete(List.of(KEY, OTHER_KEY));
	}

	@AfterAll
	static void afterAll() {
		connectionFactory.destroy();
	}

	@Nested
	@DisplayName("집계 판정")
	class 집계_판정 {

		// 검증: FR-EVENT-11
		@Test
		void heartbeat를_보낸_세션이_열람_인원에_센다() {
			service.recordHeartbeat(OCCURRENCE_ID, USER_KEY);

			assertThat(service.getViewerCount(OCCURRENCE_ID)).isEqualTo(1);
		}

		@Test
		void 마지막_신호가_90초를_넘긴_세션은_집계에서_빠진다() {
			service.recordHeartbeat(OCCURRENCE_ID, USER_KEY);

			now.set(FIXED_INSTANT.plusSeconds(91));

			assertThat(service.getViewerCount(OCCURRENCE_ID)).isEqualTo(0);
		}

		@Test
		void 경계_90초는_포함이다() {
			service.recordHeartbeat(OCCURRENCE_ID, USER_KEY);

			now.set(FIXED_INSTANT.plusSeconds(90));

			assertThat(service.getViewerCount(OCCURRENCE_ID)).isEqualTo(1);
		}

		@Test
		void 같은_사용자의_두_탭은_1명으로_센다() {
			service.recordHeartbeat(OCCURRENCE_ID, USER_KEY);
			service.recordHeartbeat(OCCURRENCE_ID, USER_KEY);

			assertThat(service.getViewerCount(OCCURRENCE_ID)).isEqualTo(1);
		}

		@Test
		void 비로그인_세션도_식별자로_집계된다() {
			service.recordHeartbeat(OCCURRENCE_ID, ANON_KEY);

			assertThat(service.getViewerCount(OCCURRENCE_ID)).isEqualTo(1);
			assertThat(redisTemplate.opsForZSet().score(KEY, ANON_KEY)).isNotNull();
		}

		@Test
		void 재전송은_같은_사용자의_만료_시각을_연장한다() {
			service.recordHeartbeat(OCCURRENCE_ID, USER_KEY);

			now.set(FIXED_INSTANT.plusSeconds(60));
			service.recordHeartbeat(OCCURRENCE_ID, USER_KEY);

			now.set(FIXED_INSTANT.plusSeconds(120));   // 첫 신호 기준 90초 초과, 재전송 기준 60초

			assertThat(service.getViewerCount(OCCURRENCE_ID)).isEqualTo(1);
		}

		@Test
		void 늦게_도착한_과거_heartbeat는_최신_score를_되돌리지_않는다() {
			Instant t2 = FIXED_INSTANT.plusSeconds(50);
			now.set(t2);
			service.recordHeartbeat(OCCURRENCE_ID, USER_KEY);

			now.set(FIXED_INSTANT);   // T1 < T2 — 네트워크에서 지연된 옛 heartbeat 가 늦게 도착
			service.recordHeartbeat(OCCURRENCE_ID, USER_KEY);

			now.set(t2.plusSeconds(90));   // T2 기준 활성 창 끝자락 — GT 가 빠지면 score 가 T1 이라 0 이 된다

			assertThat(service.getViewerCount(OCCURRENCE_ID)).isEqualTo(1);
		}

		@Test
		void occurrenceId가_다르면_집계가_섞이지_않는다() {
			service.recordHeartbeat(OCCURRENCE_ID, USER_KEY);

			assertThat(service.getViewerCount(OCCURRENCE_ID)).isEqualTo(1);
			assertThat(service.getViewerCount(OTHER_OCCURRENCE_ID)).isEqualTo(0);
		}

		@Test
		void 아무도_없는_회차의_열람_인원은_0이다() {
			assertThat(service.getViewerCount(OCCURRENCE_ID)).isEqualTo(0);
		}

		@Test
		void heartbeat는_만료된_멤버를_청소한다() {
			service.recordHeartbeat(OCCURRENCE_ID, ANON_KEY);

			now.set(FIXED_INSTANT.plusSeconds(91));   // ANON_KEY 는 딱 90초를 넘겨 만료
			service.recordHeartbeat(OCCURRENCE_ID, USER_KEY);

			// 키 TTL 로만 지워지면 heartbeat 가 계속 오는 방에 하루치 멤버가 쌓인다 (D2)
			assertThat(redisTemplate.opsForZSet().score(KEY, ANON_KEY)).isNull();
			assertThat(redisTemplate.opsForZSet().size(KEY)).isEqualTo(1);
		}

		@Test
		void 방이_조용해지면_키가_TTL로_소멸한다() {
			service.recordHeartbeat(OCCURRENCE_ID, USER_KEY);

			Long expireSeconds = redisTemplate.getExpire(KEY);

			assertThat(expireSeconds).isBetween(115L, 120L);
		}
	}

	@Nested
	@DisplayName("장애 격리 (FR-19)")
	class 장애_격리 {

		// 검증: FR-EVENT-11
		@Test
		void 캐시_예외_시_열람_인원은_null이고_예외가_전파되지_않는다() {
			EventViewerServiceImpl deadService = deadRedisService();

			AtomicReference<Integer> result = new AtomicReference<>();
			assertThatCode(() -> result.set(deadService.getViewerCount(OCCURRENCE_ID)))
				.doesNotThrowAnyException();

			assertThat(result.get()).isNull();
		}

		@Test
		void heartbeat_캐시_예외는_삼켜지고_전파되지_않는다() {
			EventViewerServiceImpl deadService = deadRedisService();

			assertThatCode(() -> deadService.recordHeartbeat(OCCURRENCE_ID, USER_KEY))
				.doesNotThrowAnyException();
		}

		/** 죽은 포트(6390) 템플릿 — 연결 실패 예외를 실제로 던지게 한다 (핫구역 테스트 방식). */
		private EventViewerServiceImpl deadRedisService() {
			LettuceConnectionFactory deadFactory = new LettuceConnectionFactory("localhost", 6390);
			deadFactory.afterPropertiesSet();
			StringRedisTemplate deadTemplate = new StringRedisTemplate(deadFactory);
			deadTemplate.afterPropertiesSet();
			return new EventViewerServiceImpl(deadTemplate, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
		}
	}

	/** 테스트가 시각을 임의로 미는 Clock — 90초 경계 경과를 결정적으로 재현한다. */
	private static Clock movableClock(AtomicReference<Instant> now) {
		return new Clock() {
			@Override
			public ZoneId getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				return now.get();
			}
		};
	}
}
