package com.msg.fillmap.event.submission.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 행사 등재 신청 (event_submissions, MSG-498). 신청 번호는 전역 시퀀스 + KST 연도 라벨이라 발급 뒤 불변이고,
 * 재제출로도 바뀌지 않는다 (D-4).
 * <p>
 * 위치는 {@code @OneToMany}(cascade ALL · orphanRemoval)로 애그리거트에 담는다 — 신청은 폼 하나로 통째로
 * 만들어졌다 폼 하나로 통째로 고쳐지는 단위이고 위치 수 상한이 20이라(D-10), 재제출의 전체 교체가
 * {@link #replaceLocations} 한 번으로 끝난다.
 * <p>
 * 반려 사유는 여기 두지 않는다 (D-3) — 저장 원천은 이력 테이블 하나이고, 상세의 "반려 항목"은 이력 최신
 * 행에서 읽는다. 두 곳에 두면 MSG-500 이 쓰기마다 둘을 맞춰야 한다.
 * {@code userId} 를 연관으로 잡지 않은 것은 이 티켓의 어떤 응답도 User 데이터를 싣지 않아 조인이 필요
 * 없어서다.
 */
@Entity
@Table(name = "event_submissions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventSubmission {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "submission_no", length = 20, nullable = false, unique = true)
	private String submissionNo;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", length = 20, nullable = false)
	private EventSubmissionType type;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", length = 20, nullable = false)
	private EventSubmissionStatus status;

	@Column(name = "title", length = 100, nullable = false)
	private String title;

	@Column(name = "organizer_name", length = 100, nullable = false)
	private String organizerName;

	@Column(name = "starts_on", nullable = false)
	private LocalDate startsOn;

	@Column(name = "ends_on", nullable = false)
	private LocalDate endsOn;

	@Column(name = "operating_hours", length = 100)
	private String operatingHours;

	@Column(name = "program_description")
	private String programDescription;

	@Column(name = "participation_method")
	private String participationMethod;

	/**
	 * 참여 대상 승인 이벤트 회차 (MSG-502) — EVENT 유형에만 값이 있고 재제출로도 바뀌지 않는다 (D-3).
	 * 연관 없이 id 만 보관하는 것은 이 티켓이 부모에서 읽는 값이 상세의 title 하나뿐이라 조인이 필요 없어서다
	 * ({@code userId} 와 같은 근거). 유형과 값의 짝은 DB CHECK(chk_event_sub_parent)가 강제한다.
	 */
	@Column(name = "parent_event_occurrence_id")
	private Long parentEventOccurrenceId;

	@Column(name = "description", nullable = false)
	private String description;

	@Column(name = "image_key", length = 255, nullable = false)
	private String imageKey;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	/**
	 * 심사 결과 4종 (MSG-500 V51). 승인 번호와 상태 전이는 조건부 UPDATE 가 한 문장으로 채우고(동시 심사
	 * 직렬화), 여기서는 그 결과를 읽는다 — 그래서 approve 전이 메서드가 없다.
	 * chk_event_sub_approval 이 "승인 행 = 승인 번호 있는 행"을 DB 에서 강제한다.
	 */
	@Column(name = "approval_no", length = 20, unique = true)
	private String approvalNo;

	/** 승인 산출물 링크 — 미션 경로에서만 값을 갖는다. 참여형은 위치가 여러 행이라 locationKey 접두로 역산한다. */
	@Column(name = "published_mission_id")
	private Long publishedMissionId;

	@Column(name = "unpublished_at")
	private LocalDateTime unpublishedAt;

	@Column(name = "unpublish_reason")
	private String unpublishReason;

	// 컬렉션 매핑의 근거(컨벤션 영속 계층 2항): 신청은 폼 하나로 통째로 만들어졌다 통째로 교체되는
	// 애그리거트이고 위치 수 상한이 20이라(D-10) 크기가 분명하다. 연관 주인은 FK 를 가진 자식 쪽
	// @ManyToOne 이고 여기는 mappedBy 읽기 쪽이다 — 그래야 자식 INSERT 한 번으로 FK 가 채워진다.
	@OneToMany(mappedBy = "submission", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
	@OrderBy("displayOrder")
	private List<EventSubmissionLocation> locations = new ArrayList<>();

	private EventSubmission(String submissionNo, Long userId, EventSubmissionType type,
		Long parentEventOccurrenceId, LocalDateTime now) {
		this.submissionNo = submissionNo;
		this.userId = userId;
		this.type = type;
		this.parentEventOccurrenceId = parentEventOccurrenceId;
		this.status = EventSubmissionStatus.IN_REVIEW;
		this.createdAt = now;
		this.updatedAt = now;
	}

	/**
	 * 제출 (FR-10). 접수된 신청은 언제나 심사 중에서 시작한다. 폼 내용은 {@link #updateForm} 가 채운다.
	 * 부모 회차는 유형과 함께 여기서만 정해진다 — 둘 다 재제출로 바꿀 수 없어 {@link #updateForm} 에 없다.
	 */
	public static EventSubmission submit(String submissionNo, Long userId, EventSubmissionType type,
		Long parentEventOccurrenceId, LocalDateTime now) {
		return new EventSubmission(submissionNo, userId, type, parentEventOccurrenceId, now);
	}

	/**
	 * 폼 내용 전체 교체 (제출·재제출 공용). 재제출이 부분 수정이 아니라 전체 교체라 두 경로가 같은 메서드다
	 * (D-8 — 유형만 불변이라 인자에 없다).
	 */
	public void updateForm(String title, String organizerName, LocalDate startsOn, LocalDate endsOn,
		String operatingHours, String programDescription, String participationMethod, String description,
		String imageKey, LocalDateTime now) {
		this.title = title;
		this.organizerName = organizerName;
		this.startsOn = startsOn;
		this.endsOn = endsOn;
		this.operatingHours = operatingHours;
		this.programDescription = programDescription;
		this.participationMethod = participationMethod;
		this.description = description;
		this.imageKey = imageKey;
		this.updatedAt = now;
	}

	/**
	 * 위치 목록 통째 교체 — 기존 행은 orphanRemoval 로 사라진다. 양방향 참조를 맞추는 편의 메서드이기도 하다:
	 * 자식의 부모 참조({@code submission})와 부모의 컬렉션을 여기 한 곳에서만 함께 세팅해, 한쪽만 갱신된
	 * 상태가 만들어질 자리를 없앤다. 순번도 여기서 매긴다 — "배열 순서가 곧 순번"이 위치의 유일한 식별
	 * 수단(이름이 없다)이라 그 불변식을 애그리거트 밖에 맡기지 않는다.
	 */
	public void replaceLocations(List<EventSubmissionLocation> newLocations) {
		locations.clear();
		int displayOrder = 1;
		for (EventSubmissionLocation location : newLocations) {
			location.attachTo(this, displayOrder++);
			locations.add(location);
		}
	}

	/**
	 * 승인 산출물 링크 기록 (MSG-500 D-2) — 상태 전이는 조건부 UPDATE 가 이미 끝냈고, 그 뒤 재로드한
	 * 애그리거트에 미션 id 를 달아 더티 체킹이 UPDATE 를 내보낸다 (setter 를 열지 않는 상태 전이 메서드,
	 * {@code Video.markBlinded()} 선례).
	 */
	public void linkPublishedMission(Long missionId) {
		this.publishedMissionId = missionId;
	}
}
