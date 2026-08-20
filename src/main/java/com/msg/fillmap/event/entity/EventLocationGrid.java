package com.msg.fillmap.event.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 행사 위치의 영역 격자 한 칸 (event_location_grids, MSG-438). 회차 id 는 "한 회차 안에서 격자는
 * 위치 하나에만 귀속"을 단일 UNIQUE 로 표현하기 위한 비정규화 값이고, 그 값의 정합은 복합 FK 가 지킨다.
 * location 연관은 조회 전용(insertable=false)이라 쓰기는 복합키와 회차 컬럼으로만 이뤄진다.
 */
@Entity
@Table(name = "event_location_grids")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventLocationGrid {

	@EmbeddedId
	private EventLocationGridId id;

	@Column(name = "event_occurrence_id", nullable = false)
	private Long eventOccurrenceId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "event_location_id", insertable = false, updatable = false)
	private EventLocation location;

	public EventLocationGrid(Long eventLocationId, Long eventOccurrenceId, String gridId) {
		this.id = new EventLocationGridId(eventLocationId, gridId);
		this.eventOccurrenceId = eventOccurrenceId;
	}

	public Long getEventLocationId() {
		return id.getEventLocationId();
	}

	public String getGridId() {
		return id.getGridId();
	}
}
