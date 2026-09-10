package com.msg.fillmap.event.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 행사 회차 알림 구독 row (event_notification_subscriptions, MSG-442). 불변식: **행 존재 = 그 회차 알림 ON**
 * (opt-in — 행 부재가 OFF 인 notification_opt_outs 와 방향이 반대다). 행은 생성/삭제만 되고 갱신되지 않는다.
 * <p>
 * 연관관계를 달지 않는다 — 이 엔티티를 축으로 한 조인 조회가 없고(스케줄러는 회차 목록을 먼저 뽑고 회차별
 * 구독자를 파생 쿼리로 읽는 2단 조회, 해제는 서브쿼리 벌크 delete), 유일한 참조 대상인 {@link EventOccurrence}
 * 는 타 조회에서 이미 손에 들려 있다. 쓰기는 native(INSERT ON CONFLICT·DELETE)라 조회 매핑만 담당한다
 * (NotificationOptOut 선례).
 */
@Entity
@Table(name = "event_notification_subscriptions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventNotificationSubscription {

	@EmbeddedId
	private EventNotificationSubscriptionId id;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;
}
