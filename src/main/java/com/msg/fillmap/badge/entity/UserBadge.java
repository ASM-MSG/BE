package com.msg.fillmap.badge.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 획득 뱃지 row (user_badges, MSG-239). 지급(INSERT)은 native ON CONFLICT DO NOTHING 만 쓰므로
 * (§D5) 쓰기 팩토리가 없다 — UserGrid 패턴의 읽기용 매핑. 비회수(FR-5)라 삭제 경로도 없다.
 * notified_at NULL = 미확인(소급분), 동기 지급은 now() 기록(§D8). featured_rank non-null = 대표 뱃지,
 * 값 = 표시 순서 1·2 — 상한 2 는 V9 partial UNIQUE 가 물리 강제한다(§D6).
 * earned_at 에 @CreationTimestamp 를 두지 않는 이유(MSG-379 D5): JPA save 경로가 없어 실행된 적이
 * 없는데, 나중에 그 경로가 생기면 그때의 JVM 기본 존으로 값이 채워지는 함정이 된다. 실제 값은
 * 지급 native INSERT 의 statement_timestamp() AT TIME ZONE 'UTC' 나 컬럼 DEFAULT(V33)가 채운다.
 */
@Entity
@Table(name = "user_badges")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserBadge {

	@EmbeddedId
	private UserBadgeId id;

	@Column(name = "earned_at", nullable = false, updatable = false)
	private LocalDateTime earnedAt;

	@Column(name = "notified_at")
	private LocalDateTime notifiedAt;

	@Column(name = "featured_rank")
	private Short featuredRank;

	public Long getUserId() {
		return id.getUserId();
	}

	public Long getBadgeId() {
		return id.getBadgeId();
	}
}
