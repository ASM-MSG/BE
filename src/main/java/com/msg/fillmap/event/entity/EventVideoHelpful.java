package com.msg.fillmap.event.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 행사 영상 도움돼요 row (event_video_helpfuls, MSG-441). 불변식: <b>행 존재 = 그 사용자가 누른 상태</b>.
 * 행은 생성/삭제만 되고 갱신되지 않는다.
 * <p>
 * 연관관계를 달지 않는다 — 이 엔티티를 축으로 한 조인 조회가 없고(집계는 count, 판정은 existsById),
 * 쓰기는 native 2종(INSERT ON CONFLICT · DELETE)이라 이 매핑은 조회만 담당한다
 * (EventNotificationSubscription 선례). created_at 은 native INSERT 가 컬럼을 생략하므로 DB DEFAULT 값이다.
 */
@Entity
@Table(name = "event_video_helpfuls")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventVideoHelpful {

	@EmbeddedId
	private EventVideoHelpfulId id;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;
}
