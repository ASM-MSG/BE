package com.msg.fillmap.notification.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.notification.entity.Notification;

/**
 * 알림 outbox 리포지토리 (MSG-179). 쓰기는 전부 native — record INSERT 는 ON CONFLICT DO NOTHING
 * (FR-6 멱등 1차, PushTokenRepository.upsert 선례), 상태 갱신은 D4 상태 머신의 전이별 1문장.
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

	/**
	 * outbox 기록 (FR-3·FR-6). 같은 (user_id, event_key) 재기록은 DO NOTHING 으로 흡수 — 반환 0.
	 * status·retry_count 는 DDL DEFAULT (PENDING·0). created_at 은 DDL DEFAULT(CURRENT_TIMESTAMP)에 맡기지
	 * 않고 markSent 와 동일 형상의 명시 UTC 로 쓴다 (MSG-314 D4) — timestamptz→timestamp 대입은 세션 TZ
	 * 캐스트라 KST JVM 에선 +9h 저장돼 countRecordedSince 의 KST 자정 판정이 환경별로 스큐난다.
	 */
	@Modifying
	@Query(value = """
		INSERT INTO notifications (user_id, category, event_key, title, body, created_at)
		VALUES (:userId, :category, :eventKey, :title, :body, statement_timestamp() AT TIME ZONE 'UTC')
		ON CONFLICT (user_id, event_key) DO NOTHING
		""", nativeQuery = true)
	int insert(
		@Param("userId") long userId,
		@Param("category") String category,
		@Param("eventKey") String eventKey,
		@Param("title") String title,
		@Param("body") String body
	);

	/** 릴레이 폴링 배치 (D13) — PENDING 을 오래된 순으로 batch 개. idx_notifications_pending(partial) 사용. */
	@Query(value = """
		SELECT * FROM notifications WHERE status = 'PENDING' ORDER BY id LIMIT :limit
		""", nativeQuery = true)
	List<Notification> findPendingBatch(@Param("limit") int limit);

	/**
	 * 릴레이 발행 성공 후 전이 (D13) — 컨슈머 종결 전 재발행을 막는다 (D4 PUBLISHED 존재 이유).
	 * PENDING 가드: send~markPublished 사이에 컨슈머가 먼저 SENT 종결한 행을 역행시키지 않는다 (D4 단방향).
	 * published_at 은 markSent 와 동일 형상의 UTC 벽시계 — resetStalePublished 판정 기준이라 축자 UTC 필수.
	 */
	@Modifying
	@Query(value = """
		UPDATE notifications
		SET status = 'PUBLISHED', published_at = statement_timestamp() AT TIME ZONE 'UTC'
		WHERE id = :id AND status = 'PENDING'
		""", nativeQuery = true)
	int markPublished(@Param("id") long id);

	/**
	 * 스테일 PUBLISHED 자동 복구 (Codex 3R P2) — 컨슈머가 브로커 보존기간보다 오래 다운되면 메시지가
	 * 소실돼 PUBLISHED 가 영구 잔류한다(릴레이는 PENDING 만 폴링). 발행 시각(published_at)이
	 * threshold(now − stale-published-minutes, 앱 UTC 계산) 이전인 행을 PENDING 으로 되돌려 릴레이가
	 * 같은 사이클에 재발행한다. created_at 기준이 아닌 이유(Codex 4R P1): 기록 30분 경과 후 첫 발행된
	 * 백로그가 발행 직후 리셋돼 5s 마다 재발행 폭주 + oldest 배치 영구 선점이 되기 때문. 정상 경로는
	 * 발행 5s 내 소비·백오프 최대 ~4.3분이라 발행 후 30분 초과 = 컨슈머 미달 확정이고, 재발행 중복은
	 * 컨슈머 멱등 2차 방어(종결 상태 검사)가 흡수한다. idx_notifications_published(partial) 사용.
	 * published_at 이 NULL 인 PUBLISHED 행은 {@code NULL < threshold} 가 UNKNOWN 이라 자연 제외된다 —
	 * 운영 경로에선 markPublished 가 전이와 기록을 한 문장으로 하므로 NULL PUBLISHED 자체가 없다(테스트 직조작 한정).
	 */
	@Modifying
	@Query(value = """
		UPDATE notifications SET status = 'PENDING' WHERE status = 'PUBLISHED' AND published_at < :threshold
		""", nativeQuery = true)
	int resetStalePublished(@Param("threshold") LocalDateTime threshold);

	/** 컨슈머 처리 진입 시 +1 (D4) — 몇 번 만에 성공했는지가 남는다. */
	@Modifying
	@Query(value = """
		UPDATE notifications SET retry_count = retry_count + 1 WHERE id = :id
		""", nativeQuery = true)
	int incrementRetryCount(@Param("id") long id);

	/**
	 * 발송 성공 종결 (D4). sent_at 은 statement_timestamp() AT TIME ZONE 'UTC'
	 * (StreakRepository.upsertOnUpload updated_at 선례) — now()(timestamptz)는 timestamp 컬럼 대입 시
	 * 세션 TZ 로 캐스트돼 KST JVM 에서 +9h 저장된다. sent_at 은 D8 자정 경계 판정(countSentSince)
	 * 입력이라 특히 UTC 축자여야 한다. 오차가 무해해 예외로 남겨 뒀던 push_tokens last_used_at 의
	 * now() 도 MSG-379 D4 에서 같은 식으로 바꿔, 이제 이 도메인의 시각 기록은 전부 존 무관이다.
	 *
	 * 원본 상태 술어(PENDING·PUBLISHED)는 DB 레벨 compare-and-set 이다 (MSG-343 Codex 4R, markPublished 선례) —
	 * 리밸런스·max.poll 만료로 두 소비자가 같은 행을 겹쳐 읽으면 consume 초입 가드는 둘 다 통과시키므로,
	 * 여기서 승자 1명만 1행을 갱신하게 해 중복 발송 종결과 이중 계상을 함께 막는다. 허용 원본을 이 둘로 잡는
	 * 근거는 초입 가드가 정확히 그 두 상태만 통과시키는 것 — 같은 술어라야 의미론이 일관된다. 종결 3전이가
	 * 모두 같은 술어를 쓰므로 종결은 행마다 한 번뿐이고, 종결 후 재전달은 0행이라 상태도 안 덮인다.
	 */
	@Modifying
	@Query(value = """
		UPDATE notifications SET status = 'SENT', sent_at = statement_timestamp() AT TIME ZONE 'UTC'
		WHERE id = :id AND status IN ('PENDING', 'PUBLISHED')
		""", nativeQuery = true)
	int markSent(@Param("id") long id);

	/** 미발송 종결 (D4·FR-8) — 사유(PREF_OFF·RATE_LIMITED·NO_TOKEN)를 last_error 에 남긴다. 술어는 markSent 와 동일. */
	@Modifying
	@Query(value = """
		UPDATE notifications SET status = 'SKIPPED', last_error = left(:reason, 255)
		WHERE id = :id AND status IN ('PENDING', 'PUBLISHED')
		""", nativeQuery = true)
	int markSkipped(@Param("id") long id, @Param("reason") String reason);

	/**
	 * 재시도 상한 소진 격리 (FR-4) — left(255): 예외 메시지가 컬럼 폭을 넘어도 DEAD 전이는 성공해야 한다.
	 * 술어는 markSent 와 동일 — 백오프 재시도 중에도 상태는 PENDING·PUBLISHED 로 남아 마지막 시도의 DEAD 가
	 * 통과한다(중간에 상태를 바꾸는 건 종결 3전이뿐이고, 종결됐다면 recoverer 까지 오지 않는다).
	 */
	@Modifying
	@Query(value = """
		UPDATE notifications SET status = 'DEAD', last_error = left(:error, 255)
		WHERE id = :id AND status IN ('PENDING', 'PUBLISHED')
		""", nativeQuery = true)
	int markDead(@Param("id") long id, @Param("error") String error);

	/**
	 * 전송률 제한 카운트 (D8·FR-12). threshold 는 애플리케이션이 KST 오늘 자정을 UTC 로 변환해 바인딩 —
	 * SQL 세션 TZ 캐스트 금지 (MSG-222 스큐 실측). idx_notifications_user 사용.
	 */
	@Query(value = """
		SELECT count(*) FROM notifications
		WHERE user_id = :userId AND category = :category AND status = 'SENT' AND sent_at >= :threshold
		""", nativeQuery = true)
	long countSentSince(
		@Param("userId") long userId,
		@Param("category") String category,
		@Param("threshold") LocalDateTime threshold
	);

	/**
	 * 백로그 Gauge 폴링 (MSG-343 D3) — status 를 리터럴로 박아 partial index(idx_notifications_pending,
	 * V21)가 항상 받는다. 바인드 파라미터면 generic plan 이 partial index 조건 일치를 증명하지 못한다.
	 */
	@Query(value = "SELECT count(*) FROM notifications WHERE status = 'PENDING'", nativeQuery = true)
	long countPending();

	/** countPending 과 같은 사정 — idx_notifications_published(partial, V21) 사용. */
	@Query(value = "SELECT count(*) FROM notifications WHERE status = 'PUBLISHED'", nativeQuery = true)
	long countPublished();

	/**
	 * 기간 내 기록 카운트 (MSG-314 D3) — 임박 알림 하루 1건 상한의 존재 확인. 발송 여부(status)는 보지
	 * 않는다: 상한은 기록 단계 규칙이므로 발송 실패나 SKIPPED 여부와 무관하게 "오늘 기록했는가"만 센다.
	 * threshold 는 countSentSince 와 같은 규칙으로 앱이 KST 자정을 UTC 변환해 바인딩 (insert 의 명시 UTC
	 * created_at 과 자기일관). uq_notifications_user_event(user_id, event_key)가 prefix 검색을 받친다.
	 */
	@Query(value = """
		SELECT count(*) FROM notifications
		WHERE user_id = :userId AND event_key LIKE :prefix || '%' AND created_at >= :threshold
		""", nativeQuery = true)
	long countRecordedSince(
		@Param("userId") long userId,
		@Param("prefix") String prefix,
		@Param("threshold") LocalDateTime threshold
	);
}
