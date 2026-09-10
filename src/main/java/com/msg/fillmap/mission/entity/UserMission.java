package com.msg.fillmap.mission.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 스탬프 row (user_missions, V6 — MSG-223). 발급(INSERT)은 native ON CONFLICT DO NOTHING 만 쓰므로
 * (§D2 3단계) 쓰기 팩토리가 없다 — UserBadge 패턴의 읽기용 최소 매핑. 비회수(FR-15)라 삭제 경로도
 * 없고, completed_at 은 컬럼 DEFAULT 가 채운다.
 */
@Entity
@Table(name = "user_missions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserMission {

	@EmbeddedId
	private UserMissionId id;

	@Column(name = "completed_at", nullable = false, updatable = false)
	private LocalDateTime completedAt;

	public Long getUserId() {
		return id.getUserId();
	}

	public Long getMissionId() {
		return id.getMissionId();
	}
}
