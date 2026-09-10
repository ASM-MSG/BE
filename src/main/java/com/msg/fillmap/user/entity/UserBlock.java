package com.msg.fillmap.user.entity;

import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자 차단 행 (user_blocks, MSG-569). 불변식: <b>행 존재 = 차단 상태</b> — 행은 생성·삭제만 되고 갱신되지
 * 않는다(created_at 은 최초 차단 시각으로 고정, 재차단은 DO NOTHING).
 * <p>
 * 쓰기는 리포지토리 native INSERT(ON CONFLICT)와 JPQL 벌크 DELETE 가 맡아 이 매핑은 조회(목록 프로젝션·
 * 판정)만 담당한다. 두 User 연관은 타 도메인 엔티티의 읽기 전용 참조이고(컨벤션 "영속 계층"), equals·
 * hashCode 는 id 만 써서 LAZY 프록시 초기화와 순환 참조를 피한다.
 */
@Entity
@Table(name = "user_blocks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserBlock {

	@EmbeddedId
	private UserBlockId id;

	@MapsId("blockerId")
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "blocker_id", nullable = false)
	private User blocker;

	@MapsId("blockedId")
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "blocked_id", nullable = false)
	private User blocked;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof UserBlock that)) {
			return false;
		}
		return Objects.equals(id, that.id);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}

	@Override
	public String toString() {
		return "UserBlock{id=" + id + ", createdAt=" + createdAt + "}";
	}
}
