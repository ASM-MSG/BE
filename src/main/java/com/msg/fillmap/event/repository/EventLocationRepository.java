package com.msg.fillmap.event.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import com.msg.fillmap.event.entity.EventLocation;

public interface EventLocationRepository extends JpaRepository<EventLocation, Long> {

	Optional<EventLocation> findByLocationKey(String locationKey);

	/**
	 * 대표 격자를 읽거나 다시 계산하기 전에 위치 행을 잠근다 (SELECT FOR UPDATE, MSG-438).
	 * 시더의 대표 격자 재계산과 MSG-440 업로드의 대표 격자 읽기가 같은 행 하나를 같은 순서로 잠그므로
	 * 교착 없이, 재계산 도중 들어온 업로드가 옛 대표 격자로 영상을 붙이는 드리프트가 생기지 않는다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<EventLocation> findWithLockByLocationKey(String locationKey);

	/**
	 * 업로드 측 잠금 (MSG-440) — 대표 격자를 읽기 전에 같은 위치 행을 같은 방식으로 잠근다. 시더는 자연키로,
	 * 업로드는 경로의 id 로 들어오지만 잠기는 행은 event_locations 하나로 같아 위 드리프트 방지가 성립한다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<EventLocation> findWithLockById(Long id);

	/**
	 * 회차의 위치 목록 (MSG-439 API 3). 정렬 계약은 display_order 오름차순 → id 오름차순 타이브레이커라
	 * 같은 표시 순서를 준 위치가 둘이어도 응답 순서가 흔들리지 않는다.
	 */
	List<EventLocation> findByOccurrenceIdOrderByDisplayOrderAscIdAsc(Long occurrenceId);
}
