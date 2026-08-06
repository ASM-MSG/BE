package com.msg.fillmap.moderation.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 영상 신고 1건 (reports — V1 기존 테이블의 첫 매핑, MSG-192). 접수는 PENDING 행 생성으로 끝나고
 * 어떤 자동 전환도 일으키지 않는다 — 블라인드는 관리자 수동 판단만이다.
 * 같은 사용자의 같은 영상 재신고는 V27 유니크 제약(uq_reports_reporter_video)이 상태와 무관하게 막는다.
 *
 * <p>reviewed_by·reviewed_at 컬럼은 이번 범위에서 쓰는 코드가 없어 매핑하지 않는다 (§D6) —
 * 검토 흐름을 만드는 MSG-195 가 자기 요구에 맞게 추가한다.
 */
@Entity
@Table(name = "reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Report {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "reporter_id", nullable = false)
	private Long reporterId;

	@Column(name = "video_id", nullable = false)
	private Long videoId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ReportReason reason;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private ReportStatus status;

	/** 상세 설명. OTHER 사유는 필수(서비스 검사), 그 외 사유는 선택이라 NULL 을 허용한다 (§D7). */
	@Column(length = 500)
	private String detail;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private Report(Long reporterId, Long videoId, ReportReason reason, String detail) {
		this.reporterId = reporterId;
		this.videoId = videoId;
		this.reason = reason;
		this.detail = detail;
		this.status = ReportStatus.PENDING;
	}

	/** 신고 접수 — 항상 PENDING 으로 시작한다 (FR-1). */
	public static Report create(Long reporterId, Long videoId, ReportReason reason, String detail) {
		return new Report(reporterId, videoId, reason, detail);
	}
}
