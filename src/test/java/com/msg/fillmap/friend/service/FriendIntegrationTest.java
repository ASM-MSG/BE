package com.msg.fillmap.friend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import software.amazon.awssdk.services.s3.S3Client;

import com.msg.fillmap.friend.dto.ReceivedFriendRequestResponseDto;
import com.msg.fillmap.friend.entity.Friendship;
import com.msg.fillmap.friend.entity.FriendshipId;
import com.msg.fillmap.friend.entity.FriendshipStatus;
import com.msg.fillmap.friend.exception.FriendErrorCode;
import com.msg.fillmap.friend.repository.FriendshipRepository;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 친구 관계 수명주기 (MSG-185, 실 DB). @Transactional 롤백 격리로 공유 로컬 DB 에 시드를 남기지
 * 않는다 (UserProfileIntegrationTest 패턴). 핵심 검증 축 = §D3 불변식 "행 존재 = 활성 관계"
 * (거절·삭제 후 잔여 행 0)와 자동 수락의 "동일 쌍 최대 1행" 유지.
 */
@SpringBootTest
@Transactional
@DisplayName("친구 관계 수명주기 (실 DB)")
class FriendIntegrationTest {

	@Autowired
	private FriendService friendService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private FriendshipRepository friendshipRepository;

	@Autowired
	private EntityManager em;

	/** 실 S3 호출 차단용 — 컨텍스트의 UserServiceImpl(계정 삭제 축)이 주입받는 의존성, 이 테스트에선 미사용. */
	@MockitoBean
	private S3Client s3Client;

	private User me;
	private User other;

	@BeforeEach
	void setUp() {
		me = seedUser("나채움");
		other = seedUser("상대방");
	}

	private User seedUser(String nickname) {
		return userRepository.save(
			User.createLocalUser("friend-" + UUID.randomUUID() + "@example.com", "hash", nickname));
	}

	@Test
	@DisplayName("내 친구 코드를 조회한다 (FR-1)")
	void 내_친구_코드를_조회한다() {
		assertThat(friendService.getMyFriendCode(me.getId()).friendCode())
			.isEqualTo(me.getFriendCode());
	}

	@Test
	@DisplayName("코드로 상대 닉네임을 미리본다 (FR-3)")
	void 코드로_상대_닉네임을_미리본다() {
		assertThat(friendService.preview(other.getFriendCode()).nickname()).isEqualTo("상대방");
	}

	@Test
	@DisplayName("없는 코드 미리보기는 9404 다")
	void 없는_코드_미리보기는_9404를_반환한다() {
		assertThatThrownBy(() -> friendService.preview("XXXXXXXX"))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", FriendErrorCode.FRIEND_CODE_NOT_FOUND);
	}

	@Test
	@DisplayName("코드로 요청하면 PENDING 행이 생긴다 (FR-4)")
	void 코드로_요청하면_PENDING_행이_생긴다() {
		FriendshipStatus status = friendService.request(me.getId(), other.getFriendCode()).status();

		assertThat(status).isEqualTo(FriendshipStatus.PENDING);
		Friendship saved = friendshipRepository.findById(new FriendshipId(me.getId(), other.getId())).orElseThrow();
		assertThat(saved.getStatus()).isEqualTo(FriendshipStatus.PENDING);
		assertThat(saved.getRespondedAt()).isNull();
	}

	@Test
	@DisplayName("자기 코드로 요청하면 9400 이다 (FR-5)")
	void 자기_코드로_요청하면_9400을_반환한다() {
		assertThatThrownBy(() -> friendService.request(me.getId(), me.getFriendCode()))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", FriendErrorCode.SELF_FRIEND_REQUEST);
	}

	@Test
	@DisplayName("없는 코드로 요청하면 9404 다 (FR-6)")
	void 없는_코드로_요청하면_9404를_반환한다() {
		assertThatThrownBy(() -> friendService.request(me.getId(), "XXXXXXXX"))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", FriendErrorCode.FRIEND_CODE_NOT_FOUND);
	}

	@Test
	@DisplayName("이미 친구면 재요청은 방향 무관 9409 다 (FR-7)")
	void 이미_친구면_재요청은_9409를_반환한다() {
		friendService.request(me.getId(), other.getFriendCode());
		friendService.accept(other.getId(), me.getId());

		// 내가 requester 였던 방향과 addressee 였던 방향(상대가 재요청) 모두 같은 판정이어야 한다.
		assertThatThrownBy(() -> friendService.request(me.getId(), other.getFriendCode()))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", FriendErrorCode.ALREADY_FRIENDS);
		assertThatThrownBy(() -> friendService.request(other.getId(), me.getFriendCode()))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", FriendErrorCode.ALREADY_FRIENDS);
	}

	@Test
	@DisplayName("내가 보낸 요청이 대기 중이면 재요청은 9410 이다 (FR-7)")
	void 내가_보낸_요청이_대기중이면_재요청은_9410을_반환한다() {
		friendService.request(me.getId(), other.getFriendCode());

		assertThatThrownBy(() -> friendService.request(me.getId(), other.getFriendCode()))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", FriendErrorCode.FRIEND_REQUEST_ALREADY_PENDING);
	}

	@Test
	@DisplayName("역방향 대기 요청에 요청하면 자동 수락된다 — 행 1개 유지 (FR-8)")
	void 역방향_대기_요청에_요청하면_자동_수락된다() {
		friendService.request(other.getId(), me.getFriendCode());

		FriendshipStatus status = friendService.request(me.getId(), other.getFriendCode()).status();

		assertThat(status).isEqualTo(FriendshipStatus.ACCEPTED);
		// 더티 체킹 UPDATE 가 실제 반영됐는지 1차 캐시를 비우고 재조회 (UserProfileIntegrationTest 관례).
		em.flush();
		em.clear();
		Friendship promoted = friendshipRepository
			.findById(new FriendshipId(other.getId(), me.getId())).orElseThrow();
		assertThat(promoted.getStatus()).isEqualTo(FriendshipStatus.ACCEPTED);
		assertThat(promoted.getRespondedAt()).isNotNull();
		// 역방향 새 행을 만들지 않았다 — "동일 쌍 최대 1행" 불변식 (§D3).
		assertThat(friendshipRepository.findById(new FriendshipId(me.getId(), other.getId()))).isEmpty();
	}

	@Test
	@DisplayName("받은 요청 목록은 보낸 사람 정보를 최신순으로 담는다 (FR-9)")
	void 받은_요청_목록은_보낸_사람_정보를_최신순으로_담는다() {
		User third = seedUser("세번째");
		friendService.request(other.getId(), me.getFriendCode());
		friendService.request(third.getId(), me.getFriendCode());

		List<ReceivedFriendRequestResponseDto> received = friendService.getReceivedRequests(me.getId());

		assertThat(received).hasSize(2);
		assertThat(received.get(0).requesterId()).isEqualTo(third.getId());
		assertThat(received.get(0).nickname()).isEqualTo("세번째");
		assertThat(received.get(0).requestedAt()).isNotNull();
		assertThat(received.get(1).requesterId()).isEqualTo(other.getId());
	}

	@Test
	@DisplayName("수락하면 ACCEPTED 와 responded_at 이 기록된다 (FR-10)")
	void 수락하면_ACCEPTED와_responded_at이_기록된다() {
		friendService.request(other.getId(), me.getFriendCode());

		friendService.accept(me.getId(), other.getId());

		em.flush();
		em.clear();
		Friendship accepted = friendshipRepository
			.findById(new FriendshipId(other.getId(), me.getId())).orElseThrow();
		assertThat(accepted.getStatus()).isEqualTo(FriendshipStatus.ACCEPTED);
		assertThat(accepted.getRespondedAt()).isNotNull();
	}

	@Test
	@DisplayName("타인의 요청은 수락 경로에서 조회되지 않아 9414 다 (FR-13)")
	void 타인의_요청은_수락_경로에서_조회되지_않아_9414다() {
		User third = seedUser("세번째");
		friendService.request(other.getId(), me.getFriendCode());

		// third 가 (other → me) 요청을 가로채 수락 시도 — 조회 키가 (other, third)라 행 자체가 없다.
		assertThatThrownBy(() -> friendService.accept(third.getId(), other.getId()))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", FriendErrorCode.FRIEND_REQUEST_NOT_FOUND);
	}

	@Test
	@DisplayName("없는 요청 수락은 9414 다")
	void 없는_요청_수락은_9414를_반환한다() {
		assertThatThrownBy(() -> friendService.accept(me.getId(), other.getId()))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", FriendErrorCode.FRIEND_REQUEST_NOT_FOUND);
	}

	@Test
	@DisplayName("거절하면 행이 삭제된다 (FR-11·§D3)")
	void 거절하면_행이_삭제된다() {
		friendService.request(other.getId(), me.getFriendCode());

		friendService.reject(me.getId(), other.getId());

		assertThat(friendshipRepository.findPair(me.getId(), other.getId())).isEmpty();
	}

	@Test
	@DisplayName("거절 후 상대는 같은 요청을 다시 보낼 수 있다 (FR-11·§D3 불변식)")
	void 거절_후_상대는_같은_요청을_다시_보낼_수_있다() {
		friendService.request(other.getId(), me.getFriendCode());
		friendService.reject(me.getId(), other.getId());

		FriendshipStatus status = friendService.request(other.getId(), me.getFriendCode()).status();

		assertThat(status).isEqualTo(FriendshipStatus.PENDING);
	}

	@Test
	@DisplayName("친구 삭제는 방향 무관하게 행을 지운다 (FR-12)")
	void 친구_삭제는_방향_무관하게_행을_지운다() {
		// 내가 requester 였던 관계 — 상대(addressee)가 삭제.
		friendService.request(me.getId(), other.getFriendCode());
		friendService.accept(other.getId(), me.getId());
		friendService.deleteFriend(other.getId(), me.getId());
		assertThat(friendshipRepository.findPair(me.getId(), other.getId())).isEmpty();

		// 내가 addressee 였던 관계 — 내가 삭제.
		friendService.request(other.getId(), me.getFriendCode());
		friendService.accept(me.getId(), other.getId());
		friendService.deleteFriend(me.getId(), other.getId());
		assertThat(friendshipRepository.findPair(me.getId(), other.getId())).isEmpty();
	}

	@Test
	@DisplayName("관계가 없으면 삭제는 9424 다")
	void 관계가_없으면_삭제는_9424를_반환한다() {
		assertThatThrownBy(() -> friendService.deleteFriend(me.getId(), other.getId()))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", FriendErrorCode.FRIENDSHIP_NOT_FOUND);
	}

	@Test
	@DisplayName("대기 중 요청은 친구 삭제 대상이 아니라 9424 다 (요청 취소 FR 없음)")
	void 대기중_요청은_친구_삭제_대상이_아니라_9424다() {
		friendService.request(other.getId(), me.getFriendCode());

		assertThatThrownBy(() -> friendService.deleteFriend(me.getId(), other.getId()))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", FriendErrorCode.FRIENDSHIP_NOT_FOUND);
	}
}
