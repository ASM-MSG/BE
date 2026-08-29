package com.msg.fillmap.event.submission.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.msg.fillmap.event.submission.entity.EventSubmissionStatusHistory;

public interface EventSubmissionStatusHistoryRepository extends JpaRepository<EventSubmissionStatusHistory, Long> {

	/** 신청 하나의 전체 이력을 발생 순으로 (FR-12). id 오름차순이 곧 발생 순이다 — append 로그라 갱신이 없다. */
	List<EventSubmissionStatusHistory> findByEventSubmissionIdOrderByIdAsc(Long eventSubmissionId);
}
