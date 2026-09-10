package com.msg.fillmap.event.entity;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 행사 영상 도움돼요 복합 기본키 (video_id, user_id) — "사용자당 1회"를 DDL 이 보장하는 축이다 (MSG-441).
 * 상세의 helpfulByMe 판정이 이 키로 existsById 단건 히트하므로 equals/hashCode 가 계약이다
 * (EventNotificationSubscriptionId 동형). video_id 선두라 영상별 집계도 이 PK 인덱스를 탄다.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventVideoHelpfulId implements Serializable {

	@Column(name = "video_id", nullable = false)
	private Long videoId;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	public EventVideoHelpfulId(Long videoId, Long userId) {
		this.videoId = videoId;
		this.userId = userId;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof EventVideoHelpfulId that)) {
			return false;
		}
		return Objects.equals(videoId, that.videoId) && Objects.equals(userId, that.userId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(videoId, userId);
	}
}
