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
 * 아이디(공식 이메일) 변경 요청 1건 (MSG-497 FR-23). 아이디는 기관 인증의 근거라 자체 변경이 불가하고,
 * 행사 운영자는 접수만 하며 반영은 관리자 승인(MSG-500) 몫이다.
 *
 * <p>연관관계 없이 userId 를 보관한다 — 이 티켓의 쓰기는 native UPSERT 한 문장뿐이고 조회 경로가 없다.
 * 엔티티는 스키마 매핑과 MSG-499 관리자 큐 조회의 발판으로 남기며, 연관이 필요해지면 그때 단다.
 */
@Entity
@Table(name = "org_email_change_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrgEmailChangeRequest {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "requested_email", nullable = false, length = 255)
	private String requestedEmail;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private OrgEmailChangeStatus status;

	/** 접수 시각(UTC). 쓰기 경로가 native UPSERT 하나라 서비스가 주입받은 Clock 값을 바인딩한다. */
	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;
}
