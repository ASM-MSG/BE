package com.msg.fillmap.event.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.msg.fillmap.video.entity.Video;

/**
 * 행사 영상 연결 (event_videos, MSG-438). 독립 파일 컬럼을 갖지 않고 기존 videos 행을 1:1 로 확장한다 —
 * 신고·인코딩·AI 블러·소프트 삭제·재생 접근 제어가 전부 videos 행을 축으로 돌기 때문이다(PRD §10).
 * video_id 가 PK 라 한 영상은 최대 하나의 행사 위치에만 연결된다.
 * Video 참조는 읽기 전용이고 상태 변경은 video 도메인 서비스만 한다(컨벤션 "타 도메인 엔티티 읽기 전용").
 */
@Entity
@Table(name = "event_videos")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventVideo {

	@Id
	@Column(name = "video_id")
	private Long videoId;

	@MapsId
	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "video_id")
	private Video video;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "event_location_id", nullable = false)
	private EventLocation location;

	/** 위치의 회차 값을 서비스가 세팅한다. 정합(영상 회차 = 위치 회차)은 복합 FK 가 검증한다. */
	@Column(name = "event_occurrence_id", nullable = false)
	private Long eventOccurrenceId;

	public EventVideo(Video video, EventLocation location, Long eventOccurrenceId) {
		this.video = video;
		this.location = location;
		this.eventOccurrenceId = eventOccurrenceId;
	}
}
