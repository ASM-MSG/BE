package com.msg.fillmap.event.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 행사 회차 (event_occurrences, MSG-438). 노출 사각형(min/max_grid_y/x)은 뷰포트 겹침 판정 재료고,
 * visible_from 은 starts_at - 14일 파생 고정값이라 시더가 항상 함께 세팅한다(DDL CHECK 가 다른 쓰기 경로도 막는다).
 * 상태와 업로드 마감은 저장하지 않는다 — 일정이 바뀌면 저장 파생값이 어긋나므로 계산으로만 둔다.
 */
@Entity
@Table(name = "event_occurrences")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventOccurrence {

	/** 종료 후 영상 업로드가 열려 있는 기간 (PRD §4.2). */
	public static final int UPLOAD_GRACE_DAYS = 30;

	/** 칩 노출 시작이 앞당겨지는 기간 — "행사 시작 2주 전" (2026-08-20 확정). */
	public static final int VISIBLE_BEFORE_DAYS = 14;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "event_series_id", nullable = false)
	private EventSeries series;

	@Column(name = "occurrence_key", length = 60, nullable = false, unique = true)
	private String occurrenceKey;

	@Column(name = "title", length = 100, nullable = false)
	private String title;

	@Column(name = "city_name", length = 30, nullable = false)
	private String cityName;

	@Column(name = "starts_at", nullable = false)
	private LocalDateTime startsAt;

	@Column(name = "ends_at", nullable = false)
	private LocalDateTime endsAt;

	@Column(name = "visible_from", nullable = false)
	private LocalDateTime visibleFrom;

	@Column(name = "min_grid_y", nullable = false)
	private Integer minGridY;

	@Column(name = "max_grid_y", nullable = false)
	private Integer maxGridY;

	@Column(name = "min_grid_x", nullable = false)
	private Integer minGridX;

	@Column(name = "max_grid_x", nullable = false)
	private Integer maxGridX;

	public EventOccurrence(EventSeries series, String occurrenceKey) {
		this.series = series;
		this.occurrenceKey = occurrenceKey;
	}

	/**
	 * 재시드 갱신. starts_at 과 visible_from 을 항상 함께 세팅한다 — 한 flush 의 단일 UPDATE 로 두 컬럼이
	 * 같이 나가므로 행 단위 CHECK 등식(visible_from = starts_at - 14일)이 중간 상태 없이 유지된다.
	 */
	public void update(EventSeries series, String title, String cityName, LocalDateTime startsAt,
		LocalDateTime endsAt, int minGridY, int maxGridY, int minGridX, int maxGridX) {
		this.series = series;
		this.title = title;
		this.cityName = cityName;
		this.startsAt = startsAt;
		this.endsAt = endsAt;
		this.visibleFrom = startsAt.minusDays(VISIBLE_BEFORE_DAYS);
		this.minGridY = minGridY;
		this.maxGridY = maxGridY;
		this.minGridX = minGridX;
		this.maxGridX = maxGridX;
	}

	/** 업로드 마감 = 종료 30일 후 (PRD §4.2). */
	public LocalDateTime uploadClosesAt() {
		return endsAt.plusDays(UPLOAD_GRACE_DAYS);
	}

	/**
	 * 주어진 시각의 파생 상태. 경계는 전부 반개구간이라 정각은 다음 상태에 속한다 —
	 * 상태 판정의 빈틈과 겹침이 없다. now 는 호출자가 주입한다(시각 컨벤션 MSG-376).
	 */
	public EventStatus statusAt(LocalDateTime now) {
		if (now.isBefore(startsAt)) {
			return EventStatus.UPCOMING;
		}
		if (now.isBefore(endsAt)) {
			return EventStatus.LIVE;
		}
		if (now.isBefore(uploadClosesAt())) {
			return EventStatus.UPLOAD_GRACE;
		}
		return EventStatus.ARCHIVED;
	}
}
