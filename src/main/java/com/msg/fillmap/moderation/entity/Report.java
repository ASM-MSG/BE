package com.msg.fillmap.moderation.entity;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 영상 신고 1건 (reports — V1 기존 테이블의 첫 매핑, MSG-192). 접수는 PENDING 행 생성으로 끝나고
 * 어떤 자동 전환도 일으키지 않는다 — 블라인드는 관리자 수동 판단만이다.
 * 같은 사용자의 같은 영상 재신고는 V27 유니크 제약(uq_reports_reporter_video)이 상태와 무관하게 막는다.
 *
 * <p>관리자 처리(MSG-195)는 PENDING 을 RESOLVED(승인) 또는 REJECTED(기각) 로 한 번만 옮기고,
 * 누가 언제 처리했는지를 reviewed_by·reviewed_at 에 남긴다 (MSG-192 §D6 이 이 티켓 몫으로 예약한 매핑).
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

	/** 처리한 관리자의 사용자 id. 미처리면 null (MSG-195 FR-4). */
	@Column(name = "reviewed_by")
	private Long reviewedBy;

	/** 처리 시각. 미처리면 null (MSG-195 FR-4). */
	@Column(name = "reviewed_at")
	private LocalDateTime reviewedAt;

	/**
	 * 접수 시각. @CreationTimestamp 를 쓰지 않고 생성자에서 UTC 로 직접 넣는다 (MSG-376) —
	 * 그 애너테이션은 JVM 기본 존의 벽시계를 만들어 KST 개발 머신에서 +9h 가 저장되는데, 이 값은
	 * 응답에 실려 전역 코덱이 UTC 로 표기하므로 저장 축이 UTC 여야 한다. reviewedAt 과 같은 축이다.
	 */
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private Report(Long reporterId, Long videoId, ReportReason reason, String detail) {
		this.reporterId = reporterId;
		this.videoId = videoId;
		this.reason = reason;
		this.detail = detail;
		this.status = ReportStatus.PENDING;
		this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
	}

	/** 신고 접수 — 항상 PENDING 으로 시작한다 (FR-1). */
	public static Report create(Long reporterId, Long videoId, ReportReason reason, String detail) {
		return new Report(reporterId, videoId, reason, detail);
	}

	/**
	 * 승인 종결 (MSG-195 FR-4). "이미 처리된 신고인가" 가드는 여기에 두지 않는다 — 재처리 판정은 신고 행
	 * 잠금을 쥔 상태에서만 경합에 안전하고, 그 잠금은 서비스가 갖는다 (Video.markBlinded 선례).
	 */
	public void resolve(Long adminId) {
		markReviewed(ReportStatus.RESOLVED, adminId);
	}

	/** 기각 종결 (MSG-195 FR-6). 영상에는 아무 영향이 없다. 가드 위치는 resolve 주석 참조. */
	public void reject(Long adminId) {
		markReviewed(ReportStatus.REJECTED, adminId);
	}

	private void markReviewed(ReportStatus status, Long adminId) {
		this.status = status;
		this.reviewedBy = adminId;
		this.reviewedAt = LocalDateTime.now(ZoneOffset.UTC);
	}
}
