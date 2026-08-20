package com.msg.fillmap.event.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.msg.fillmap.event.entity.EventVideo;

public interface EventVideoRepository extends JpaRepository<EventVideo, Long> {

	/** 대표 격자 변경 드리프트 가드 — 영상이 하나라도 붙은 위치는 대표 격자를 바꿀 수 없다 (MSG-438). */
	boolean existsByLocationId(Long eventLocationId);
}
