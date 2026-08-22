package com.msg.fillmap.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.event.dto.EventNotificationResponseDto;
import com.msg.fillmap.event.entity.EventOccurrence;
import com.msg.fillmap.event.entity.EventSeries;
import com.msg.fillmap.event.exception.EventErrorCode;
import com.msg.fillmap.event.repository.EventNotificationSubscriptionRepository;
import com.msg.fillmap.event.repository.EventOccurrenceRepository;
import com.msg.fillmap.event.repository.EventSeriesRepository;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 행사 알림 구독 토글과 노출 상태 파생 (MSG-442, 실 PostgreSQL). 상태 경계를 고정 Clock 으로 재현하고
 * 실제 회차 행에 붙여 본다 — 파생식이 상태와 행 존재의 AND 라 둘 중 하나만 목으로 대체하면 검증이 반쪽이다.
 * <p>
 * 격리(공유 로컬 DB): 합성 자연키(msg442-*)와 국내 정의역 밖 합성 격자 인덱스(9만대)만 쓰고 롤백한다.
 */
@SpringBootTest
@Transactional
@DisplayName("EventNotificationService 구독 토글·노출 상태 (실 PostgreSQL)")
class EventNotificationServiceTest {

	private static final LocalDateTime 시작 = LocalDateTime.of(2026, 10, 6, 1, 0);
	private static final LocalDateTime 종료 = LocalDateTime.of(2026, 10, 15, 13, 0);
	private static final LocalDateTime 진행중 = 시작.plusDays(1);
	private static final LocalDateTime 유예중 = 종료.plusDays(1);
	private static final LocalDateTime 아카이브 = 종료.plusDays(EventOccurrence.UPLOAD_GRACE_DAYS);

	@Autowired
	private EventOccurrenceRepository occurrenceRepository;

	@Autowired
	private EventSeriesRepository seriesRepository;

	@Autowired
	private EventNotificationSubscriptionRepository subscriptionRepository;

	@Autowired
	private UserRepository userRepository;

	private long userId;
	private long occurrenceId;

	@BeforeEach
	void setUp() {
		userId = userRepository.save(User.createLocalUser(
			"msg442-" + UUID.randomUUID() + "@example.com", "hash", "테스터")).getId();
		EventSeries series = seriesRepository.save(
			new EventSeries("msg442-series-" + 짧은키(), "합성 시리즈"));
		EventOccurrence occurrence = new EventOccurrence(series, "msg442-occ-" + 짧은키());
		occurrence.update(series, "합성 회차", "부산", 시작, 종료, 90000, 90001, 90500, 90501);
		occurrenceId = occurrenceRepository.save(occurrence).getId();
	}

	/** 자연키 컬럼 폭(series_key 50자)에 맞춘 합성 접미사. */
	private String 짧은키() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	private EventNotificationService 서비스(LocalDateTime now) {
		return new EventNotificationServiceImpl(occurrenceRepository, subscriptionRepository,
			Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
	}

	@Nested
	@DisplayName("토글")
	class Toggle {

		// 검증: FR-EVENT-06
		@Test
		@DisplayName("구독 ON 은 행을 만들고 반복 요청에도 멱등하다")
		void 구독_ON은_행을_만들고_반복_요청에도_멱등하다() {
			EventNotificationService service = 서비스(진행중);

			EventNotificationResponseDto first = service.updateSubscription(userId, occurrenceId, true);
			EventNotificationResponseDto second = service.updateSubscription(userId, occurrenceId, true);

			assertThat(first.enabled()).isTrue();
			assertThat(second.enabled()).isTrue();
			assertThat(subscriptionRepository.findAllByIdEventOccurrenceId(occurrenceId)).hasSize(1);
		}

		// 검증: FR-EVENT-06
		@Test
		@DisplayName("구독 OFF 는 행을 지우고 이미 없어도 성공한다")
		void 구독_OFF는_행을_지우고_이미_없어도_성공한다() {
			EventNotificationService service = 서비스(진행중);
			service.updateSubscription(userId, occurrenceId, true);

			assertThat(service.updateSubscription(userId, occurrenceId, false).enabled()).isFalse();
			assertThat(service.updateSubscription(userId, occurrenceId, false).enabled()).isFalse();

			assertThat(subscriptionRepository.findAllByIdEventOccurrenceId(occurrenceId)).isEmpty();
		}

		// 검증: FR-EVENT-06
		@Test
		@DisplayName("종료된 행사에 구독 ON 은 거절되고 OFF 는 성공한다 — 유예·아카이브 양쪽")
		void 종료된_행사에_구독_ON은_거절되고_OFF는_성공한다() {
			for (LocalDateTime 종료후 : new LocalDateTime[] {유예중, 아카이브}) {
				EventNotificationService service = 서비스(종료후);

				assertThatThrownBy(() -> service.updateSubscription(userId, occurrenceId, true))
					.isInstanceOf(ApiException.class)
					.hasFieldOrPropertyWithValue("errorCode", EventErrorCode.EVENT_INTERACTION_LOCKED);
				assertThat(service.updateSubscription(userId, occurrenceId, false).enabled()).isFalse();
			}
		}

		// 검증: FR-EVENT-06
		@Test
		@DisplayName("없는 회차는 EVENT_NOT_FOUND 다 — 아직 노출 전인 예정 회차도 같은 응답으로 존재를 숨긴다")
		void 없는_회차는_EVENT_NOT_FOUND다() {
			EventNotificationService service = 서비스(진행중);

			assertThatThrownBy(() -> service.updateSubscription(userId, -1L, true))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", EventErrorCode.EVENT_NOT_FOUND);

			// 노출 시작(시작 2주 전) 이전 시점 — 조회 네 경로와 같은 술어로 감춰야 id 대입 탐색이 막힌다
			EventNotificationService 노출전 = 서비스(시작.minusDays(EventOccurrence.VISIBLE_BEFORE_DAYS + 1));
			assertThatThrownBy(() -> 노출전.updateSubscription(userId, occurrenceId, true))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", EventErrorCode.EVENT_NOT_FOUND);
		}
	}

	@Nested
	@DisplayName("노출 상태 파생")
	class Derived {

		// 검증: FR-EVENT-06
		@Test
		@DisplayName("예정과 진행 중의 구독 상태는 행 존재와 일치한다")
		void 예정과_진행_중의_구독_상태는_행_존재와_일치한다() {
			LocalDateTime 예정 = 시작.minusDays(1);
			assertThat(서비스(예정).isSubscribed(userId, occurrenceId)).isFalse();

			서비스(예정).updateSubscription(userId, occurrenceId, true);

			assertThat(서비스(예정).isSubscribed(userId, occurrenceId)).isTrue();
			assertThat(서비스(진행중).isSubscribed(userId, occurrenceId)).isTrue();
		}

		// 검증: FR-EVENT-06
		@Test
		@DisplayName("종료된 행사는 구독 행이 남아 있어도 OFF 로 파생된다 — 정리 틱 이전 구간")
		void 종료된_행사는_구독_행이_남아_있어도_OFF로_파생된다() {
			서비스(진행중).updateSubscription(userId, occurrenceId, true);

			assertThat(서비스(종료).isSubscribed(userId, occurrenceId)).isFalse();
			assertThat(서비스(유예중).isSubscribed(userId, occurrenceId)).isFalse();
			assertThat(subscriptionRepository.findAllByIdEventOccurrenceId(occurrenceId)).hasSize(1);
		}

		// 검증: FR-EVENT-06
		@Test
		@DisplayName("비로그인 열람은 항상 false 다 — 조회 없이 즉시 반환")
		void 비로그인_열람은_항상_false다() {
			서비스(진행중).updateSubscription(userId, occurrenceId, true);

			assertThat(서비스(진행중).isSubscribed(null, occurrenceId)).isFalse();
		}
	}
}
