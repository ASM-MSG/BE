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

	/** 공개 프리픽스 사본 키 (MSG-538). 공개 주소 조립은 조회 시점이라 여기엔 키만 저장한다. */
	@Column(name = "image_key", length = 255)
	private String imageKey;

	/**
	 * 일정 개정 번호 (MSG-442) — 시각(starts_at·ends_at)이 실제로 바뀐 재시드마다 +1 하는 단조 증가값이다.
	 * 일정 변경 알림의 dedupe 키 재료이고, 시각값을 키로 쓰면 일정 왕복(A→B→A→B)에서 두 번째 "B로 변경"이
	 * 첫 번째와 같은 키가 되어 억제되기 때문에 번호를 쓴다.
	 */
	@Column(name = "schedule_revision", nullable = false)
	private Integer scheduleRevision = 0;

	public EventOccurrence(EventSeries series, String occurrenceKey) {
		this.series = series;
		this.occurrenceKey = occurrenceKey;
	}

	/**
	 * 재시드 갱신. starts_at 과 visible_from 을 항상 함께 세팅한다 — 한 flush 의 단일 UPDATE 로 두 컬럼이
	 * 같이 나가므로 행 단위 CHECK 등식(visible_from = starts_at - 14일)이 중간 상태 없이 유지된다.
	 * <p>
	 * 시각이 실제로 바뀐 경우에만 {@link #scheduleRevision} 을 +1 한다 (MSG-442) — 값이 같은 재시드는
	 * 번호가 불변이라 멱등이 유지되고, 시더가 이 번호로 일정 변경 알림 발송 여부를 판단한다.
	 */
	public void update(EventSeries series, String title, String cityName, LocalDateTime startsAt,
		LocalDateTime endsAt, int minGridY, int maxGridY, int minGridX, int maxGridX) {
		if (isScheduleChanged(startsAt, endsAt)) {
			this.scheduleRevision = this.scheduleRevision + 1;
		}
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

	/**
	 * 대표 이미지 갱신 (MSG-538). 일정 갱신과 메서드를 나눈 이유는 둘이 서로 다른 경로에서 오기 때문이다.
	 * 이미지는 시더만 쓰고, 승인 반영(노출 영역 확장)은 {@link #update} 만 쓴다. 한 메서드로 합치면 승인
	 * 경로가 자기 이미지 키를 자기에게 다시 넘기는 인자를 들고 다녀야 하고, 그 자리를 null 로 잘못 채우면
	 * 승인 때마다 대표 이미지가 지워진다.
	 * <p>
	 * 일정 개정 번호({@link #scheduleRevision})는 건드리지 않는다 (D3). 포스터만 갈아 끼운 재시드가
	 * 알림을 켠 사용자 전원에게 "행사 일정이 변경됐어요" 를 보내면 안 되기 때문이다.
	 * <p>
	 * 시드 파일이 정본이라 항목이 사라지면 null 이 들어와 저장값도 비워진다 (D7).
	 */
	public void updateImageKey(String imageKey) {
		this.imageKey = imageKey;
	}

	/**
	 * 최초 세팅(startsAt 이 아직 null 인 신규 회차)은 변경이 아니다 — 개정 0 으로 태어나야 첫 시딩이
	 * 존재하지도 않던 일정의 "변경 알림"을 만들지 않는다.
	 */
	private boolean isScheduleChanged(LocalDateTime startsAt, LocalDateTime endsAt) {
		return this.startsAt != null && (!this.startsAt.equals(startsAt) || !this.endsAt.equals(endsAt));
	}

	/**
	 * 노출 판정 (MSG-439 §API 명세 존재 은닉) — 아직 노출 시작 전인 예정 회차만 감춘다. 조회 네 경로와
	 * 알림 구독(MSG-442)이 이 술어 하나를 공유한다. 갈라지면 미공개 회차의 존재가 한쪽 경로로만 새어 나간다.
	 */
	public boolean isVisibleAt(LocalDateTime now) {
		return statusAt(now) != EventStatus.UPCOMING || !visibleFrom.isAfter(now);
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
