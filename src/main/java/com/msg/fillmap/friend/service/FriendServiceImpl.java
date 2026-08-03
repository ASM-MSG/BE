package com.msg.fillmap.friend.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.friend.dto.FriendCodeResponseDto;
import com.msg.fillmap.friend.dto.FriendPreviewResponseDto;
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

/**
 * 친구 관계 수명주기 (MSG-185). 불변식 "행 존재 = 활성 관계"(§D3) — 거절·삭제는 행 DELETE,
 * 수락·자동 수락은 기존 행의 ACCEPTED 승격(더티 체킹 UPDATE)이라 동일 쌍 최대 1행이 유지된다.
 */
@Service
@RequiredArgsConstructor
public class FriendServiceImpl implements FriendService {

	private final UserRepository userRepository;
	private final FriendshipRepository friendshipRepository;

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
		// ponytail: 서로 동시에 요청하는 극단 레이스면 A→B·B→A 2행이 생길 수 있다(복합 PK 는 같은
		// 방향만 차단). 실측 발생 시 UNIQUE (LEAST(requester_id, addressee_id), GREATEST(...))
		// 인덱스 마이그레이션이 업그레이드 경로.
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
