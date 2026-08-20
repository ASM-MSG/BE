package com.msg.fillmap.event.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.msg.fillmap.event.entity.EventSeries;

public interface EventSeriesRepository extends JpaRepository<EventSeries, Long> {

	Optional<EventSeries> findBySeriesKey(String seriesKey);

	/**
	 * 행사 시딩 전체를 직렬화한다 (MSG-438). 롤링 배포 중 두 인스턴스의 ApplicationRunner 가 동시에 돌면
	 * 신규 행은 아직 없어 행 잠금을 걸 수 없고, 동시 INSERT 가 자연키 UNIQUE 위반으로 한쪽 기동을 실패시킨다.
	 * advisory lock 은 행이 아니라 임의의 키에 거는 PostgreSQL 잠금이라 아직 없는 행의 경쟁도 막고,
	 * 트랜잭션 종료 시 자동 해제된다 (VideoRepository.acquirePendingKeyConfirmLock 선례).
	 */
	@Query(value = "SELECT pg_advisory_xact_lock(hashtextextended('event_seed', 0))", nativeQuery = true)
	Object acquireSeedLock();
}
