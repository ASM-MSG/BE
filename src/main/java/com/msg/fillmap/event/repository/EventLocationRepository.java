package com.msg.fillmap.event.repository;

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
}
