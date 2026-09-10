package com.msg.fillmap.event.submission.entity;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.BatchSize;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 신청 위치 하나 (MSG-498 FR-9). <b>이름 필드가 없다</b> — 화면 식별은 순번(displayOrder, 1부터)과
 * 대표 격자에서 계산한 지역 라벨로 한다 (피그마 #102).
 * <p>
 * 사각형은 {@link ElementCollection} 이다. 위치당 최대 81개로 상한이 분명하고 위치 없이 홀로 의미가 없어
 * 별도 엔티티일 이유가 없다. {@code @BatchSize} 는 상세 응답이 위치 수만큼 사각형 조회를 내는 N+1 을 막는다.
 * <p>
 * 신청으로 가는 {@code @ManyToOne} 이 <b>연관의 주인</b>이다 (스펙 D-12) — FK 를 가진 쪽이 주인이라야
 * Hibernate 가 자식 INSERT 에 FK 를 싣고 끝낸다. 반대편 컬렉션은 {@code mappedBy} 로 읽기 쪽이 되고,
 * 양쪽 참조는 {@link EventSubmission#replaceLocations} 가 캡슐화해 맞춘다. 이 필드는
 * equals·hashCode·toString 어디에도 넣지 않는다(Lombok 생성 대상에서 제외 — 프록시 초기화·순환 참조 방지).
 */
@Entity
@Table(name = "event_submission_locations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventSubmissionLocation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "event_submission_id", nullable = false)
	private EventSubmission submission;

	@Column(name = "display_order", nullable = false)
	private Integer displayOrder;

	@Column(name = "representative_grid_id", length = 20, nullable = false)
	private String representativeGridId;

	@ElementCollection(fetch = FetchType.LAZY)
	@CollectionTable(
		name = "event_submission_location_rects",
		joinColumns = @JoinColumn(name = "event_submission_location_id"))
	@BatchSize(size = 20)
	private List<EventSubmissionAreaRect> rects = new ArrayList<>();

	public EventSubmissionLocation(String representativeGridId, List<EventSubmissionAreaRect> rects) {
		this.representativeGridId = representativeGridId;
		this.rects = new ArrayList<>(rects);
	}

	/**
	 * 애그리거트에 붙으며 부모와 순번을 받는다. 패키지 전용이고 호출자는 {@link EventSubmission#replaceLocations}
	 * 하나다 — 양쪽 참조를 맞추는 책임을 서비스에 흘리지 않으려고 편의 메서드로 가둔다.
	 */
	void attachTo(EventSubmission submission, int displayOrder) {
		this.submission = submission;
		this.displayOrder = displayOrder;
	}
}
