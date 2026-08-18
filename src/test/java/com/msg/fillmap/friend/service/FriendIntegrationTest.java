package com.msg.fillmap.friend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import software.amazon.awssdk.services.s3.S3Client;

import com.msg.fillmap.friend.dto.FriendListItemResponseDto;
import com.msg.fillmap.friend.dto.ReceivedFriendRequestResponseDto;
import com.msg.fillmap.friend.entity.Friendship;
import com.msg.fillmap.friend.entity.FriendshipId;
import com.msg.fillmap.friend.entity.FriendshipStatus;
import com.msg.fillmap.friend.exception.FriendErrorCode;
import com.msg.fillmap.friend.repository.FriendshipRepository;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.service.GridQueryService;
import com.msg.fillmap.notification.service.NotificationCommandService;
import com.msg.fillmap.user.entity.GridColor;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.usergrid.service.UserGridQueryService;
import com.msg.fillmap.video.service.VideoService;

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

	// 친구 판정 정본 (MSG-312) — FriendService.isFriend 를 대체한 leaf 빈. 판정 자체는 여기에만 있다.
	@Autowired
	private FriendshipQueryService friendshipQueryService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private FriendshipRepository friendshipRepository;

	// FRIEND 알림 기록 관찰 + 고정 클럭 수동 조립 재료 (MSG-416) — 아래 4개는 FriendServiceImpl 전체 생성자용.
	@Autowired
	private NotificationCommandService notificationCommandService;

	@Autowired
	private UserGridQueryService userGridQueryService;

	@Autowired
	private GridQueryService gridQueryService;

	@Autowired
	private VideoService videoService;

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

	// 검증: FR-FRIEND-01
	@Test
	@DisplayName("내 친구 코드를 조회한다 (FR-1)")
	void 내_친구_코드를_조회한다() {
		assertThat(friendService.getMyFriendCode(me.getId()).friendCode())
			.isEqualTo(me.getFriendCode());
	}

	// 검증: FR-FRIEND-02
	@Test
	@DisplayName("코드로 상대 닉네임을 미리본다 (FR-3)")
	void 코드로_상대_닉네임을_미리본다() {
		assertThat(friendService.preview(other.getFriendCode()).nickname()).isEqualTo("상대방");
	}

	// 검증: FR-FRIEND-02
	@Test
	@DisplayName("없는 코드 미리보기는 9404 다")
	void 없는_코드_미리보기는_9404를_반환한다() {
		assertThatThrownBy(() -> friendService.preview("XXXXXXXX"))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", FriendErrorCode.FRIEND_CODE_NOT_FOUND);
	}

	// 검증: FR-FRIEND-02
	@Test
	@DisplayName("코드로 요청하면 PENDING 행이 생긴다 (FR-4)")
	void 코드로_요청하면_PENDING_행이_생긴다() {
		FriendshipStatus status = friendService.request(me.getId(), other.getFriendCode()).status();

		assertThat(status).isEqualTo(FriendshipStatus.PENDING);
		Friendship saved = friendshipRepository.findById(new FriendshipId(me.getId(), other.getId())).orElseThrow();
		assertThat(saved.getStatus()).isEqualTo(FriendshipStatus.PENDING);
		assertThat(saved.getRespondedAt()).isNull();
	}

	// 검증: FR-FRIEND-02
	@Test
	@DisplayName("자기 코드로 요청하면 9400 이다 (FR-5)")
	void 자기_코드로_요청하면_9400을_반환한다() {
		assertThatThrownBy(() -> friendService.request(me.getId(), me.getFriendCode()))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", FriendErrorCode.SELF_FRIEND_REQUEST);
	}

	// 검증: FR-FRIEND-02
	@Test
	@DisplayName("없는 코드로 요청하면 9404 다 (FR-6)")
	void 없는_코드로_요청하면_9404를_반환한다() {
		assertThatThrownBy(() -> friendService.request(me.getId(), "XXXXXXXX"))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", FriendErrorCode.FRIEND_CODE_NOT_FOUND);
	}

	// 검증: FR-FRIEND-02
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

	// 검증: FR-FRIEND-02
	@Test
	@DisplayName("내가 보낸 요청이 대기 중이면 재요청은 9410 이다 (FR-7)")
	void 내가_보낸_요청이_대기중이면_재요청은_9410을_반환한다() {
		friendService.request(me.getId(), other.getFriendCode());

		assertThatThrownBy(() -> friendService.request(me.getId(), other.getFriendCode()))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", FriendErrorCode.FRIEND_REQUEST_ALREADY_PENDING);
	}

	// 검증: FR-FRIEND-03
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

	// 검증: FR-FRIEND-04
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

	// 검증: FR-FRIEND-04
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

	// 검증: FR-FRIEND-04
	@Test
	@DisplayName("거절하면 행이 삭제된다 (FR-11·§D3)")
	void 거절하면_행이_삭제된다() {
		friendService.request(other.getId(), me.getFriendCode());

		friendService.reject(me.getId(), other.getId());

		assertThat(friendshipRepository.findPair(me.getId(), other.getId())).isEmpty();
	}

	// 검증: FR-FRIEND-04
	@Test
	@DisplayName("거절 후 상대는 같은 요청을 다시 보낼 수 있다 (FR-11·§D3 불변식)")
	void 거절_후_상대는_같은_요청을_다시_보낼_수_있다() {
		friendService.request(other.getId(), me.getFriendCode());
		friendService.reject(me.getId(), other.getId());

		FriendshipStatus status = friendService.request(other.getId(), me.getFriendCode()).status();

		assertThat(status).isEqualTo(FriendshipStatus.PENDING);
	}

	// 검증: FR-FRIEND-04
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

	// 검증: FR-FRIEND-06, FR-USER-05
	@Test
	@DisplayName("친구 목록은 방향 무관하게 수락 시각 내림차순이다 (MSG-186 FR-1·2)")
	void 친구_목록은_방향_무관하게_수락_시각_내림차순이다() {
		// 내가 requester 인 관계를 먼저, 내가 addressee 인 관계를 나중에 수락한다 —
		// 기대 순서(third → other)는 시드 순서·id 오름차순 타이브레이크와 반대라 정렬 키가 respondedAt 임을 가른다.
		User third = seedUser("세번째");
		becomeFriends(me, other);
		becomeFriends(third, me);

		List<FriendListItemResponseDto> friends = friendService.getFriends(me.getId(), null);

		assertThat(friends).hasSize(2);
		assertThat(friends.get(0).userId()).isEqualTo(third.getId());
		assertThat(friends.get(0).nickname()).isEqualTo("세번째");
		assertThat(friends.get(0).profileImageUrl()).isNull();
		assertThat(friends.get(0).gridColor()).isEqualTo(GridColor.BLUE);
		assertThat(friends.get(1).userId()).isEqualTo(other.getId());
	}

	// 검증: FR-FRIEND-06
	@Test
	@DisplayName("sort=nickname 이면 닉네임순이고 동명이인은 id 순이다 (FR-2)")
	void sort_nickname_이면_닉네임순으로_정렬된다() {
		User bbb = seedUser("BBB");
		User aaaFirst = seedUser("AAA");
		User aaaSecond = seedUser("AAA");
		becomeFriends(me, bbb);
		becomeFriends(me, aaaFirst);
		becomeFriends(me, aaaSecond);

		List<FriendListItemResponseDto> friends = friendService.getFriends(me.getId(), "nickname");

		assertThat(friends).extracting(FriendListItemResponseDto::userId)
			.containsExactly(aaaFirst.getId(), aaaSecond.getId(), bbb.getId());
		// 대소문자 무시 (§D3) — 같은 정렬로 수렴한다.
		assertThat(friendService.getFriends(me.getId(), "NICKNAME"))
			.extracting(FriendListItemResponseDto::userId)
			.containsExactly(aaaFirst.getId(), aaaSecond.getId(), bbb.getId());
	}

	// 검증: FR-FRIEND-06
	@Test
	@DisplayName("친구가 없으면 빈 목록이다 — 에러 아님 (FR-4)")
	void 친구가_없으면_빈_목록이다() {
		assertThat(friendService.getFriends(me.getId(), null)).isEmpty();
	}

	// 검증: FR-FRIEND-06
	@Test
	@DisplayName("대기 중 요청 상대는 친구 목록에 없다 (FR-1)")
	void 대기중_요청_상대는_친구_목록에_없다() {
		friendService.request(other.getId(), me.getFriendCode());

		assertThat(friendService.getFriends(me.getId(), null)).isEmpty();
	}

	// 검증: FR-FRIEND-06
	@Test
	@DisplayName("잘못된 sort 값은 9420 이다 (§D3)")
	void 잘못된_sort_값은_9420을_반환한다() {
		assertThatThrownBy(() -> friendService.getFriends(me.getId(), "oldest"))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", FriendErrorCode.INVALID_FRIEND_SORT);
	}

	// 검증: FR-FRIEND-04
	@Test
	@DisplayName("친구 삭제 후 목록에서 즉시 사라진다 (FR-8)")
	void 친구_삭제_후_목록에서_즉시_사라진다() {
		becomeFriends(me, other);

		friendService.deleteFriend(me.getId(), other.getId());

		assertThat(friendService.getFriends(me.getId(), null)).isEmpty();
	}

	/** requester 가 요청하고 addressee 가 수락해 ACCEPTED 관계를 만든다. */
	private void becomeFriends(User requester, User addressee) {
		friendService.request(requester.getId(), addressee.getFriendCode());
		friendService.accept(addressee.getId(), requester.getId());
	}

	// 검증: FR-FRIEND-04
	@Test
	@DisplayName("ACCEPTED 행이 있으면 방향과 무관하게 친구다 (MSG-285 FR-4)")
	void ACCEPTED_행이_있으면_방향과_무관하게_친구다() {
		friendService.request(me.getId(), other.getFriendCode());
		friendService.accept(other.getId(), me.getId());

		// 행은 (me → other) 한 방향뿐이지만 판정은 대칭이어야 한다 — 누가 요청했는지는 공개 판정과 무관.
		assertThat(friendshipQueryService.isFriend(me.getId(), other.getId())).isTrue();
		assertThat(friendshipQueryService.isFriend(other.getId(), me.getId())).isTrue();
	}

	@Test
	@DisplayName("PENDING 행이나 행 없음은 친구가 아니다 (MSG-285 FR-4)")
	void PENDING_행이나_행_없음은_친구가_아니다() {
		assertThat(friendshipQueryService.isFriend(me.getId(), other.getId())).as("행 없음").isFalse();

		friendService.request(me.getId(), other.getFriendCode());

		assertThat(friendshipQueryService.isFriend(me.getId(), other.getId())).as("대기 중 요청").isFalse();
		assertThat(friendshipQueryService.isFriend(other.getId(), me.getId())).as("대기 중 요청 역방향").isFalse();
	}

	// 검증: FR-FRIEND-09
	@Test
	@DisplayName("친구 삭제 직후 친구 판정이 즉시 false 가 된다 (MSG-285 FR-6 실시간 판정)")
	void 친구_삭제_직후_친구_판정은_false다() {
		friendService.request(me.getId(), other.getFriendCode());
		friendService.accept(other.getId(), me.getId());

		friendService.deleteFriend(me.getId(), other.getId());

		// 캐시·비정규화가 없어 다음 조회가 곧바로 삭제를 반영한다.
		assertThat(friendshipQueryService.isFriend(me.getId(), other.getId())).isFalse();
	}

	@Test
	@DisplayName("역방향 중복 행은 DB 대칭 유니크가 거부한다 — V19 백스톱 (Codex 리뷰 반영)")
	void 역방향_중복_행은_DB_대칭_유니크가_거부한다() {
		// 상호 동시 요청 레이스를 리포지토리 직접 INSERT 로 재현 — 서비스 검증을 우회해도 DB 가 막는다.
		friendshipRepository.saveAndFlush(Friendship.request(me.getId(), other.getId()));

		assertThatThrownBy(() -> friendshipRepository.saveAndFlush(Friendship.request(other.getId(), me.getId())))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	// ---- FRIEND 알림 기록 (MSG-416) — 롤백 동반 소멸은 커밋 경계 관찰이 필요해 FriendNotificationRollbackTest 별도 ----

	// 검증: FR-NOTI-14
	@Test
	@DisplayName("친구 요청이 접수되면 수신자에게 FRIEND 알림이 기록된다 — 요청자 닉네임·KST 날짜 키 (D1·D2·D4)")
	void 친구_요청이_접수되면_수신자에게_FRIEND_알림이_기록된다() {
		// 고정 클럭(16:00Z = KST 다음 날 01:00)으로 KST 변환 자체를 단언한다 — UTC 로 새면 20260818 이 찍힌다.
		clockedService(new SteppingClock()).request(me.getId(), other.getFriendCode());

		Object[] row = notificationRow(other.getId(), "FRIEND_REQ:" + me.getId() + ":20260819");
		assertThat(row[0]).isEqualTo("FRIEND");
		assertThat(row[1]).isEqualTo("새 친구 요청");
		assertThat(row[2]).isEqualTo("나채움님이 친구 요청을 보냈어요");
		assertThat(notificationCount(me.getId())).isZero();
	}

	// 검증: FR-NOTI-14
	@Test
	@DisplayName("요청을 수락하면 요청자에게 수락 알림이 기록된다 — 수락자 닉네임, FRIEND_ACC 무작위 꼬리 키 (D1·D2)")
	void 요청을_수락하면_요청자에게_수락_알림이_기록된다() {
		friendService.request(other.getId(), me.getFriendCode());

		friendService.accept(me.getId(), other.getId());

		List<Object[]> rows = notificationRows(other.getId(), "FRIEND_ACC:" + me.getId() + ":");
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0)[1]).isEqualTo("FRIEND");
		assertThat(rows.get(0)[2]).isEqualTo("친구 요청 수락");
		assertThat(rows.get(0)[3]).isEqualTo("나채움님이 친구 요청을 수락했어요");
	}

	// 검증: FR-NOTI-14
	@Test
	@DisplayName("상호 요청 자동 수락은 먼저 요청한 쪽에만 수락 알림을 기록한다 — 나중 요청자 수신 0건 (FR-3)")
	void 상호_요청_자동_수락은_먼저_요청한_쪽에만_수락_알림이_기록된다() {
		// PENDING 을 직접 시드 — 요청 접수 알림 없이 자동 수락 경로의 기록만 관찰한다 (스펙 Given/When/Then).
		friendshipRepository.saveAndFlush(Friendship.request(other.getId(), me.getId()));

		FriendshipStatus status = friendService.request(me.getId(), other.getFriendCode()).status();

		assertThat(status).isEqualTo(FriendshipStatus.ACCEPTED);
		assertThat(notificationRows(other.getId(), "FRIEND_ACC:" + me.getId() + ":")).hasSize(1);
		assertThat(notificationCount(me.getId())).isZero();
	}

	// 검증: FR-NOTI-14
	@Test
	@DisplayName("거절은 어느 쪽에도 알림을 기록하지 않는다 (FR-4)")
	void 거절은_어느_쪽에도_알림을_기록하지_않는다() {
		friendshipRepository.saveAndFlush(Friendship.request(other.getId(), me.getId()));

		friendService.reject(me.getId(), other.getId());

		assertThat(notificationCount(me.getId())).isZero();
		assertThat(notificationCount(other.getId())).isZero();
	}

	// 검증: FR-NOTI-14
	@Test
	@DisplayName("같은 상대의 같은 날 재요청은 알림이 추가로 기록되지 않는다 — 날짜 키 DO NOTHING 흡수 (FR-8)")
	void 같은_상대의_같은_날_재요청은_알림이_추가로_기록되지_않는다() {
		friendService.request(me.getId(), other.getFriendCode());
		friendService.reject(other.getId(), me.getId());

		friendService.request(me.getId(), other.getFriendCode());

		// 거절 뒤 재요청(FR-7)이지만 같은 날이라 상한(FR-8)이 우선한다 — 행은 그대로 1건.
		assertThat(notificationCount(other.getId())).isEqualTo(1);
	}

	// 검증: FR-NOTI-14
	@Test
	@DisplayName("거절 후 다른 날 재요청은 새 알림이 기록된다 — 날짜가 키를 갈라 새 행 (FR-7)")
	void 거절_후_다른_날_재요청은_새_알림이_기록된다() {
		// KST 일 경계 전진용 수동 조립 — 프로덕션 생성자는 Clock.systemUTC() 고정 (BadgeNearMissIntegrationTest
		// 선례). @Transactional 테스트 트랜잭션이 열려 있어 프록시 없는 직접 호출도 같은 트랜잭션에서 돈다.
		SteppingClock clock = new SteppingClock();
		FriendServiceImpl clocked = clockedService(clock);
		clocked.request(me.getId(), other.getFriendCode());
		clocked.reject(other.getId(), me.getId());
		clock.plusDays(1);

		clocked.request(me.getId(), other.getFriendCode());

		assertThat(notificationCount(other.getId())).isEqualTo(2);
	}

	// 검증: FR-NOTI-14
	@Test
	@DisplayName("친구 삭제 후 재요청·재수락은 같은 날에도 새 수락 알림이 기록된다 — 무작위 UUID 꼬리 (D2 회귀 방지)")
	void 친구_삭제_후_재요청_재수락은_같은_날에도_새_수락_알림이_기록된다() {
		friendService.request(me.getId(), other.getFriendCode());
		friendService.accept(other.getId(), me.getId());
		friendService.deleteFriend(me.getId(), other.getId());

		friendService.request(me.getId(), other.getFriendCode());
		friendService.accept(other.getId(), me.getId());

		assertThat(notificationRows(me.getId(), "FRIEND_ACC:" + other.getId() + ":")).hasSize(2);
	}

	private long notificationCount(long userId) {
		return ((Number) em.createNativeQuery(
				"SELECT count(*) FROM notifications WHERE user_id = :userId")
			.setParameter("userId", userId)
			.getSingleResult()).longValue();
	}

	/** category·title·body 스냅샷 — 단언은 호출부에서 (BadgeNotificationIntegrationTest 관례). */
	private Object[] notificationRow(long userId, String eventKey) {
		return (Object[]) em.createNativeQuery("""
				SELECT category, title, body FROM notifications
				WHERE user_id = :userId AND event_key = :eventKey
				""")
			.setParameter("userId", userId)
			.setParameter("eventKey", eventKey)
			.getSingleResult();
	}

	/** 접두 매칭 행 조회 — FRIEND_ACC 키 꼬리가 무작위 UUID 라 완전 일치로는 못 찾는다 (D2). */
	@SuppressWarnings("unchecked")
	private List<Object[]> notificationRows(long userId, String keyPrefix) {
		return em.createNativeQuery("""
				SELECT event_key, category, title, body FROM notifications
				WHERE user_id = :userId AND event_key LIKE :prefix || '%'
				""")
			.setParameter("userId", userId)
			.setParameter("prefix", keyPrefix)
			.getResultList();
	}

	/** 고정 클럭 수동 조립 — 프로덕션 생성자는 Clock.systemUTC() 고정이라 빈으로는 못 돌린다 (D3). */
	private FriendServiceImpl clockedService(SteppingClock clock) {
		return new FriendServiceImpl(userRepository, friendshipRepository, userGridQueryService, gridQueryService,
			videoService, friendshipQueryService, notificationCommandService, clock);
	}

	/**
	 * 전진 가능 시계 (BadgeNearMissIntegrationTest 선례) — 기준 instant 는 KST(08-19)와 UTC(08-18)의
	 * 날짜가 갈리는 값으로 고정한다: 실시각 의존을 없애고, KST 변환을 빼먹는 회귀가 날짜 차이로 드러난다.
	 * Badge 원본과 달리 withZone 이 요청된 존을 보존한다 — 여기선 clock.withZone(KST) 의 KST 날짜 산출이
	 * 검증 축이라, this 반환(존 무시)이면 UTC 날짜로 계산돼 그 회귀를 못 잡는다 (Codex P2, 리더 판정).
	 * friend 쪽은 뱃지와 달리 DB statement_timestamp 와의 같은 날 정렬이 불필요하다 — 키 유니크만 본다.
	 */
	private static final class SteppingClock extends Clock {

		private Instant instant = Instant.parse("2026-08-18T16:00:00Z");

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			// 호출 시점 스냅숏 뷰 — 프로덕션은 withZone 직후 now() 한 번이라 충분하다 (plusDays 는 기반 시계에만).
			return Clock.fixed(instant, zone);
		}

		@Override
		public Instant instant() {
			return instant;
		}

		void plusDays(long days) {
			instant = instant.plus(Duration.ofDays(days));
		}
	}
}
