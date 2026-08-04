package com.msg.fillmap.friend.service;

import java.util.List;
import java.util.Optional;

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
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.usergrid.dto.CollectionSummaryResponseDto;
import com.msg.fillmap.usergrid.service.UserGridQueryService;

/**
 * 친구 관계 수명주기 (MSG-185). 불변식 "행 존재 = 활성 관계"(§D3) — 거절·삭제는 행 DELETE,
 * 수락·자동 수락은 기존 행의 ACCEPTED 승격(더티 체킹 UPDATE)이라 동일 쌍 최대 1행이 유지된다.
 */
@Service
@RequiredArgsConstructor
public class FriendServiceImpl implements FriendService {

	private static final String SORT_RECENT = "recent";
	private static final String SORT_NICKNAME = "nickname";

	private final UserRepository userRepository;
	private final FriendshipRepository friendshipRepository;
	// B-내부 read 소비 (MSG-186 §D5) — 역방향(usergrid → friend) 참조는 없다. 관계 판정은 여기서 끝내고
	// usergrid 에는 도감 소유자 userId 만 넘긴다.
	private final UserGridQueryService userGridQueryService;

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
		// 상호 동시 요청 레이스의 양방향 2행은 V19 대칭 유니크 인덱스(uq_friendships_pair)가 막는다 —
		// 늦은 INSERT 는 유니크 위반 500 1회, 재시도가 역방향 PENDING 을 보고 자동 수락으로 수렴.
		// 방치 시 findPair 의 Optional 단건 계약이 영구 깨져 앱 검증만으론 부족했다 (Codex 리뷰 반영).
		Optional<Friendship> pair = friendshipRepository.findPair(userId, target.getId());
		if (pair.isEmpty()) {
			friendshipRepository.save(Friendship.request(userId, target.getId()));
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
		if (!friendshipRepository.existsAcceptedPair(userId, targetUserId)) {
			throw new ApiException(FriendErrorCode.FRIENDSHIP_NOT_FOUND);
		}
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

	@Override
	@Transactional
	public void accept(Long userId, Long requesterId) {
		findPendingRequest(requesterId, userId).accept();
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

	@Override
	@Transactional(readOnly = true)
	public boolean isFriend(Long userId, Long otherUserId) {
		return friendshipRepository.existsAcceptedPair(userId, otherUserId);
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

	private User findByCode(String friendCode) {
		return userRepository.findByFriendCode(friendCode)
			.orElseThrow(() -> new ApiException(FriendErrorCode.FRIEND_CODE_NOT_FOUND));
	}

	private User findUser(Long userId) {
		return userRepository.findById(userId)
			.orElseThrow(() -> new ApiException(UserErrorCode.USER_NOT_FOUND));
	}
}
