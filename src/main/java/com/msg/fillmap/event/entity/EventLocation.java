package com.msg.fillmap.event.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 행사 위치 (event_locations, MSG-438). 영역은 event_location_grids 행들이고, 영상은 그중
 * representative_grid_id 하나에만 붙는다 (FR-EVENT-08). 대표 격자가 영역 소속이라는 보장은
 * 지연 FK(fk_event_loc_rep_grid)가 커밋 시점에 검증한다.
 */
@Entity
@Table(name = "event_locations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventLocation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "event_occurrence_id", nullable = false)
	private EventOccurrence occurrence;

	@Column(name = "location_key", length = 60, nullable = false, unique = true)
	private String locationKey;

	@Column(name = "name", length = 100, nullable = false)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", length = 20, nullable = false)
	private EventLocationType type;

	@Column(name = "operating_hours", length = 100)
	private String operatingHours;

	@Column(name = "display_order", nullable = false)
	private Integer displayOrder;

	@Column(name = "representative_grid_id", length = 20, nullable = false)
	private String representativeGridId;

	public EventLocation(EventOccurrence occurrence, String locationKey) {
		this.occurrence = occurrence;
		this.locationKey = locationKey;
	}

	/** 재시드 갱신 — 대표 격자는 매 실행 3단 규칙으로 재계산된 값이 들어온다. */
	public void update(EventOccurrence occurrence, String name, EventLocationType type, String operatingHours,
		int displayOrder, String representativeGridId) {
		this.occurrence = occurrence;
		this.name = name;
		this.type = type;
		this.operatingHours = operatingHours;
		this.displayOrder = displayOrder;
		this.representativeGridId = representativeGridId;
	}
}
