package com.msg.fillmap.event.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 행사 알림 구독 복합키의 동등성 계약 (MSG-442, UserGridTest 선례). 운영 경로는 {@code existsById} 로
 * DB 에 물어보므로 equals 를 타지 않는데, 영속성 컨텍스트의 1차 캐시 조회와 컬렉션 담기는 이 구현에
 * 의존한다 — 깨지면 같은 구독을 다른 행으로 보게 된다.
 */
@DisplayName("EventNotificationSubscriptionId 복합키 동등성")
class EventNotificationSubscriptionIdTest {

	@Test
	@DisplayName("같은 user_id와 event_occurrence_id 조합의 복합키는 동등하다")
	void 같은_user_id와_event_occurrence_id_조합의_복합키는_동등하다() {
		EventNotificationSubscriptionId id1 = new EventNotificationSubscriptionId(1L, 10L);
		EventNotificationSubscriptionId id2 = new EventNotificationSubscriptionId(1L, 10L);
		EventNotificationSubscriptionId otherUser = new EventNotificationSubscriptionId(2L, 10L);
		EventNotificationSubscriptionId otherOccurrence = new EventNotificationSubscriptionId(1L, 11L);

		assertThat(id1).isEqualTo(id1);                    // 자기 참조
		assertThat(id1).isEqualTo(id2);
		assertThat(id1).hasSameHashCodeAs(id2);
		assertThat(id1).isNotEqualTo(otherUser);           // 사용자만 다름
		assertThat(id1).isNotEqualTo(otherOccurrence);     // 회차만 다름
		assertThat(id1).isNotEqualTo("1_10");              // 타입 불일치
		assertThat(id1).isNotEqualTo(null);
	}
}
