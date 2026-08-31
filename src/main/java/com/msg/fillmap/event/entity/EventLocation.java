package com.msg.fillmap.event.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

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

	/**
	 * 승인 산출물 위치의 자연키 접두 (MSG-500 D-8) — {@code sub-{submissionNo}-{순번}}. 신청 하나가 만든
	 * 위치를 접두로 역산하고(신청 행에 단일 FK 를 둘 수 없다), 시드 위치와 <b>기여 축</b>을 가르는 표식이기도
	 * 하다: 시드 위치의 노출 영역 기여는 시드 사각형이 정본이고, 이 접두를 가진 위치만 확장 기여분이다.
	 */
	public static final String SUBMISSION_KEY_PREFIX = "sub-";

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

	/**
	 * 노출 중지 시각 (V51, MSG-500 D-3). NULL = 노출 중이고, 값이 있으면 회차 상세·격자 조회·영상 목록·
	 * 업로드·직접 접근에서 전부 빠진다. 시드 위치는 전부 NULL 이라 기존 행사 조회 동작이 불변이다.
	 */
	@Column(name = "hidden_at")
	private LocalDateTime hiddenAt;

	/**
	 * 참여 속성 6종 (V51, MSG-500 D-8) — 이벤트 참여형 승인분만 값을 갖고 시드 위치는 전부 NULL 이다.
	 * 신청 <b>한 건</b>의 값이 그 신청이 만든 모든 위치로 복사된다(속성이 신청 단위라는 PRD v2.2 확정 1).
	 * <p>
	 * {@code startsOn}·{@code endsOn} 은 <b>표기 정보</b>이지 노출 창이 아니다 — 위치 노출은 종전대로 부모
	 * 회차 생명주기와 {@link #hiddenAt} 이 지배한다. 노출 창으로 해석하면 위치별 상태 판정 축이 하나 더
	 * 생기는데 PRD v2.2 는 "부가 정보"라고만 확정했다(미해결 질문).
	 */
	@Column(name = "organizer_name", length = 100)
	private String organizerName;

	@Column(name = "description")
	private String description;

	@Column(name = "starts_on")
	private LocalDate startsOn;

	@Column(name = "ends_on")
	private LocalDate endsOn;

	@Column(name = "participation_method")
	private String participationMethod;

	/** 공개 프리픽스 사본 키 (D-6). 공개 주소 조립은 조회 시점이라 여기엔 키만 저장한다. */
	@Column(name = "image_key", length = 255)
	private String imageKey;

	public EventLocation(EventOccurrence occurrence, String locationKey) {
		this.occurrence = occurrence;
		this.locationKey = locationKey;
	}

	/**
	 * 승인 산출물 위치 (MSG-500 D-8) — 이벤트 참여형 신청이 승인될 때 부모 회차 아래 만들어진다.
	 * 시더가 쓰는 {@link #update} 와 갈라 두는 것은 이 경로가 <b>참여 속성까지 한 번에</b> 정하고 다시
	 * 갱신되지 않기 때문이다(재시드 대상이 아니다 — locationKey 접두 {@code sub-} 가 그 표식이다).
	 */
	public static EventLocation forSubmission(EventOccurrence occurrence, String locationKey, String name,
		EventLocationType type, int displayOrder, String representativeGridId, String organizerName,
		String description, LocalDate startsOn, LocalDate endsOn, String participationMethod, String imageKey) {
		EventLocation location = new EventLocation(occurrence, locationKey);
		location.name = name;
		location.type = type;
		location.displayOrder = displayOrder;
		location.representativeGridId = representativeGridId;
		location.organizerName = organizerName;
		location.description = description;
		location.startsOn = startsOn;
		location.endsOn = endsOn;
		location.participationMethod = participationMethod;
		location.imageKey = imageKey;
		return location;
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
