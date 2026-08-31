package com.msg.fillmap.user.entity;

import java.time.LocalDateTime;

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
 * 행사 운영자 계정 발급 요청 1건 (MSG-499 FR-6). 계정이 없는 신청자가 비로그인 공개 폼으로 접수하고
 * 관리자가 큐에서 검토한다.
 *
 * <p>연관관계 없이 issuedUserId 를 Long 으로 보관한다 — 쓰기가 접수 UPSERT 와 상태 전이뿐이고 조인
 * 조회가 없어 {@code @ManyToOne} 을 달 근거(크기 상한·조회 수요)가 아직 없다 (OrgEmailChangeRequest
 * 와 같은 판단).
 *
 * <p>{@code createdAt} 은 최초 접수 시각이라 재접수에도 보존되고, {@code updatedAt} 은 마지막 접수
 * 시각이면서 관리자 심사의 낙관적 검증 토큰이다 — 승인·반려 요청이 상세 조회로 받은 값을 그대로
 * 에코해야 처리된다(조회와 처리 사이의 익명 재접수·변조를 1426 으로 막는다).
 */
@Entity
@Table(name = "org_account_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrgAccountRequest {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "org_name", nullable = false, length = 100)
	private String orgName;

	@Column(name = "contact_name", nullable = false, length = 20)
	private String contactName;

	@Column(name = "contact_phone", nullable = false, length = 20)
	private String contactPhone;

	@Column(nullable = false, length = 255)
	private String email;

	@Column(name = "event_name", nullable = false, length = 200)
	private String eventName;

	@Column(nullable = false, columnDefinition = "text")
	private String content;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private OrgAccountRequestStatus status;

	@Column(name = "reject_reason", length = 500)
	private String rejectReason;

	@Column(name = "issued_user_id")
	private Long issuedUserId;

	/** 최초 접수 시각(UTC). 접수 UPSERT 가 갱신 대상에서 빼 재접수에도 보존된다. */
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	/** 마지막 접수 시각(UTC) 겸 심사의 검토 기준 시각. 쓰기 경로는 접수 UPSERT 하나뿐이다. */
	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	@Column(name = "processed_at")
	private LocalDateTime processedAt;

	/** 승인 전이 — 발급된 계정 id 와 처리 시각을 남긴다. 상태 검증은 서비스가 행 잠금 안에서 한다. */
	public void issue(Long userId, LocalDateTime processedAt) {
		this.status = OrgAccountRequestStatus.ISSUED;
		this.issuedUserId = userId;
		this.processedAt = processedAt;
	}

	/** 반려 전이 — 사유는 관리자의 수기 통보 재료다(메일 발송 없음, FR-6). */
	public void reject(String reason, LocalDateTime processedAt) {
		this.status = OrgAccountRequestStatus.REJECTED;
		this.rejectReason = reason;
		this.processedAt = processedAt;
	}
}
