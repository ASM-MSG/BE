package com.msg.fillmap.streak.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.msg.fillmap.streak.entity.Streak;

/**
 * 스트릭 리포지토리 (MSG-200). 갱신은 3분기(같은 날/어제/리셋) CASE 를 담은 native UPSERT 한 문장이
 * 전부다 — 애플리케이션 코드에 시계·타임존 로직 0(§D3·D5). 갱신 직후 current_count 는 별도 PK SELECT
 * 로 읽는다: Spring Data @Modifying 은 RETURNING 결과 행을 못 돌려준다(239 §D5 기각 논리 동일).
 */
public interface StreakRepository extends JpaRepository<Streak, Long> {

	/**
	 * 업로드 이벤트의 스트릭 갱신 — last_recorded_date(KST) 대비 오늘(KST) 기준 같은 날 카운트 no-op /
	 * 어제 +1 / 그 외(NULL = 첫 기록 포함) 1 리셋. 행 사전 생성 없이 첫 이벤트 때 lazy 생성한다.
	 */
	@Modifying
	@Query(value = """
		-- upsertOnUpload — 3분기 CASE 를 한 문장에: user_id PK 단일 행이라 ON CONFLICT 행 잠금이
		-- 동시 업로드를 직렬화한다 (§D5). 날짜는 KST 변환 후 비교 (§D3 — DB TIMESTAMP 는 UTC 저장).
		INSERT INTO streaks (user_id, current_count, max_count, last_recorded_date, updated_at)
		VALUES (:userId, 1, 1, (now() AT TIME ZONE 'Asia/Seoul')::date, now() AT TIME ZONE 'UTC')
		ON CONFLICT (user_id) DO UPDATE SET
			current_count = CASE
				WHEN streaks.last_recorded_date = (now() AT TIME ZONE 'Asia/Seoul')::date
					THEN streaks.current_count                                  -- 같은 날: 카운트 no-op (updated_at 은 아래에서 갱신)
				WHEN streaks.last_recorded_date = (now() AT TIME ZONE 'Asia/Seoul')::date - 1
					THEN streaks.current_count + 1                              -- 어제: 연속 +1
				ELSE 1                                                          -- 끊김·첫 기록(NULL): 1 리셋
			END,
			max_count = GREATEST(streaks.max_count, CASE
				WHEN streaks.last_recorded_date = (now() AT TIME ZONE 'Asia/Seoul')::date
					THEN streaks.current_count
				WHEN streaks.last_recorded_date = (now() AT TIME ZONE 'Asia/Seoul')::date - 1
					THEN streaks.current_count + 1
				ELSE 1
			END),
			last_recorded_date = (now() AT TIME ZONE 'Asia/Seoul')::date,   -- 같은 날엔 동일값 재기록
			updated_at = now() AT TIME ZONE 'UTC'                           -- 같은 날에도 갱신 — 마지막 이벤트 시각 추적
		""", nativeQuery = true)
	int upsertOnUpload(@Param("userId") long userId);

	/** 갱신 직후 판정 metric 읽기 — 같은 트랜잭션에서 UPSERT 가 행 잠금을 쥔 상태라 읽기 정합 문제 없음(§D5). */
	@Query(value = "SELECT current_count FROM streaks WHERE user_id = :userId", nativeQuery = true)
	int findCurrentCount(@Param("userId") long userId);
}
