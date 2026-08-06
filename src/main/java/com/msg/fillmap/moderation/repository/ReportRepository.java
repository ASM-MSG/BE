package com.msg.fillmap.moderation.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.msg.fillmap.moderation.entity.Report;

public interface ReportRepository extends JpaRepository<Report, Long> {

	/**
	 * 중복 신고 선제 검사 (FR-2). status 무관 — 처리 완료된 신고가 있어도 재신고는 막힌다 (§D3).
	 * V27 유니크 제약(uq_reports_reporter_video)이 만든 인덱스를 탄다.
	 */
	boolean existsByReporterIdAndVideoId(Long reporterId, Long videoId);
}
