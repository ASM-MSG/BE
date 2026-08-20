package com.msg.fillmap.event.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 행사 시리즈 (event_series, MSG-438). 같은 행사가 다시 열려도 회차 데이터가 섞이지 않도록
 * 시리즈와 회차를 나눈 것의 상위 단위다 (FR-EVENT-07). series_key 는 재시딩·환경 무관 자연키.
 */
@Entity
@Table(name = "event_series")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventSeries {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "series_key", length = 50, nullable = false, unique = true)
	private String seriesKey;

	@Column(name = "name", length = 100, nullable = false)
	private String name;

	public EventSeries(String seriesKey, String name) {
		this.seriesKey = seriesKey;
		this.name = name;
	}

	/** 재시드 갱신 — 자연키(series_key)는 불변이고 표시명만 바뀐다. */
	public void updateName(String name) {
		this.name = name;
	}
}
