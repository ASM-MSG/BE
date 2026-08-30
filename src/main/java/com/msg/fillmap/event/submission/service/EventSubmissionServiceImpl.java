package com.msg.fillmap.event.submission.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.event.entity.EventOccurrence;
import com.msg.fillmap.event.exception.EventErrorCode;
import com.msg.fillmap.event.repository.EventOccurrenceRepository;
import com.msg.fillmap.event.submission.dto.EventSubmissionAreaRectDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionCreateRequestDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionDetailResponseDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionForm;
import com.msg.fillmap.event.submission.dto.EventSubmissionHistoryResponseDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionImagePresignRequestDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionImagePresignResponseDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionLocationRequestDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionLocationResponseDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionMyListResponseDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionParentEventResponseDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionRejectionResponseDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionStatusCountsResponseDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionSubmitResponseDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionSummaryResponseDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionUpdateRequestDto;
import com.msg.fillmap.event.submission.entity.EventSubmission;
import com.msg.fillmap.event.submission.entity.EventSubmissionLocation;
import com.msg.fillmap.event.submission.entity.EventSubmissionStatus;
import com.msg.fillmap.event.submission.entity.EventSubmissionStatusHistory;
import com.msg.fillmap.event.submission.entity.EventSubmissionType;
import com.msg.fillmap.event.submission.repository.EventSubmissionRepository;
import com.msg.fillmap.event.submission.repository.EventSubmissionStatusHistoryRepository;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.global.geo.AreaCell;
import com.msg.fillmap.global.geo.RepresentativeGridResolver;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.service.GridQueryService;
import com.msg.fillmap.zone.service.ZoneCellName;
import com.msg.fillmap.zone.service.ZoneNameQueryService;
import com.msg.fillmap.zone.service.ZoneNameResolver;

/**
 * 행사 등재 신청 접수 (MSG-498). 검증 3규칙(영역·기간·유형별 항목)과 대표 격자 계산이 제출과 재제출에서
 * 똑같이 돌아간다 — 재제출은 부분 수정이 아니라 폼 전체 교체라 같은 입력을 같은 규칙으로 다시 받는 것과
 * 다르지 않기 때문이다.
 * <p>
 * 81칸 상한은 사각형 합산이 아니라 <b>전개한 격자 집합의 크기</b>로 판정한다 (D-7) — 겹침을 두 번 세면
 * 정당한 81칸 영역이 그리는 방식에 따라 거부되는 비결정성이 생긴다. 대표 격자도 같은 집합을 입력으로 받아
 * 표현이 아니라 기하로 결정된다.
 */
@Service
public class EventSubmissionServiceImpl implements EventSubmissionService {

	/** 신청 번호의 연도 라벨은 사용자 대면 값이라 KST 다 (D-4, uploadDate 선례와 같은 성격). */
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	/** 위치 수 상한 (D-10). 실사용을 막지 않으면서 무제한 엔티티 생성을 차단하는 값이다. */
	private static final int MAX_LOCATIONS = 20;

	/** 위치 하나의 영역 상한 (FR-24). 사각형 수 상한이기도 하다 — 사각형마다 최소 1칸이라 초과는 전부 중복 입력이다. */
	private static final int MAX_CELLS_PER_LOCATION = 81;

	private final EventSubmissionRepository submissionRepository;
	private final EventSubmissionStatusHistoryRepository historyRepository;
	private final EventSubmissionImageStore imageStore;
	private final ZoneNameQueryService zoneNameQueryService;
	private final GridQueryService gridQueryService;
	private final EventOccurrenceRepository occurrenceRepository;
	private final Clock clock;

	/** 프로덕션 생성자 — clock 을 systemUTC 로 고정해 전체 생성자로 위임한다 (EventVideoServiceImpl 선례). */
	@Autowired
	public EventSubmissionServiceImpl(EventSubmissionRepository submissionRepository,
		EventSubmissionStatusHistoryRepository historyRepository, EventSubmissionImageStore imageStore,
		ZoneNameQueryService zoneNameQueryService, GridQueryService gridQueryService,
		EventOccurrenceRepository occurrenceRepository) {
		this(submissionRepository, historyRepository, imageStore, zoneNameQueryService, gridQueryService,
			occurrenceRepository, Clock.systemUTC());
	}

	public EventSubmissionServiceImpl(EventSubmissionRepository submissionRepository,
		EventSubmissionStatusHistoryRepository historyRepository, EventSubmissionImageStore imageStore,
		ZoneNameQueryService zoneNameQueryService, GridQueryService gridQueryService,
		EventOccurrenceRepository occurrenceRepository, Clock clock) {
		this.submissionRepository = submissionRepository;
		this.historyRepository = historyRepository;
		this.imageStore = imageStore;
		this.zoneNameQueryService = zoneNameQueryService;
		this.gridQueryService = gridQueryService;
		this.occurrenceRepository = occurrenceRepository;
		this.clock = clock;
	}

	@Override
	public EventSubmissionImagePresignResponseDto issueImagePresignedUrl(Long userId,
		EventSubmissionImagePresignRequestDto request) {
		return imageStore.presign(userId, request);
	}

	@Override
	@Transactional
	public EventSubmissionSubmitResponseDto submit(Long userId, EventSubmissionCreateRequestDto request) {
		validateForm(request.type(), request, request.parentOccurrenceId());
		List<EventSubmissionLocation> locations = buildLocations(request.locations());

		LocalDateTime now = LocalDateTime.now(clock);
		String imageKey = imageStore.confirm(userId, request.imageS3Key());
		EventSubmission submission = EventSubmission.submit(nextSubmissionNo(), userId, request.type(),
			request.parentOccurrenceId(), now);
		applyForm(submission, request, imageKey, locations, now);

		submissionRepository.save(submission);
		historyRepository.save(EventSubmissionStatusHistory.inReview(submission.getId(), now));
		return EventSubmissionSubmitResponseDto.from(submission);
	}

	@Override
	@Transactional(readOnly = true)
	public EventSubmissionMyListResponseDto getMySubmissions(Long userId) {
		List<EventSubmission> submissions = submissionRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId);
		// 건수를 GROUP BY 로 따로 세지 않는다 (Codex 구현 리뷰 1R). 페이지네이션이 없어 목록이 곧 전량이라
		// 세는 재료가 이미 손에 있고, 쿼리를 둘로 나누면 READ COMMITTED 에서 서로 다른 스냅숏을 볼 뿐이다 —
		// 그 사이 상태가 바뀌면 현황 카드의 합과 목록이 어긋나 보인다. 목록에서 세면 둘이 같은 한 장면이다.
		Map<EventSubmissionStatus, Long> counts = submissions.stream()
			.collect(Collectors.groupingBy(EventSubmission::getStatus, Collectors.counting()));
		return new EventSubmissionMyListResponseDto(
			new EventSubmissionStatusCountsResponseDto(
				counts.getOrDefault(EventSubmissionStatus.IN_REVIEW, 0L),
				counts.getOrDefault(EventSubmissionStatus.APPROVED, 0L),
				counts.getOrDefault(EventSubmissionStatus.REJECTED, 0L)),
			submissions.stream().map(EventSubmissionSummaryResponseDto::from).toList());
	}

	@Override
	@Transactional(readOnly = true)
	public EventSubmissionDetailResponseDto getSubmission(Long userId, Long submissionId) {
		EventSubmission submission = findOwned(userId, submissionId);
		List<EventSubmissionStatusHistory> history =
			historyRepository.findByEventSubmissionIdOrderByIdAsc(submissionId);
		return new EventSubmissionDetailResponseDto(
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
			toLocationDtos(submission),
			toRejection(submission, history),
			history.stream().map(EventSubmissionHistoryResponseDto::from).toList(),
			submission.getUpdatedAt());
	}

	/**
	 * 반려본 수정 재제출 (FR-13). 상태 복귀를 소유권 술어를 포함한 조건부 UPDATE 하나로 원자화하고
	 * <b>실행 순서가 계약이다</b>.
	 * <p>
	 * ① 유형을 읽고 폼을 검증한다. 유형은 바꿀 수 없으므로(D-8) 저장값이 검증 기준이고, 읽기는 소유 조회라
	 * 남의 id·없는 id 가 여기서 같은 13430 이 된다. 이 선행 조회는 <b>읽기 전용</b>이라 뒤의 벌크 UPDATE 가
	 * 덮어쓸 변경을 만들지 않는다(수정 대상 엔티티에 손대는 것은 ④ 뒤다). ② 조건부 UPDATE 를 실행한다.
	 * 술어에 userId 가 있어 남의 행은 어떤 경로로도 수정되지 않는다. ③ 영향 행이 0이면 <b>소유 조회로</b>
	 * 분기한다 — 행이 없으면 13430, 있는데 REJECTED 가 아니면 13434(동시 재제출의 패자 포함)다. id + status
	 * 로만 분기하면 남의 REJECTED 행이 13434 로 새어 존재가 드러난다. ④ 이겼으면 애그리거트를 <b>재로드</b>해
	 * 교체한다 — 순서를 뒤집어 UPDATE 전에 로드한 엔티티를 고쳐 flush 하면, 벌크 UPDATE 가 영속성 컨텍스트를
	 * 우회한 탓에 스테일한 REJECTED 가 도로 덮어써진다.
	 */
	@Override
	@Transactional
	public EventSubmissionSubmitResponseDto resubmit(Long userId, Long submissionId,
		EventSubmissionUpdateRequestDto request) {
		EventSubmission owned = findOwned(userId, submissionId);
		// 부모는 재제출로 바뀌지 않으므로(D-3) 저장값을 그대로 검증에 넘긴다 — 종료(13441)만 다시 걸린다.
		validateForm(owned.getType(), request, owned.getParentEventOccurrenceId());
		List<EventSubmissionLocation> locations = buildLocations(request.locations());

		LocalDateTime now = LocalDateTime.now(clock);
		if (submissionRepository.reopenRejected(submissionId, userId, now) == 0) {
			// 진 이유를 소유 조회로 가른다 — 그 사이 사라졌으면 13430, 남아 있으면 상태 위반이다.
			findOwned(userId, submissionId);
			throw new ApiException(EventErrorCode.SUBMISSION_NOT_EDITABLE);
		}

		EventSubmission submission = findOwned(userId, submissionId);
		String previousImageKey = submission.getImageKey();
		// null·생략은 "기존 이미지 유지"다 — 상세가 저장 키를 노출하지 않으므로 확정 키를 직접 보내는 경로가 없다.
		String imageKey = request.imageS3Key() == null
			? previousImageKey
			: imageStore.confirm(userId, request.imageS3Key());
		applyForm(submission, request, imageKey, locations, now);

		historyRepository.save(EventSubmissionStatusHistory.inReview(submissionId, now));
		if (!imageKey.equals(previousImageKey)) {
			imageStore.deleteAfterCommit(previousImageKey);
		}
		return EventSubmissionSubmitResponseDto.from(submission);
	}

	/**
	 * 부모 이벤트 (MSG-502 §API 4) — EVENT 신청만 값이 있다. 이름의 원천이 회차 title 하나라 모달에서 고른
	 * 이름과 상세에 보이는 이름이 어긋나지 않는다. 부모 행의 존재는 FK 가 보장한다.
	 */
	private EventSubmissionParentEventResponseDto toParentEvent(EventSubmission submission) {
		if (submission.getParentEventOccurrenceId() == null) {
			return null;
		}
		return occurrenceRepository.findById(submission.getParentEventOccurrenceId())
			.map(parent -> new EventSubmissionParentEventResponseDto(parent.getId(), parent.getTitle()))
			.orElseThrow(() -> new ApiException(EventErrorCode.PARENT_EVENT_NOT_FOUND));
	}

	/** 존재 은닉의 단일 진입점 (FR-14) — 없는 신청과 남의 신청이 여기서 같은 13430 이 된다. */
	private EventSubmission findOwned(Long userId, Long submissionId) {
		return submissionRepository.findByIdAndUserId(submissionId, userId)
			.orElseThrow(() -> new ApiException(EventErrorCode.SUBMISSION_NOT_FOUND));
	}

	private void applyForm(EventSubmission submission, EventSubmissionForm form, String imageKey,
		List<EventSubmissionLocation> locations, LocalDateTime now) {
		submission.updateForm(form.title(), form.organizerName(), form.startsOn(), form.endsOn(),
			form.operatingHours(), form.programDescription(), form.participationMethod(), form.description(),
			imageKey, now);
		submission.replaceLocations(locations);
	}

	/**
	 * 유형별 필수 항목과 기간과 부모 회차 (§도메인 로직). 자기 유형이 아닌 필드는 무시하지 않고 거부한다 —
	 * 폼에 없는 값이 저장되면 관리자 화면이 출처 불명 데이터를 그린다.
	 * <p>
	 * 유형마다 항목이 정확히 하나씩 대응하므로 판정은 "값이 있다 == 그 유형이다" 등식들이다 (MSG-502 에서
	 * 두 유형 이분기를 대체했다 — 유형이 늘어도 등식 한 줄이 는다). 부모 회차만 폼이 아니라 인자로 따로
	 * 받는 것은 재제출 DTO 에 부모 필드가 없기 때문이다: 제출은 요청 본문 값이, 재제출은 저장값이 들어와
	 * 같은 규칙을 탄다 (D-3).
	 */
	private void validateForm(EventSubmissionType type, EventSubmissionForm form, Long parentOccurrenceId) {
		boolean event = type == EventSubmissionType.EVENT;
		if (hasText(form.programDescription()) != (type == EventSubmissionType.FESTIVAL)
			|| hasText(form.operatingHours()) != (type == EventSubmissionType.POPUP)
			|| hasText(form.participationMethod()) != event
			|| (parentOccurrenceId != null) != event) {
			throw new ApiException(EventErrorCode.SUBMISSION_REQUIRED_FIELD_MISSING);
		}
		// EVENT 는 대표 위치 정확히 1곳이다 (D-2). 0곳은 유형 무관 공통 규칙이라 여기서 세지 않고
		// buildLocations 의 13431 로 흘려보낸다 — 같은 실패를 두 코드로 내지 않기 위해서다.
		if (event && form.locations() != null && form.locations().size() > 1) {
			throw new ApiException(EventErrorCode.SUBMISSION_REQUIRED_FIELD_MISSING);
		}
		validatePeriod(form.startsOn(), form.endsOn());
		validateParentEvent(type, parentOccurrenceId);
	}

	/**
	 * 부모 이벤트 회차 검증 (MSG-502 §도메인 로직). 존재를 은닉하지 않는다 — 승인 이벤트 목록(MSG-501)이
	 * 행사 운영자 전원에게 같은 전량을 보여주므로 회차의 존재는 비밀이 아니고, 은닉 대상은 남의 신청(13430)이다.
	 * <p>
	 * 종료 판정 {@code endsAt <= now} 는 그 목록의 노출 조건({@code endsAt > now})의 정확한 여집합이라,
	 * 종료 정각을 포함한 어떤 시각에도 모달에 보이는 회차와 신청이 되는 회차가 일치한다. 재제출도 저장 부모로
	 * 이 메서드를 다시 타는데, 저장 부모의 존재는 FK 가 보장하므로 13440 은 사실상 제출 전용이다.
	 */
	private void validateParentEvent(EventSubmissionType type, Long parentOccurrenceId) {
		if (type != EventSubmissionType.EVENT) {
			return;
		}
		EventOccurrence parent = occurrenceRepository.findById(parentOccurrenceId)
			.orElseThrow(() -> new ApiException(EventErrorCode.PARENT_EVENT_NOT_FOUND));
		if (!parent.getEndsAt().isAfter(LocalDateTime.now(clock))) {
			throw new ApiException(EventErrorCode.PARENT_EVENT_CLOSED);
		}
	}

	/**
	 * 기간 검증 (D-6). 이미 끝난 행사는 심사할 의미가 없고 관리자 큐만 오염시키므로 종료일이 오늘 이전이면
	 * 거부한다. 진행 중 행사의 신청(시작일 과거)은 허용한다. 오늘은 사용자가 생각하는 날짜라 KST 다.
	 */
	private void validatePeriod(LocalDate startsOn, LocalDate endsOn) {
		if (startsOn.isAfter(endsOn) || endsOn.isBefore(LocalDate.now(clock.withZone(KST)))) {
			throw new ApiException(EventErrorCode.INVALID_SUBMISSION_PERIOD);
		}
	}

	private List<EventSubmissionLocation> buildLocations(List<EventSubmissionLocationRequestDto> requests) {
		if (requests == null || requests.isEmpty() || requests.size() > MAX_LOCATIONS) {
			throw new ApiException(EventErrorCode.INVALID_SUBMISSION_AREA);
		}
		List<EventSubmissionLocation> locations = new ArrayList<>();
		for (EventSubmissionLocationRequestDto request : requests) {
			Set<AreaCell> cells = expand(request.areaRects());
			locations.add(new EventSubmissionLocation(
				RepresentativeGridResolver.resolve(cells, null),
				request.areaRects().stream().map(EventSubmissionAreaRectDto::toEntity).toList()));
		}
		return locations;
	}

	/**
	 * 사각형들을 격자 집합으로 전개한다 (FR-8, FR-24). 사각형 하나의 칸 수를 long 산술로 <b>먼저</b> 구해
	 * 상한을 넘으면 전개 없이 거부하는 것은 거대 사각형을 펼치다 메모리를 태우지 않기 위해서다.
	 * 인덱스 범위 검사가 극단값을 대표 격자 산술에 닿기 전에 걸러낸다 — 그쪽의 long 오버플로 안전 논증이
	 * 이 상한 위에 서 있다.
	 */
	private Set<AreaCell> expand(List<EventSubmissionAreaRectDto> rects) {
		if (rects == null || rects.isEmpty() || rects.size() > MAX_CELLS_PER_LOCATION) {
			throw new ApiException(EventErrorCode.INVALID_SUBMISSION_AREA);
		}
		Set<AreaCell> cells = new LinkedHashSet<>();
		for (EventSubmissionAreaRectDto rect : rects) {
			validateRect(rect);
			long rows = (long) rect.maxGridY() - rect.minGridY() + 1;
			long columns = (long) rect.maxGridX() - rect.minGridX() + 1;
			if (rows * columns > MAX_CELLS_PER_LOCATION) {
				throw new ApiException(EventErrorCode.SUBMISSION_AREA_LIMIT_EXCEEDED);
			}
			for (int gridY = rect.minGridY(); gridY <= rect.maxGridY(); gridY++) {
				for (int gridX = rect.minGridX(); gridX <= rect.maxGridX(); gridX++) {
					cells.add(new AreaCell(gridY, gridX));
				}
			}
			// 합집합 판정 (D-7) — 겹치는 사각형은 Set 이 자연히 한 번만 센다.
			if (cells.size() > MAX_CELLS_PER_LOCATION) {
				throw new ApiException(EventErrorCode.SUBMISSION_AREA_LIMIT_EXCEEDED);
			}
		}
		return cells;
	}

	private void validateRect(EventSubmissionAreaRectDto rect) {
		if (rect == null) {
			throw new ApiException(EventErrorCode.INVALID_SUBMISSION_AREA);
		}
		requireIndex(rect.minGridY());
		requireIndex(rect.maxGridY());
		requireIndex(rect.minGridX());
		requireIndex(rect.maxGridX());
		if (rect.minGridY() > rect.maxGridY() || rect.minGridX() > rect.maxGridX()) {
			throw new ApiException(EventErrorCode.INVALID_SUBMISSION_AREA);
		}
	}

	private void requireIndex(Integer index) {
		if (index == null || index <= 0 || index >= RepresentativeGridResolver.GRID_INDEX_UPPER_EXCLUSIVE) {
			throw new ApiException(EventErrorCode.INVALID_SUBMISSION_AREA);
		}
	}

	/** {@code FM-{KST 연도}-{4자리 0패딩 순번}} (D-4). 순번은 연도별 리셋 없는 전역 시퀀스라 9999 를 넘으면 자릿수가 늘어난다. */
	private String nextSubmissionNo() {
		return "FM-%d-%04d".formatted(
			LocalDate.now(clock.withZone(KST)).getYear(), submissionRepository.nextSubmissionSequence());
	}

	/**
	 * 위치 응답 (§API 4). 표시명 재료는 대표 격자 기준으로 서버가 계산해 동봉하고 FE 는 조립만 한다 —
	 * 구역은 요청당 리졸버 1회로 순수 계산하고(MSG-341 계약), 행정동은 대표 격자를 한 번에 넘겨 받는다.
	 */
	private List<EventSubmissionLocationResponseDto> toLocationDtos(EventSubmission submission) {
		List<EventSubmissionLocation> locations = submission.getLocations();
		List<String> gridIds = locations.stream()
			.map(EventSubmissionLocation::getRepresentativeGridId)
			.distinct()
			.toList();
		ZoneNameResolver resolver = zoneNameQueryService.resolver();
		Map<String, String> regionNames = gridQueryService.resolveRegionNames(gridIds);

		List<EventSubmissionLocationResponseDto> dtos = new ArrayList<>();
		for (EventSubmissionLocation location : locations) {
			String gridId = location.getRepresentativeGridId();
			GridIndex index = GridEncoder.decode(gridId);
			ZoneCellName zone = resolver.name(index.gridY(), index.gridX());
			List<EventSubmissionAreaRectDto> rects = location.getRects().stream()
				.map(EventSubmissionAreaRectDto::from)
				.toList();
			dtos.add(new EventSubmissionLocationResponseDto(
				location.getDisplayOrder(),
				gridId,
				zone.zoneName(),
				zone.zoneCell(),
				regionNames.get(gridId),
				expand(rects).size(),
				rects));
		}
		return dtos;
	}

	/**
	 * 현재 반려 사유는 이력 최신 행에서 읽는다 (D-3) — 신청 행에 중복 저장하지 않는다.
	 * <p>
	 * 엔티티 상태와 이력이 다른 문장에서 읽히므로 READ COMMITTED 에서 둘이 한 찰나만큼 어긋날 수 있다
	 * (반려 상태인데 반려 행이 아직 안 보이거나, 재제출 직후 상태는 심사 중인데 이력이 반려로 읽히는 경우).
	 * 수용한다 (Codex 구현 리뷰 1R) — 본인이 자기 신청을 동시에 재제출하는 찰나에만 성립하고, 결과는 카드
	 * 하나가 잠깐 비거나 남는 표시 문제이며, 다음 조회에서 사라진다. 이 하나를 없애려고 격리 수준을 올리거나
	 * 이력을 신청 행에 중복 저장(D-3 번복)하는 것은 대가가 훨씬 크다.
	 */
	private EventSubmissionRejectionResponseDto toRejection(EventSubmission submission,
		List<EventSubmissionStatusHistory> history) {
		if (submission.getStatus() != EventSubmissionStatus.REJECTED || history.isEmpty()) {
			return null;
		}
		return EventSubmissionRejectionResponseDto.from(history.get(history.size() - 1));
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
