package com.msg.fillmap.event.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.msg.fillmap.event.entity.EventSeries;

public interface EventSeriesRepository extends JpaRepository<EventSeries, Long> {

	Optional<EventSeries> findBySeriesKey(String seriesKey);

	/**
	 * 행사 데이터 쓰기 전체를 직렬화한다 (MSG-438 시딩 · MSG-442 구독 정리). 롤링 배포 중 두 인스턴스의
	 * ApplicationRunner 가 동시에 돌면 신규 행은 아직 없어 행 잠금을 걸 수 없고, 동시 INSERT 가 자연키
	 * UNIQUE 위반으로 한쪽 기동을 실패시킨다. advisory lock 은 행이 아니라 임의의 키에 거는 PostgreSQL
	 * 잠금이라 아직 없는 행의 경쟁도 막고, 트랜잭션 종료 시 자동 해제된다
	 * (VideoRepository.acquirePendingKeyConfirmLock 선례).
	 * <p>
	 * MSG-442 의 종료 구독 정리가 **같은 키**를 쓰는 것이 설계의 일부다 — 정리가 구 ends_at 스냅숏으로 도는
	 * 사이 시더가 일정 연장을 커밋하면 되살아난 회차의 구독이 전멸하기 때문에, 두 쓰기를 한 줄로 세운다.
	 * 키 문자열을 바꾸면 그 직렬화가 조용히 풀린다.
	 */
	@Query(value = "SELECT pg_advisory_xact_lock(hashtextextended('event_seed', 0))", nativeQuery = true)
	Object acquireEventWriteLock();
}
