package com.msg.fillmap.friend.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.friend.dto.FriendCodeResponseDto;
import com.msg.fillmap.friend.dto.FriendCollectionGridResponseDto;
import com.msg.fillmap.friend.dto.FriendListItemResponseDto;
import com.msg.fillmap.friend.dto.FriendPreviewResponseDto;
import com.msg.fillmap.friend.dto.FriendProfileResponseDto;
import com.msg.fillmap.friend.dto.FriendRequestCreateResponseDto;
import com.msg.fillmap.friend.dto.ReceivedFriendRequestResponseDto;
import com.msg.fillmap.friend.entity.Friendship;
import com.msg.fillmap.friend.entity.FriendshipId;
import com.msg.fillmap.friend.entity.FriendshipStatus;
import com.msg.fillmap.friend.exception.FriendErrorCode;
import com.msg.fillmap.friend.repository.FriendshipRepository;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.dto.RegionUnit;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.grid.service.GridQueryService;
import com.msg.fillmap.grid.service.OccupiedGridPage;
import com.msg.fillmap.grid.service.RegionAggregateView;
import com.msg.fillmap.notification.entity.NotificationCategory;
import com.msg.fillmap.notification.service.NotificationCommandService;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.usergrid.dto.CollectionSummaryResponseDto;
import com.msg.fillmap.usergrid.service.UserGridQueryService;
import com.msg.fillmap.video.dto.FriendGridVideoResponseDto;
import com.msg.fillmap.video.service.VideoService;

/**
 * 친구 관계 수명주기 (MSG-185). 불변식 "행 존재 = 활성 관계"(§D3) — 거절·삭제는 행 DELETE,
 * 수락·자동 수락은 기존 행의 ACCEPTED 승격(더티 체킹 UPDATE)이라 동일 쌍 최대 1행이 유지된다.
 */
@Service
@RequiredArgsConstructor
public class FriendServiceImpl implements FriendService {

	private static final String SORT_RECENT = "recent";
	private static final String SORT_NICKNAME = "nickname";
	// 요청 도착 event_key 의 일 경계 = KST 자정 (MSG-416 D2, glossary 스트릭 규칙·FR-NOTI-07 과 동일).
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final UserRepository userRepository;
	private final FriendshipRepository friendshipRepository;
	// B-내부 read 소비 (MSG-186 §D5) — 역방향(usergrid → friend) 참조는 없다. 관계 판정은 여기서 끝내고
	// usergrid 에는 도감 소유자 userId 만 넘긴다.
	private final UserGridQueryService userGridQueryService;
	// A 제공 계약의 read 소비 (MSG-187 D2) — 4-인자 시그니처 무변경, 친구 userId 를 넣어 호출한다.
	private final GridQueryService gridQueryService;
	// 친구 격자 영상 목록의 데이터 형상 위임 (MSG-187 D5). video 는 더 이상 FriendService 를 참조하지 않고
	// 판정용 leaf(FriendshipQueryService)만 보므로 이 방향은 단방향이다 — 생성자 직접 주입으로 충분하다
	// (MSG-312 이전에는 상호 의존이라 ObjectProvider 지연 조회로 기동 순환을 우회했다).
	private final VideoService videoService;
	// 친구 판정 정본 (MSG-312) — video 와 같은 빈을 본다. 판정 규칙 변경 지점이 여기 하나로 모인다.
	private final FriendshipQueryService friendshipQueryService;
	// FRIEND 알림 기록 (MSG-416 D1) — 전파 REQUIRED 라 요청·수락 트랜잭션과 같은 커밋 (FR-5). B-내부 배선.
	private final NotificationCommandService notificationCommandService;
	private final Clock clock;

	/**
	 * 프로덕션 생성자 — 마지막 인자 clock 을 Clock.systemUTC() 로 고정해 Lombok 전체 생성자로 위임한다
	 * (BadgeAwardServiceImpl 선례, MSG-416 D3). 요청 도착 event_key 의 KST 날짜 산출에 쓰며, 전체
	 * 생성자는 테스트 고정 클럭 주입용이다.
	 */
	@Autowired
	public FriendServiceImpl(UserRepository userRepository, FriendshipRepository friendshipRepository,
		UserGridQueryService userGridQueryService, GridQueryService gridQueryService, VideoService videoService,
		FriendshipQueryService friendshipQueryService, NotificationCommandService notificationCommandService) {
		this(userRepository, friendshipRepository, userGridQueryService, gridQueryService, videoService,
			friendshipQueryService, notificationCommandService, Clock.systemUTC());
	}

	@Override
	@Transactional(readOnly = true)
	public FriendCodeResponseDto getMyFriendCode(Long userId) {
		return new FriendCodeResponseDto(findUser(userId).getFriendCode());
	}

	@Override
	@Transactional(readOnly = true)
	public FriendPreviewResponseDto preview(String friendCode) {
		return new FriendPreviewResponseDto(findByCode(friendCode).getNickname());
	}

	@Override
	@Transactional
	public FriendRequestCreateResponseDto request(Long userId, String friendCode) {
		User target = findByCode(friendCode);
		if (target.getId().equals(userId)) {
			throw new ApiException(FriendErrorCode.SELF_FRIEND_REQUEST);
		}
		// 알림 수신자(두 분기 모두 target)의 users KEY SHARE 선취 (MSG-416 D7) — record 의 FK 검사가 나중에
		// 잡을 잠금을 friendships 잠금(findPair)보다 먼저 확보해 탈퇴 CASCADE(users 배타 → friendships)와
		// 잠금 순서를 통일한다 (VideoStatusWriter.lockUploaderFirst 선례). 빈 Optional = findByCode 와 이
		// 사이의 대상 계정 삭제 — 방치하면 pair-empty 분기의 save 가 FK 위반 500 이라 9404 로 변환한다.
		if (userRepository.findIdForKeyShare(target.getId()).isEmpty()) {
			throw new ApiException(FriendErrorCode.FRIEND_CODE_NOT_FOUND);
		}
		// 상호 동시 요청 레이스의 양방향 2행은 V19 대칭 유니크 인덱스(uq_friendships_pair)가 막는다 —
		// 늦은 INSERT 는 유니크 위반 500 1회, 재시도가 역방향 PENDING 을 보고 자동 수락으로 수렴.
		// 방치 시 findPair 의 Optional 단건 계약이 영구 깨져 앱 검증만으론 부족했다 (Codex 리뷰 반영).
		Optional<Friendship> pair = friendshipRepository.findPair(userId, target.getId());
		if (pair.isEmpty()) {
			friendshipRepository.save(Friendship.request(userId, target.getId()));
			// 요청 도착 알림 (MSG-416 D1·D2) — KST 날짜 키가 "같은 상대 하루 1건" 상한(FR-8)을 겸한다.
			// 같은 날 재요청은 ON CONFLICT DO NOTHING 흡수, 날이 바뀌면 새 키라 새 행 (FR-7).
			String eventKey = "FRIEND_REQ:" + userId + ":"
				+ LocalDate.now(clock.withZone(KST)).format(DateTimeFormatter.BASIC_ISO_DATE);
			notificationCommandService.record(target.getId(), NotificationCategory.FRIEND, eventKey,
				"새 친구 요청", findUser(userId).getNickname() + "님이 친구 요청을 보냈어요");
			return new FriendRequestCreateResponseDto(FriendshipStatus.PENDING);
		}
		Friendship existing = pair.get();
		if (existing.getStatus() == FriendshipStatus.ACCEPTED) {
			throw new ApiException(FriendErrorCode.ALREADY_FRIENDS);
		}
		if (existing.getRequesterId().equals(userId)) {
			throw new ApiException(FriendErrorCode.FRIEND_REQUEST_ALREADY_PENDING);
		}
		// 역방향 대기 = 양쪽 다 추가 의사 표명 — 기존 행을 승격해 "최대 1행" 불변식을 유지한다 (FR-8).
		existing.accept();
		// 수락 알림은 먼저 요청했던 쪽에만 — 나중 요청자(userId)는 응답 body 로 이미 ACCEPTED 를 받는다 (MSG-416 FR-3).
		recordAcceptedNotification(existing.getRequesterId(), userId);
		return new FriendRequestCreateResponseDto(FriendshipStatus.ACCEPTED);
	}

	@Override
	@Transactional(readOnly = true)
	public List<ReceivedFriendRequestResponseDto> getReceivedRequests(Long userId) {
		return friendshipRepository.findReceivedRequests(userId);
	}

	/**
	 * 친구 목록 (MSG-186 D3). 값이 둘뿐이라 enum 없이 분기 2개로 파싱한다 — 알 수 없는 값은
	 * VideoServiceImpl.parseVisibility(3420) 선례대로 도메인 코드 9420 으로 거른다(조용한 기본값 폴백 금지).
	 * equalsIgnoreCase 는 로케일 비의존이라 배포 JVM 로케일에 흔들리지 않는다.
	 */
	@Override
	@Transactional(readOnly = true)
	public List<FriendListItemResponseDto> getFriends(Long userId, String sort) {
		if (sort == null || SORT_RECENT.equalsIgnoreCase(sort)) {
			return friendshipRepository.findFriendsOrderByAcceptedAt(userId);
		}
		if (SORT_NICKNAME.equalsIgnoreCase(sort)) {
			return friendshipRepository.findFriendsOrderByNickname(userId);
		}
		throw new ApiException(FriendErrorCode.INVALID_FRIEND_SORT);
	}

	/**
	 * 친구 프로필 (MSG-186 D4·D5). 실패는 전부 9424 하나다 — 비친구·본인 ID(자기 쌍 행은 존재할 수 없어
	 * 존재 확인이 자연히 false)·대기 중 상대·미존재 userId 가 같은 응답이라 관계도 계정 존재도 새지 않는다.
	 * 존재 확인은 무잠금 existsAcceptedPair — findPair 는 PESSIMISTIC_WRITE 라 이 readOnly 트랜잭션에서
	 * PostgreSQL 이 거부한다. 확인 → 프로필 → 요약 → 격자가 한 트랜잭션이라 삭제 직후 요청은 첫 단계에서 끝난다.
	 */
	@Override
	@Transactional(readOnly = true)
	public FriendProfileResponseDto getFriendProfile(Long userId, Long targetUserId) {
		requireFriend(userId, targetUserId);
		// 관계 행이 있으면 FK ON DELETE CASCADE 로 계정도 반드시 있다 — 도달 불가 경로지만 은닉 일관을 위해 9424.
		User friend = userRepository.findById(targetUserId)
			.orElseThrow(() -> new ApiException(FriendErrorCode.FRIENDSHIP_NOT_FOUND));
		return new FriendProfileResponseDto(
			friend.getNickname(),
			friend.getProfileImageUrl(),
			friend.getGridColor(),
			CollectionSummaryResponseDto.from(userGridQueryService.getCollectionSummary(targetUserId)),
			userGridQueryService.getCollectionGridsForFriend(targetUserId).stream()
				.map(FriendCollectionGridResponseDto::from)
				.toList()
		);
	}

	/**
	 * 친구 격자 뷰포트 조회 (MSG-187 D2·D3). 친구 판정(9424)이 먼저고, 뷰포트·커서·size 검증은 전부
	 * GridQueryService 재사용분이 수행한다 — friend 쪽 검증 코드가 0줄이라 내 조회와 규칙·에러가
	 * 구조적으로 같다(FR-10). 비친구에게는 뷰포트가 유효한지조차 알려주지 않는다(은닉 우선, §D3 순서).
	 * 계약 시그니처가 이미 대상 userId 를 파라미터로 받으므로 친구 id 를 넣는 것으로 끝난다 — grid 무수정.
	 */
	@Override
	@Transactional(readOnly = true)
	public OccupiedGridPage getFriendGrids(Long userId, Long targetUserId, ViewportBounds bounds, String cursor,
		int size) {
		requireFriend(userId, targetUserId);
		return gridQueryService.getOccupiedInViewport(targetUserId, bounds, cursor, size);
	}

	/**
	 * 친구 격자 집계 조회 (MSG-356). 뷰포트 조회와 같은 배선이다 — 판정(9424) 뒤 친구 id 를 넣어 위임하고,
	 * 뷰포트·단위 검증과 단위별 bbox 상한은 전부 위임분이 수행한다(friend 쪽 검증 코드 0줄, MSG-187 D2).
	 */
	@Override
	@Transactional(readOnly = true)
	public List<RegionAggregateView> getFriendGridAggregates(Long userId, Long targetUserId, ViewportBounds bounds,
		RegionUnit unit) {
		requireFriend(userId, targetUserId);
		return gridQueryService.getOccupiedAggregatesInViewport(targetUserId, bounds, unit);
	}

	/**
	 * 친구 격자 영상 목록 (MSG-187 D5). 판정은 여기서 끝내고(9424) 데이터 형상은 소유 도메인(video)에
	 * 위임한다 — getFriendProfile 이 usergrid 에 위임하는 것과 같은 배선이다. 위임 대상은 관계를 다시
	 * 확인하지 않으므로 이 판정이 유일한 인가 지점이다.
	 */
	@Override
	@Transactional(readOnly = true)
	public List<FriendGridVideoResponseDto> getFriendGridVideos(Long userId, Long targetUserId, String gridId) {
		requireFriend(userId, targetUserId);
		return videoService.getFriendGridVideos(targetUserId, gridId);
	}

	@Override
	@Transactional
	public void accept(Long userId, Long requesterId) {
		// 알림 수신자(원 요청자)의 users KEY SHARE 선취 (MSG-416 D7) — friendships FOR UPDATE 보다 먼저.
		// 빈 Optional = 요청자 계정 삭제 진행/완료 — 유효한 대기 요청도 없으므로 기존 실패와 같은 9414.
		if (userRepository.findIdForKeyShare(requesterId).isEmpty()) {
			throw new ApiException(FriendErrorCode.FRIEND_REQUEST_NOT_FOUND);
		}
		findPendingRequest(requesterId, userId).accept();
		recordAcceptedNotification(requesterId, userId);
	}

	@Override
	@Transactional
	public void reject(Long userId, Long requesterId) {
		friendshipRepository.delete(findPendingRequest(requesterId, userId));
	}

	@Override
	@Transactional
	public void deleteFriend(Long userId, Long friendUserId) {
		Friendship friendship = friendshipRepository.findPair(userId, friendUserId)
			.filter(pair -> pair.getStatus() == FriendshipStatus.ACCEPTED)
			.orElseThrow(() -> new ApiException(FriendErrorCode.FRIENDSHIP_NOT_FOUND));
		friendshipRepository.delete(friendship);
	}

	/**
	 * 친구 열람 API 공용 인가 가드 (MSG-186 §D4). 판정은 leaf 에 맡긴다 — video 재생 판정과 같은 코드를
	 * 타야 규칙이 갈라지지 않는다 (MSG-312). 실패는 언제나 9424 하나뿐이라 비친구·본인 ID(자기 쌍 행은
	 * 존재할 수 없어 자연 false)·대기 중 상대·미존재 userId 가 구분되지 않는다.
	 */
	private void requireFriend(Long userId, Long targetUserId) {
		if (!friendshipQueryService.isFriend(userId, targetUserId)) {
			throw new ApiException(FriendErrorCode.FRIENDSHIP_NOT_FOUND);
		}
	}

	/**
	 * 수락/거절 공용 조회 — 키 (requesterId, 나)라 타인의 요청은 애초에 조회되지 않는다 (FR-13).
	 * FOR UPDATE 잠금이라 동시 수락/거절/자동수락은 직렬화되고, 늦은 쪽은 갱신된 상태를 읽어
	 * PENDING 필터에서 9414 로 수렴한다 (Codex 리뷰 반영).
	 */
	private Friendship findPendingRequest(Long requesterId, Long addresseeId) {
		return friendshipRepository.findWithLockById(new FriendshipId(requesterId, addresseeId))
			.filter(friendship -> friendship.getStatus() == FriendshipStatus.PENDING)
			.orElseThrow(() -> new ApiException(FriendErrorCode.FRIEND_REQUEST_NOT_FOUND));
	}

	/**
	 * 수락 알림 기록 (MSG-416 D1·D2) — 직접 수락·자동 수락 공통. 키 꼬리는 무작위 UUID: 같은 KST 날짜의
	 * "삭제 후 재요청·재수락"에서 정당한 두 번째 알림이 dedupe 에 삼켜지지 않는다. 같은 전이의 중복 기록은
	 * 롤백 동반(전파 REQUIRED)·상태 가드(9414·9409)·컨슈머 종결 가드가 키 없이도 막는다 (D2 논증).
	 */
	private void recordAcceptedNotification(Long requesterId, Long accepterId) {
		notificationCommandService.record(requesterId, NotificationCategory.FRIEND,
			"FRIEND_ACC:" + accepterId + ":" + UUID.randomUUID(),
			"친구 요청 수락", findUser(accepterId).getNickname() + "님이 친구 요청을 수락했어요");
	}

	private User findByCode(String friendCode) {
		return userRepository.findByFriendCode(friendCode)
			.orElseThrow(() -> new ApiException(FriendErrorCode.FRIEND_CODE_NOT_FOUND));
	}

	private User findUser(Long userId) {
		return userRepository.findById(userId)
			.orElseThrow(() -> new ApiException(UserErrorCode.USER_NOT_FOUND));
	}
}
