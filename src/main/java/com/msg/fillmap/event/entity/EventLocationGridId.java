package com.msg.fillmap.event.entity;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * event_location_grids 복합 기본키 (event_location_id, grid_id) — 격자 귀속의 정체성은 위치×격자 쌍이다.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventLocationGridId implements Serializable {

	@Column(name = "event_location_id", nullable = false)
	private Long eventLocationId;

	@Column(name = "grid_id", nullable = false, length = 20)
	private String gridId;

	public EventLocationGridId(Long eventLocationId, String gridId) {
		this.eventLocationId = eventLocationId;
		this.gridId = gridId;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof EventLocationGridId that)) {
			return false;
		}
		return Objects.equals(eventLocationId, that.eventLocationId) && Objects.equals(gridId, that.gridId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(eventLocationId, gridId);
	}
}
