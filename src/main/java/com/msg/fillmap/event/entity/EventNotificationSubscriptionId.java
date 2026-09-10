package com.msg.fillmap.event.entity;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 행사 알림 구독 복합 기본키 (user_id, event_occurrence_id) — 구독의 정체성은 사용자·회차 쌍이다 (MSG-442).
 * 헤더의 구독 여부 조회가 이 키로 existsById 단건 히트한다 (NotificationOptOutId 동형).
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventNotificationSubscriptionId implements Serializable {

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "event_occurrence_id", nullable = false)
	private Long eventOccurrenceId;

	public EventNotificationSubscriptionId(Long userId, Long eventOccurrenceId) {
		this.userId = userId;
		this.eventOccurrenceId = eventOccurrenceId;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof EventNotificationSubscriptionId that)) {
			return false;
		}
		return Objects.equals(userId, that.userId) && Objects.equals(eventOccurrenceId, that.eventOccurrenceId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(userId, eventOccurrenceId);
	}
}
