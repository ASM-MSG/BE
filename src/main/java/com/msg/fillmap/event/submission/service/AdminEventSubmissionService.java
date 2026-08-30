package com.msg.fillmap.event.submission.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.event.entity.EventLocation;
import com.msg.fillmap.event.entity.EventLocationGrid;
import com.msg.fillmap.event.entity.EventLocationType;
import com.msg.fillmap.event.entity.EventOccurrence;
import com.msg.fillmap.event.exception.EventErrorCode;
import com.msg.fillmap.event.repository.EventLocationGridRepository;
import com.msg.fillmap.event.repository.EventLocationRepository;
import com.msg.fillmap.event.repository.EventOccurrenceRepository;
import com.msg.fillmap.event.support.EventExposureArea;
import com.msg.fillmap.event.submission.dto.AdminEventSubmissionDetailResponseDto;
import com.msg.fillmap.event.submission.dto.AdminEventSubmissionListResponseDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionApproveResponseDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionAreaRectDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionHistoryResponseDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionParentEventResponseDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionRejectRequestDto;
import com.msg.fillmap.event.submission.entity.EventSubmission;
import com.msg.fillmap.event.submission.entity.EventSubmissionAreaRect;
import com.msg.fillmap.event.submission.entity.EventSubmissionLocation;
import com.msg.fillmap.event.submission.entity.EventSubmissionReasonCode;
import com.msg.fillmap.event.submission.entity.EventSubmissionStatus;
import com.msg.fillmap.event.submission.entity.EventSubmissionStatusHistory;
import com.msg.fillmap.event.submission.repository.EventSubmissionRepository;
import com.msg.fillmap.event.submission.repository.EventSubmissionStatusHistoryRepository;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.global.geo.AreaCell;
import com.msg.fillmap.mission.entity.MissionType;
import com.msg.fillmap.mission.service.MissionRegistrationService;
import com.msg.fillmap.mission.service.MissionRegistrationService.MissionRegistration;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 관리자 심사 (MSG-500 §API 1~4). 행사 운영자 조회(MSG-498)와 <b>존재 은닉이 반대다</b> — 소유 술어가
 * 없고 없는 id 는 그대로 404(13430)다. 관리자는 전체를 보는 주체라 숨길 대상이 없다.
 *
 * <p>승인·반려는 조건부 UPDATE 하나로 상태를 전이한다 — 읽고 확인한 뒤 쓰면 동시 심사 두 건이 같은
 * IN_REVIEW 를 보고 둘 다 성공한다. 전이·이력·산출물 생성·이미지 복사 보상이 전부 한 트랜잭션이라
 * "미션은 생겼는데 신청은 심사 중" 같은 어긋난 절반이 남지 않는다.
 *
 * <p>역할 검사는 여기에 없다. SecurityConfig 의 {@code /api/admin/**} matcher 가 필터 단계에서 거른다
 * (AdminOrgAccountController 선례).
 */
@Service
public class AdminEventSubmissionService {

	private static final int MIN_PAGE_SIZE = 1;
	private static final int MAX_PAGE_SIZE = 100;

	/** event_locations.name 컬럼 길이 — 순번 접미사를 붙여도 넘지 않게 제목을 먼저 자르는 기준이다 (D-8). */
	private static final int MAX_LOCATION_NAME_LENGTH = 100;

	/** 승인 번호의 연도 라벨과 기간 판정의 "오늘"은 사용자·관리자가 읽는 값이라 KST 다 (D-4, 접수 선례). */
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final EventSubmissionRepository submissionRepository;
	private final EventSubmissionStatusHistoryRepository historyRepository;
	private final EventSubmissionImageStore imageStore;
	private final EventSubmissionLocationView locationView;
	private final UserRepository userRepository;
	private final MissionRegistrationService missionRegistrationService;
	private final EventOccurrenceRepository occurrenceRepository;
	private final EventLocationRepository locationRepository;
	private final EventLocationGridRepository locationGridRepository;
	private final Clock clock;

	/** 프로덕션 생성자 — clock 을 systemUTC 로 고정해 전체 생성자로 위임한다 (EventSubmissionServiceImpl 선례). */
	@Autowired
	public AdminEventSubmissionService(EventSubmissionRepository submissionRepository,
		EventSubmissionStatusHistoryRepository historyRepository, EventSubmissionImageStore imageStore,
		EventSubmissionLocationView locationView, UserRepository userRepository,
		MissionRegistrationService missionRegistrationService, EventOccurrenceRepository occurrenceRepository,
		EventLocationRepository locationRepository, EventLocationGridRepository locationGridRepository) {
		this(submissionRepository, historyRepository, imageStore, locationView, userRepository,
			missionRegistrationService, occurrenceRepository, locationRepository, locationGridRepository,
			Clock.systemUTC());
	}

	public AdminEventSubmissionService(EventSubmissionRepository submissionRepository,
		EventSubmissionStatusHistoryRepository historyRepository, EventSubmissionImageStore imageStore,
		EventSubmissionLocationView locationView, UserRepository userRepository,
		MissionRegistrationService missionRegistrationService, EventOccurrenceRepository occurrenceRepository,
		EventLocationRepository locationRepository, EventLocationGridRepository locationGridRepository,
		Clock clock) {
		this.submissionRepository = submissionRepository;
		this.historyRepository = historyRepository;
		this.imageStore = imageStore;
		this.locationView = locationView;
		this.userRepository = userRepository;
		this.missionRegistrationService = missionRegistrationService;
		this.occurrenceRepository = occurrenceRepository;
		this.locationRepository = locationRepository;
		this.locationGridRepository = locationGridRepository;
		this.clock = clock;
	}

	/**
	 * 심사 큐 (§API 1). 상태 필터 기준 접수 최신순 오프셋 페이징이고, 탭 뱃지용 건수 3종은 필터와 무관한
	 * 전체 집계다. 거부 판정은 처음 걸린 하나를 돌려준다: 상태 파싱 실패(13455) → 페이지 범위 밖(13456).
	 */
	@Transactional(readOnly = true)
	public AdminEventSubmissionListResponseDto getSubmissions(String status, int page, int size) {
		EventSubmissionStatus filter = parseStatus(status);
		// PageRequest.of 에 그냥 넘기면 IllegalArgumentException 이 catch-all 핸들러에서 500 이 된다.
		// 오프셋(page*size)이 int 를 넘는 극단 양수도 같다 (MSG-499 관리자 큐 선례, 대역만 event 다).
		if (page < 0 || size < MIN_PAGE_SIZE || size > MAX_PAGE_SIZE
			|| (long) page * size > Integer.MAX_VALUE) {
			throw new ApiException(EventErrorCode.INVALID_PAGE_RANGE);
		}
		return AdminEventSubmissionListResponseDto.of(
			submissionRepository.findAdminPageByStatus(filter, PageRequest.of(page, size)),
			submissionRepository.countByStatus(EventSubmissionStatus.IN_REVIEW),
			submissionRepository.countByStatus(EventSubmissionStatus.APPROVED),
			submissionRepository.countByStatus(EventSubmissionStatus.REJECTED));
	}

	/**
	 * 심사 상세 (§API 2). 폼 필드 전체에 심사 재료 셋을 더한다 — 신청 계정 정보, 노출 영역 사각형, 이력이다.
	 * 위치 표현은 행사 운영자 상세와 같은 조립기를 쓴다({@link EventSubmissionLocationView}) — 심사자와
	 * 신청자가 서로 다른 그림을 보면 반려 사유가 가리키는 대상이 어긋난다.
	 */
	@Transactional(readOnly = true)
	public AdminEventSubmissionDetailResponseDto getSubmission(Long submissionId) {
		EventSubmission submission = submissionRepository.findWithLocationsById(submissionId)
			.orElseThrow(() -> new ApiException(EventErrorCode.SUBMISSION_NOT_FOUND));
		// 신청 계정은 FK 가 보장하는 존재라 빈 결과가 정상 흐름에 없다 — 그래도 삼키지 않고 1404 로 드러낸다.
		User applicant = userRepository.findById(submission.getUserId())
			.orElseThrow(() -> new ApiException(UserErrorCode.USER_NOT_FOUND));

		return new AdminEventSubmissionDetailResponseDto(
			submission.getId(),
			submission.getSubmissionNo(),
			submission.getType().name(),
			submission.getStatus().name(),
			submission.getTitle(),
			submission.getOrganizerName(),
			submission.getStartsOn(),
			submission.getEndsOn(),
			submission.getOperatingHours(),
			submission.getProgramDescription(),
			submission.getParticipationMethod(),
			toParentEvent(submission),
			submission.getDescription(),
			imageStore.presignGet(submission.getImageKey()),
			applicant.getOrgName(),
			applicant.getNickname(),
			applicant.getEmail(),
			locationView.describe(submission.getLocations()),
			exposureRect(submission.getLocations()),
			historyRepository.findByEventSubmissionIdOrderByIdAsc(submissionId).stream()
				.map(EventSubmissionHistoryResponseDto::from)
				.toList(),
			submission.getCreatedAt(),
			submission.getUpdatedAt());
	}

	/**
	 * 승인 (§API 3, D-1). 실행 순서가 계약이다.
	 * <p>
	 * ① 신청을 읽어 상태와 기간을 먼저 본다 — 여기서 걸러야 승인 번호 시퀀스를 헛되이 소비하지 않는다
	 * (시퀀스는 롤백해도 되돌아오지 않는다). ② 조건부 UPDATE 로 IN_REVIEW → APPROVED 전이와 승인 번호
	 * 부여를 한 문장에 담는다 — 이것이 동시 승인의 진짜 판정이고 ①은 빠른 거절일 뿐이다. ③ 영향 행이
	 * 0이면 재조회로 가른다: 없으면 13430, 있으면 13450(동시 승인의 패자 포함). ④ 이겼으면 이력을 쌓고
	 * 애그리거트를 <b>재로드</b>해 산출물을 만든다 — 벌크 UPDATE 가 영속성 컨텍스트를 우회했으므로 ① 에서
	 * 읽은 엔티티는 스테일한 IN_REVIEW 다.
	 * <p>
	 * 전이·이력·미션 등재·이미지 복사 보상이 전부 이 트랜잭션 하나다. 뒤에서 실패하면 미션도 승인 상태도
	 * 함께 사라지고 공개 이미지 사본은 롤백 보상이 지운다.
	 */
	@Transactional
	public EventSubmissionApproveResponseDto approve(Long submissionId) {
		EventSubmission submission = findForReview(submissionId);
		requirePeriodNotPassed(submission);

		LocalDateTime now = LocalDateTime.now(clock);
		String approvalNo = nextApprovalNo();
		if (submissionRepository.approveInReview(submissionId, approvalNo, now) == 0) {
			throw transitionFailure(submissionId);
		}
		historyRepository.save(EventSubmissionStatusHistory.approved(submissionId, now));

		EventSubmission approved = submissionRepository.findWithLocationsById(submissionId)
			.orElseThrow(() -> new ApiException(EventErrorCode.SUBMISSION_NOT_FOUND));
		// 참여형은 산출물이 여러 위치라 실을 단일 링크가 없다 — 그 경우 publish 가 null 을 준다.
		Long missionId = publish(approved);
		if (missionId != null) {
			approved.linkPublishedMission(missionId);
		}
		return new EventSubmissionApproveResponseDto(submissionId, approvalNo,
			EventSubmissionStatus.APPROVED.name());
	}

	/**
	 * 반려 (§API 4, FR-19). 승인과 같은 원자 전이이고, 사유는 신청 행이 아니라 이력의 반려 행에 쌓인다
	 * (D-3 저장 원천 단일). 항목 코드 검증을 전이보다 먼저 하는 것은 잘못된 요청이 상태를 바꾸지 못하게
	 * 하기 위해서다. <b>메일은 보내지 않는다</b> — 행사 운영자는 콘솔 상세에서 항목과 사유를 본다(D-5).
	 */
	@Transactional
	public void reject(Long submissionId, EventSubmissionRejectRequestDto request) {
		List<EventSubmissionReasonCode> reasonCodes = parseReasonCodes(request.reasonCodes());

		LocalDateTime now = LocalDateTime.now(clock);
		if (submissionRepository.rejectInReview(submissionId, now) == 0) {
			throw transitionFailure(submissionId);
		}
		historyRepository.save(
			EventSubmissionStatusHistory.rejected(submissionId, reasonCodes, request.reasonText(), now));
	}

	/**
	 * 승인 산출물 (D-1 유형별 분기) — 지역축제·팝업스토어는 <b>미션 1건</b>이 되고(위치가 여럿이어도 칩
	 * 카드는 하나가 자연스럽다), 이벤트 참여형은 부모 회차 아래 <b>행사 위치</b>가 된다.
	 * <p>
	 * switch 에 default 를 두지 않는다 — 유형이 또 늘면 여기서 컴파일이 깨져 새 유형이 아무 산출물 없이
	 * 승인되는 경로가 만들어지지 않는다(MSG-502 참여형 값 추가 때 실제로 이 가드가 발화했다).
	 *
	 * @return 미션 경로면 미션 id, 참여형이면 null (산출물이 여러 위치라 신청 행에 실을 단일 링크가 없다)
	 */
	private Long publish(EventSubmission submission) {
		return switch (submission.getType()) {
			// 지도 홈 "축제" 칩 — 행사 카테고리 "이벤트"와 무관하다.
			case FESTIVAL -> registerMission(submission, MissionType.EVENT);
			case POPUP -> registerMission(submission, MissionType.POPUP);
			case EVENT -> {
				publishParticipation(submission);
				yield null;
			}
		};
	}

	/** 미션 등재 (D-2). 운영 시간은 팝업만 값을 갖는다 — 접수 검증이 축제의 운영 시간을 애초에 거부한다. */
	private long registerMission(EventSubmission submission, MissionType missionType) {
		return missionRegistrationService.register(new MissionRegistration(
			missionType,
			submission.getTitle(),
			submission.getStartsOn(),
			submission.getEndsOn(),
			submission.getSubmissionNo(),
			submission.getDescription(),
			submission.getOperatingHours(),
			imageStore.copyToMissionImage(submission.getImageKey()),
			EventSubmissionCells.union(submission.getLocations()).stream().map(AreaCell::gridId).toList()));
	}

	/**
	 * 이벤트 참여형 → 부모 회차 아래 행사 위치 (D-8·D-9). 순서가 계약이다.
	 * <p>
	 * ① 부모 회차를 <b>비관 잠금</b>으로 잡아 같은 회차의 승인을 직렬화한다 — 잠금이 없으면 서로 다른 두
	 * 신청의 겹침 사전 검사가 모두 통과하고, 회차 내 격자 단일 귀속이 지연 제약이라 지는 쪽이 커밋 시점
	 * 500 이 된다. ② 끝난 회차는 반영할 노출이 없으므로 13451 이다. ③ 겹침을 <b>두 방향</b>으로 검사한다:
	 * 회차의 기존 격자와, 이번 신청의 위치들끼리. 후자가 필요한 이유는 접수 검증이 위치를 독립적으로 봐서
	 * 위치 간 겹침이 심사까지 통과해 오기 때문이다. 어느 쪽이든 13452 이고 관리자의 다음 조작은 AREA 반려다.
	 * ④ 위치를 전개하며 참여 속성을 복사하고 ⑤ 노출 영역을 합집합으로 넓힌다.
	 * <p>
	 * 커버 이미지 공개 사본은 <b>신청당 한 번</b> 복사해 위치들이 공유한다.
	 */
	private void publishParticipation(EventSubmission submission) {
		EventOccurrence occurrence = occurrenceRepository.findWithLockById(submission.getParentEventOccurrenceId())
			.orElseThrow(() -> new ApiException(EventErrorCode.PARENT_EVENT_NOT_FOUND));
		if (!occurrence.getEndsAt().isAfter(LocalDateTime.now(clock))) {
			throw new ApiException(EventErrorCode.SUBMISSION_PERIOD_PASSED);
		}

		List<EventSubmissionLocation> locations = submission.getLocations();
		Set<AreaCell> newCells = requireNoGridConflict(occurrence, locations);
		String imageKey = imageStore.copyToLocationImage(submission.getImageKey());
		int displayOrder = nextDisplayOrder(occurrence.getId());

		for (EventSubmissionLocation location : locations) {
			EventLocation created = locationRepository.save(EventLocation.forSubmission(
				occurrence,
				"%s%s-%d".formatted(EventLocation.SUBMISSION_KEY_PREFIX, submission.getSubmissionNo(),
					location.getDisplayOrder()),
				locationName(submission, locations.size(), location.getDisplayOrder()),
				// 참여 방식 문자열은 위치 유형이 아니다 — 넷에 안 걸리는 위치의 도피처가 ETC 다.
				EventLocationType.ETC,
				displayOrder++,
				// 접수 때 계산해 저장한 위치별 대표 격자를 그대로 쓴다 (FR-9) — 셀 집합 소속이 이미
				// 성립해 지연 FK(fk_event_loc_rep_grid)를 통과한다.
				location.getRepresentativeGridId(),
				submission.getOrganizerName(),
				submission.getDescription(),
				submission.getStartsOn(),
				submission.getEndsOn(),
				submission.getParticipationMethod(),
				imageKey));
			locationGridRepository.saveAll(EventSubmissionCells.of(location).stream()
				.map(cell -> new EventLocationGrid(created.getId(), occurrence.getId(), cell.gridId()))
				.toList());
		}
		expandExposure(occurrence, newCells);
	}

	/**
	 * 위치 이름 (D-8) — 신청 위치에는 이름이 없어서(접수 정책 #102) 신청 제목을 쓰고, 여럿이면 순번을
	 * 붙인다. name·title 이 둘 다 VARCHAR(100) 이라 <b>접미사 길이만큼 제목을 먼저 잘라낸다</b> — 100자
	 * 제목이 그대로 들어오면 접미사를 붙이는 순간 길이 초과로 승인이 DB 오류로 죽는다.
	 * <p>
	 * <b>순번 분기는 현재 입력으로는 도달하지 않는다</b> — PRD v2.2 FR-8 이 참여형 신청의 위치를 대표
	 * 위치 한 곳으로 제한하고 접수가 2곳 이상을 13439 로 막는다. 그래도 남겨 두는 것은 이 메서드가 위치
	 * N개를 전제로 쓰인 D-8 규칙의 구현이고, 제한이 풀리는 날 이름이 조용히 겹치거나 길이로 죽는 것보다
	 * 낫기 때문이다. 검증은 접수를 우회한 SQL 시드로 들어온다
	 * ({@code AdminEventParticipationApprovalTest} 의 두 위치 케이스).
	 */
	private String locationName(EventSubmission submission, int locationCount, int displayOrder) {
		String title = submission.getTitle();
		if (locationCount == 1) {
			return title;
		}
		String suffix = " " + displayOrder;
		int room = MAX_LOCATION_NAME_LENGTH - suffix.length();
		return (title.length() <= room ? title : title.substring(0, room)) + suffix;
	}

	/**
	 * 겹침 사전 검사 (D-9) — 회차 기존 격자와의 겹침, 그리고 이번 신청 위치들 상호 겹침. 삽입 전에
	 * 13452 로 거부하는 것은 지연 제약 위반이 커밋 시점 DataIntegrityViolation 으로 터져 관리자에게
	 * 읽을 수 없는 500 이 되기 때문이다(제약은 그대로 백스톱으로 남는다).
	 *
	 * @return 이번 신청이 새로 차지하는 격자 집합 (노출 영역 확장의 입력)
	 */
	private Set<AreaCell> requireNoGridConflict(EventOccurrence occurrence,
		List<EventSubmissionLocation> locations) {
		Set<String> existing = Set.copyOf(locationGridRepository.findGridIdsByOccurrenceId(occurrence.getId()));
		Set<AreaCell> claimed = new LinkedHashSet<>();
		for (EventSubmissionLocation location : locations) {
			for (AreaCell cell : EventSubmissionCells.of(location)) {
				if (existing.contains(cell.gridId()) || !claimed.add(cell)) {
					throw new ApiException(EventErrorCode.SUBMISSION_GRID_CONFLICT);
				}
			}
		}
		return claimed;
	}

	/** 부모 회차의 다음 표시 순번 — 기존 위치 뒤에 이어 붙인다(기존 위치의 순번은 건드리지 않는다). */
	private int nextDisplayOrder(Long occurrenceId) {
		return locationRepository.findByOccurrenceIdOrderByDisplayOrderAscIdAsc(occurrenceId).stream()
			.mapToInt(EventLocation::getDisplayOrder)
			.max()
			.orElse(0) + 1;
	}

	/**
	 * 노출 영역 확장 (D-8) — "노출 영역 = 위치 사각형들을 감싸는 범위"라는 기존 불변식을 유지한다.
	 * {@code EventOccurrence.update} 를 그대로 쓰는 것이 계약이다: 시각을 바꾸지 않으므로
	 * scheduleRevision 이 오르지 않아 <b>일정 변경 알림이 나가지 않는다</b>(영역이 넓어진 것은 구독자에게
	 * 알릴 일정 변경이 아니다).
	 */
	private void expandExposure(EventOccurrence occurrence, Set<AreaCell> newCells) {
		EventExposureArea area = EventExposureArea.of(occurrence).union(EventExposureArea.ofCells(newCells));
		occurrence.update(occurrence.getSeries(), occurrence.getTitle(), occurrence.getCityName(),
			occurrence.getStartsAt(), occurrence.getEndsAt(),
			area.minGridY(), area.maxGridY(), area.minGridX(), area.maxGridX());
	}

	/** 심사 대상 조회 — 없으면 13430, 심사 중이 아니면 13450 (전이 전 빠른 거절). */
	private EventSubmission findForReview(Long submissionId) {
		EventSubmission submission = submissionRepository.findWithLocationsById(submissionId)
			.orElseThrow(() -> new ApiException(EventErrorCode.SUBMISSION_NOT_FOUND));
		if (submission.getStatus() != EventSubmissionStatus.IN_REVIEW) {
			throw new ApiException(EventErrorCode.SUBMISSION_STATUS_NOT_REVIEWABLE);
		}
		return submission;
	}

	/**
	 * 기간 가드 (D-1) — 접수 검증(MSG-498 D-6)과 같은 규칙을 심사 지연 구간에 다시 건다. 끝난 행사를
	 * 승인하면 지도에 실릴 것이 없는 미션이 생긴다. "오늘"은 관리자가 생각하는 날짜라 KST 다.
	 */
	private void requirePeriodNotPassed(EventSubmission submission) {
		if (submission.getEndsOn().isBefore(LocalDate.now(clock.withZone(KST)))) {
			throw new ApiException(EventErrorCode.SUBMISSION_PERIOD_PASSED);
		}
	}

	/** 조건부 UPDATE 가 0행일 때의 이유 — 그 사이 사라졌으면 13430, 남아 있으면 상태 위반이다. */
	private ApiException transitionFailure(Long submissionId) {
		submissionRepository.findById(submissionId)
			.orElseThrow(() -> new ApiException(EventErrorCode.SUBMISSION_NOT_FOUND));
		return new ApiException(EventErrorCode.SUBMISSION_STATUS_NOT_REVIEWABLE);
	}

	/**
	 * 반려 항목 코드 파싱 (§API 4) — 빈 목록·허용 밖 값·중복을 전부 13454 로 낸다. Jackson enum 역직렬화에
	 * 맡기지 않는 이유는 그 실패가 공통 400 으로 뭉개져 "무엇이 잘못됐는지"를 화면이 구분하지 못해서다.
	 */
	private List<EventSubmissionReasonCode> parseReasonCodes(List<String> rawCodes) {
		if (rawCodes == null || rawCodes.isEmpty()) {
			throw new ApiException(EventErrorCode.INVALID_REJECT_REASON);
		}
		List<EventSubmissionReasonCode> codes = new ArrayList<>();
		for (String rawCode : rawCodes) {
			if (rawCode == null) {
				throw new ApiException(EventErrorCode.INVALID_REJECT_REASON);
			}
			try {
				codes.add(EventSubmissionReasonCode.valueOf(rawCode.toUpperCase(Locale.ROOT)));
			} catch (IllegalArgumentException e) {
				throw new ApiException(EventErrorCode.INVALID_REJECT_REASON, e);
			}
		}
		if (codes.size() != Set.copyOf(codes).size()) {
			throw new ApiException(EventErrorCode.INVALID_REJECT_REASON);
		}
		return codes;
	}

	/** {@code APR-{KST 연도}-{4자리 0패딩 순번}} (D-4) — 신청 번호와 같은 구조의 전역 시퀀스다. */
	private String nextApprovalNo() {
		return "APR-%d-%04d".formatted(
			LocalDate.now(clock.withZone(KST)).getYear(), submissionRepository.nextApprovalSequence());
	}

	/**
	 * 부모 이벤트 회차 요약 (§API 2) — 참여형이 아니면 null 이다. 행사 운영자 상세(MSG-502)와 같은 타입·같은
	 * 원천({@code event_occurrences.title})을 쓴다. 회차가 사라진 신청(부모 삭제)은 null 로 두고 승인 시점에
	 * 13440 으로 갈린다 — 조회가 500 으로 죽는 것보다 심사 화면이 열리는 편이 낫다.
	 */
	private EventSubmissionParentEventResponseDto toParentEvent(EventSubmission submission) {
		if (submission.getParentEventOccurrenceId() == null) {
			return null;
		}
		return occurrenceRepository.findById(submission.getParentEventOccurrenceId())
			.map(parent -> new EventSubmissionParentEventResponseDto(parent.getId(), parent.getTitle()))
			.orElse(null);
	}

	/**
	 * 노출 영역 (§API 2) — 전 위치 사각형을 감싸는 경계 사각형이다. 셀을 전개하지 않고 사각형의 min·max 만
	 * 접는 것으로 충분하다: 합집합의 경계는 원본 사각형들의 경계와 같다. 조회 시점 계산이라 저장하지 않는다.
	 * <p>
	 * 선행 조건은 "위치 1개 이상, 위치마다 사각형 1개 이상"이고 접수 검증(MSG-498 buildLocations·expand)이
	 * 그 둘을 강제한다 — 신청을 만드는 경로가 그 애그리거트 하나뿐이라 빈 입력이 여기 닿지 못한다.
	 * 그래서 빈 경우의 분기를 두지 않는다(응답 필드도 non-null 계약이다).
	 */
	private EventSubmissionAreaRectDto exposureRect(List<EventSubmissionLocation> locations) {
		int minGridY = Integer.MAX_VALUE;
		int maxGridY = Integer.MIN_VALUE;
		int minGridX = Integer.MAX_VALUE;
		int maxGridX = Integer.MIN_VALUE;
		for (EventSubmissionLocation location : locations) {
			for (EventSubmissionAreaRect rect : location.getRects()) {
				minGridY = Math.min(minGridY, rect.getMinGridY());
				maxGridY = Math.max(maxGridY, rect.getMaxGridY());
				minGridX = Math.min(minGridX, rect.getMinGridX());
				maxGridX = Math.max(maxGridX, rect.getMaxGridX());
			}
		}
		return new EventSubmissionAreaRectDto(minGridY, maxGridY, minGridX, maxGridX);
	}

	/** 클라이언트 문자열 → 상태 (MSG-499 parseStatus 선례). 조용한 기본값 폴백 없이 13455 로 거른다. */
	private EventSubmissionStatus parseStatus(String status) {
		try {
			return EventSubmissionStatus.valueOf(status.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new ApiException(EventErrorCode.INVALID_SUBMISSION_STATUS_FILTER);
		}
	}
}
