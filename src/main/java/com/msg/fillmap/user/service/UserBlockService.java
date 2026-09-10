package com.msg.fillmap.user.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.friend.service.FriendService;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.dto.BlockedUserResponseDto;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.UserBlockRepository;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 사용자 차단·해제·목록 (MSG-569 D-3). 인터페이스 없는 클래스다 — 타 패키지 주입용 계약은
 * {@link UserBlockQueryService} 하나뿐이고 형식적 Service/Impl 쌍은 두지 않는다(2026-09-08 확정).
 */
@Service
@RequiredArgsConstructor
public class UserBlockService {

	private final UserBlockRepository userBlockRepository;
	private final UserRepository userRepository;
	// friendships 의 소유 도메인은 friend 라 삭제도 그 서비스를 거친다(타 도메인 상태 변경은 소유 도메인 서비스로).
	private final FriendService friendService;
	private final Clock clock;

	/**
	 * 프로덕션 생성자 — Clock 빈이 없어 마지막 인자를 Clock.systemUTC() 로 고정해 Lombok 전체 생성자로
	 * 위임한다(VideoServiceImpl·BadgeAwardServiceImpl 선례, D-8). systemUTC 인 이유는 created_at 이 UTC 저장
	 * 관례(MSG-376)라서다. 전체 생성자는 테스트 고정 클럭 주입용이다.
	 */
	@Autowired
	public UserBlockService(UserBlockRepository userBlockRepository, UserRepository userRepository,
		FriendService friendService) {
		this(userBlockRepository, userRepository, friendService, Clock.systemUTC());
	}

	/**
	 * 차단 (FR-1·2·5). 대상 존재 확인과 KEY SHARE 잠금을 한 문장으로 한다 — existsById 뒤 INSERT 사이에
	 * 탈퇴가 끼면 FK 위반 500 인데, KEY SHARE 를 잡아 두면 탈퇴 CASCADE 가 이 트랜잭션 뒤로 밀린다
	 * (FriendServiceImpl.request 와 같은 선례, 요청자 본인은 잠그지 않는다). 잠금 순서도 그 경로와 같은
	 * users(KEY SHARE) → friendships 라 교착 조합이 없다. 차단 저장과 친구 관계 삭제가 한 트랜잭션이다(FR-MOD-16).
	 */
	@Transactional
	public void block(Long userId, Long targetId) {
		if (userId.equals(targetId)) {
			throw new ApiException(UserErrorCode.SELF_BLOCK);
		}
		if (userRepository.findIdForKeyShare(targetId).isEmpty()) {
			throw new ApiException(UserErrorCode.USER_NOT_FOUND);
		}
		userBlockRepository.insertIgnore(userId, targetId, LocalDateTime.now(clock));
		friendService.deleteRelationsBetween(userId, targetId);
	}

	/** 해제 (FR-3). 자기 방향 한 행만 지우고 0행도 200 이다 — 친구 관계는 되살리지 않는다(FR-5). */
	@Transactional
	public void unblock(Long userId, Long targetId) {
		userBlockRepository.deletePair(userId, targetId);
	}

	/** 내가 걸은 차단 목록 (FR-4). 최신 차단 순, 페이지 없음. 나를 차단한 사람은 포함되지 않는다. */
	@Transactional(readOnly = true)
	public List<BlockedUserResponseDto> getBlockedUsers(Long userId) {
		return userBlockRepository.findBlockedUsers(userId);
	}
}
