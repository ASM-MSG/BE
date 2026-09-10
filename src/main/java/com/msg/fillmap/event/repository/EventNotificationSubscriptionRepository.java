package com.msg.fillmap.event.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.event.entity.EventNotificationSubscription;
import com.msg.fillmap.event.entity.EventNotificationSubscriptionId;

/**
 * 행사 알림 구독 리포지토리 (MSG-442). 토글 쓰기는 native 2종(INSERT ON CONFLICT·DELETE)뿐 — 둘 다
 * 1문장 멱등이라 같은 값 재전환이 오류 없이 같은 상태로 끝난다 (NotificationOptOutRepository 선례).
 * 구독 여부 판정은 기본 제공 existsById (PK 선두 user_id 단건 히트).
 */
public interface EventNotificationSubscriptionRepository
	extends JpaRepository<EventNotificationSubscription, EventNotificationSubscriptionId> {

	/** 구독 ON. 이미 구독 중이면 DO NOTHING 0행 = 멱등, created_at 도 첫 구독 시각 그대로 남는다. */
	@Modifying
	@Query(value = """
		INSERT INTO event_notification_subscriptions (user_id, event_occurrence_id)
		VALUES (:userId, :occurrenceId)
		ON CONFLICT (user_id, event_occurrence_id) DO NOTHING
		""", nativeQuery = true)
	int insertSubscription(@Param("userId") long userId, @Param("occurrenceId") long occurrenceId);

	/** 구독 OFF — 행 삭제. 이미 구독이 없어도 0행 = 멱등. */
	@Modifying
	@Query(value = """
		DELETE FROM event_notification_subscriptions
		WHERE user_id = :userId AND event_occurrence_id = :occurrenceId
		""", nativeQuery = true)
	int deleteSubscription(@Param("userId") long userId, @Param("occurrenceId") long occurrenceId);

	/** 회차 하나의 구독자 전량 — 스케줄러 시작 알림 fanout 과 시더의 일정 변경 알림이 쓴다. */
	List<EventNotificationSubscription> findAllByIdEventOccurrenceId(Long eventOccurrenceId);

	/**
	 * 종료된 회차의 구독 일괄 해제 (FR-16). DELETE 는 본성상 멱등이라 재실행이 그냥 안전하다.
	 * 호출자(스케줄러 정리 단계)가 같은 트랜잭션에서 advisory lock 을 먼저 선취해 시더와 직렬화한다.
	 */
	@Modifying
	@Query("""
		DELETE FROM EventNotificationSubscription s
		WHERE s.id.eventOccurrenceId IN (SELECT o.id FROM EventOccurrence o WHERE o.endsAt <= :now)
		""")
	int deleteByOccurrenceEndedAtOrBefore(@Param("now") LocalDateTime now);

	/** 회차 하나의 구독 전량 삭제 — 종료 경계를 지난 회차를 건드리는 재시드의 선삭제 (시더 훅). */
	@Modifying
	@Query("DELETE FROM EventNotificationSubscription s WHERE s.id.eventOccurrenceId = :occurrenceId")
	int deleteByOccurrenceId(@Param("occurrenceId") long occurrenceId);
}
