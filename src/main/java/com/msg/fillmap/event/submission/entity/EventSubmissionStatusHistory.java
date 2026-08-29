package com.msg.fillmap.event.submission.entity;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
 * 신청 상태 이력 (MSG-498 FR-12). 전이마다 한 행이 쌓이는 append 로그라 갱신·삭제가 없고, 신청과 연관을
 * 걸지 않는다 — 쓰기는 신청 id 하나로 끝나고 읽기는 신청당 한 번의 파생 쿼리다.
 * <p>
 * 반려 사유(코드·본문)의 저장 원천이 이 테이블 하나다 (D-3). 이 티켓은 반려 아닌 행만 쓰고 반려 행은
 * 읽기만 하며, 반려 행을 쓰는 쪽은 MSG-500 이다. "반려 행에만 사유가 있다"는 DDL CHECK 두 개가 강제한다.
 */
@Entity
@Table(name = "event_submission_status_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventSubmissionStatusHistory {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "event_submission_id", nullable = false)
	private Long eventSubmissionId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", length = 20, nullable = false)
	private EventSubmissionStatus status;

	@Convert(converter = EventSubmissionReasonCodesConverter.class)
	@Column(name = "reason_codes", length = 30)
	private List<EventSubmissionReasonCode> reasonCodes;

	@Column(name = "reason_text")
	private String reasonText;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	/** 심사 중 전이 기록 (제출·재제출). 사유가 없는 상태 행이라 인자에도 사유가 없다. */
	public static EventSubmissionStatusHistory inReview(Long eventSubmissionId, LocalDateTime now) {
		EventSubmissionStatusHistory history = new EventSubmissionStatusHistory();
		history.eventSubmissionId = eventSubmissionId;
		history.status = EventSubmissionStatus.IN_REVIEW;
		history.createdAt = now;
		return history;
	}
}
