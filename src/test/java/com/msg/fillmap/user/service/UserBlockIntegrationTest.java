package com.msg.fillmap.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import software.amazon.awssdk.services.s3.S3Client;

import com.msg.fillmap.friend.entity.Friendship;
import com.msg.fillmap.friend.repository.FriendshipRepository;
import com.msg.fillmap.friend.service.FriendService;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.moderation.dto.ReportCreateRequestDto;
import com.msg.fillmap.moderation.service.ReportService;
import com.msg.fillmap.user.dto.BlockedUserResponseDto;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.entity.UserBlock;
import com.msg.fillmap.user.entity.UserBlockId;
import com.msg.fillmap.user.exception.UserErrorCode;
import com.msg.fillmap.user.repository.UserBlockRepository;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.dto.GridGlobalVideoResponseDto;
import com.msg.fillmap.video.entity.Visibility;
import com.msg.fillmap.video.exception.VideoErrorCode;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.service.VideoService;
import com.msg.fillmap.video.support.GeoSupport;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;

/**
 * 사용자 차단·해제·목록과 친구 관계 삭제 (MSG-569, 실 DB). @Transactional 롤백 격리로 공유 로컬 DB 에 시드를
 * 남기지 않는다(FriendIntegrationTest 패턴). 동시 차단은 실제 커밋이 필요해 UserBlockConcurrencyTest 로 뗐고,
 * 콘텐츠 비노출(AC-06~10)은 각 도메인 테스트가 맡는다 — 여기서는 AC-18 의 양방향 잔존만 판정 leaf 로 본다.
 */
@SpringBootTest
@Transactional
@DisplayName("사용자 차단 (실 DB)")
class UserBlockIntegrationTest {

	private static final LocalDateTime FIRST = LocalDateTime.of(2026, 9, 8, 3, 10);
	private static final LocalDateTime LATER = FIRST.plusHours(2);
	/** 서해 먼바다 기준 격자 — 다른 테스트 대역과 겹치지 않는 자리. */
	private static final GridIndex 바다 = GridEncoder.decode(GridEncoder.encode(33.5, 125.95));

	@Autowired
	private UserBlockService userBlockService;

	@Autowired
	private UserBlockQueryService userBlockQueryService;

	@Autowired
	private UserBlockRepository userBlockRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private FriendService friendService;

	@Autowired
	private FriendshipRepository friendshipRepository;

	@Autowired
	private ReportService reportService;

	@Autowired
	private VideoRepository videoRepository;

	@Autowired
	private VideoService videoService;

	/** 격자 목록·재생의 썸네일 presign 은 AWS 자격증명 의존이라 mock — 여기서 볼 축은 차단 필터다. */
	@MockitoBean
	private ThumbnailUrlPresigner thumbnailUrlPresigner;

	@Autowired
	private EntityManager em;

	/** 실 S3 호출 차단용 — 컨텍스트의 UserServiceImpl(계정 삭제 축)이 주입받는 의존성, 이 테스트에선 미사용. */
	@MockitoBean
	private S3Client s3Client;

	private User me;
	private User other;

	@BeforeEach
	void setUp() {
		me = seedUser("차단자");
		other = seedUser("상대방");
	}

	private User seedUser(String nickname) {
		return userRepository.save(
			User.createLocalUser("block-" + UUID.randomUUID() + "@example.com", "hash", nickname));
	}

	/** 고정 클럭 서비스 — created_at 보존(AC-02) 검증용. 바깥 트랜잭션에 합류하므로 프록시 없이도 한 커밋이다. */
	private UserBlockService serviceAt(LocalDateTime now) {
		return new UserBlockService(userBlockRepository, userRepository, friendService,
			Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
	}

	private UserBlock row(User blocker, User blocked) {
		em.flush();
		em.clear();
		return userBlockRepository.findById(new UserBlockId(blocker.getId(), blocked.getId())).orElse(null);
	}

	private long rowCount(User blocker, User blocked) {
		em.flush();
		return ((Number) em.createNativeQuery(
				"SELECT COUNT(*) FROM user_blocks WHERE blocker_id = :a AND blocked_id = :b")
			.setParameter("a", blocker.getId()).setParameter("b", blocked.getId())
			.getSingleResult()).longValue();
	}

	@Nested
	@DisplayName("차단")
	class Block {

		// 검증: FR-MOD-15, AC-569-01
		@Test
		@DisplayName("자기 자신을 차단하면 1430 이다")
		void 자기_자신을_차단하면_1430이다() {
			assertThatThrownBy(() -> userBlockService.block(me.getId(), me.getId()))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(UserErrorCode.SELF_BLOCK);
			assertThat(rowCount(me, me)).isZero();
		}

		// 검증: FR-MOD-15, AC-569-01
		@Test
		@DisplayName("없는 사용자를 차단하면 1404 다")
		void 없는_사용자를_차단하면_1404다() {
			assertThatThrownBy(() -> userBlockService.block(me.getId(), -1L))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(UserErrorCode.USER_NOT_FOUND);
		}

		// 검증: FR-MOD-15, AC-569-01
		@Test
		@DisplayName("차단하면 user_blocks 행이 한 건 생긴다")
		void 차단하면_user_blocks_행이_한_건_생긴다() {
			userBlockService.block(me.getId(), other.getId());

			assertThat(rowCount(me, other)).isEqualTo(1);
			assertThat(rowCount(other, me)).isZero();
			assertThat(userBlockQueryService.isBlockedEitherWay(me.getId(), other.getId())).isTrue();
			assertThat(userBlockQueryService.isBlockedEitherWay(other.getId(), me.getId())).isTrue();
		}

		// 검증: FR-MOD-15, AC-569-02
		@Test
		@DisplayName("재차단해도 행은 한 건이고 created_at 은 처음 값이다")
		void 재차단해도_행은_한_건이고_created_at은_처음_값이다() {
			serviceAt(FIRST).block(me.getId(), other.getId());
			serviceAt(LATER).block(me.getId(), other.getId());

			assertThat(rowCount(me, other)).isEqualTo(1);
			assertThat(row(me, other).getCreatedAt()).isEqualTo(FIRST);
		}
	}

	@Nested
	@DisplayName("해제")
	class Unblock {

		// 검증: FR-MOD-15, AC-569-03
		@Test
		@DisplayName("해제하면 행이 사라진다")
		void 해제하면_행이_사라진다() {
			userBlockService.block(me.getId(), other.getId());

			userBlockService.unblock(me.getId(), other.getId());

			assertThat(rowCount(me, other)).isZero();
			assertThat(userBlockQueryService.isBlockedEitherWay(me.getId(), other.getId())).isFalse();
		}

		// 검증: FR-MOD-15, AC-569-03
		@Test
		@DisplayName("차단한 적 없는 사용자를 해제해도 성공한다")
		void 차단한_적_없는_사용자를_해제해도_200이다() {
			userBlockService.unblock(me.getId(), other.getId());

			assertThat(rowCount(me, other)).isZero();
		}

		// 검증: FR-MOD-15, AC-569-03
		@Test
		@DisplayName("없는 userId 를 해제해도 성공한다")
		void 없는_userId를_해제해도_200이다() {
			userBlockService.unblock(me.getId(), -1L);
		}

		// 검증: FR-MOD-17, AC-569-18
		@Test
		@DisplayName("서로 차단한 상태에서 한쪽만 해제해도 차단 관계는 남는다")
		void 서로_차단한_상태에서_한쪽만_해제해도_상대_콘텐츠는_계속_안_보인다() {
			userBlockService.block(me.getId(), other.getId());
			userBlockService.block(other.getId(), me.getId());

			userBlockService.unblock(me.getId(), other.getId());

			// 자기 방향 한 행만 지워지고 역방향(B→A)은 남아 판정이 양쪽 viewer 모두 true 다. 6종 경로가 전부 이
			// 판정(단건)이나 같은 술어(목록 NOT EXISTS)를 쓰므로 콘텐츠는 계속 보이지 않는다.
			assertThat(rowCount(me, other)).isZero();
			assertThat(rowCount(other, me)).isEqualTo(1);
			assertThat(userBlockQueryService.isBlockedEitherWay(me.getId(), other.getId())).isTrue();
			assertThat(userBlockQueryService.isBlockedEitherWay(other.getId(), me.getId())).isTrue();

			// 재생은 양쪽 viewer 모두 삭제 영상과 같은 3404, 격자 전역 목록은 양쪽 모두 상대 영상이 빠진다.
			Video mine = seedVideo(me);
			Video theirs = seedVideo(other);
			assertThatThrownBy(() -> videoService.getVideoPlayback(me.getId(), theirs.getId()))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(VideoErrorCode.VIDEO_NOT_FOUND);
			assertThatThrownBy(() -> videoService.getVideoPlayback(other.getId(), mine.getId()))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(VideoErrorCode.VIDEO_NOT_FOUND);
			assertThat(videoService.getGridGlobalVideos(me.getId(), mine.getGridId(), null, 20).videos())
				.extracting(GridGlobalVideoResponseDto::videoId).containsExactly(mine.getId());
			assertThat(videoService.getGridGlobalVideos(other.getId(), mine.getGridId(), null, 20).videos())
				.extracting(GridGlobalVideoResponseDto::videoId).containsExactly(theirs.getId());
		}
	}

	@Nested
	@DisplayName("목록")
	class BlockedList {

		// 검증: FR-MOD-15, AC-569-04
		@Test
		@DisplayName("목록은 최신 차단 순이고 항목 필드가 맞다")
		void 목록은_최신_차단_순이고_항목_필드가_맞다() {
			User third = seedUser("세번째");
			third.changeProfileImage("https://cdn.example.com/p/3.jpg");
			serviceAt(FIRST).block(me.getId(), other.getId());
			serviceAt(LATER).block(me.getId(), third.getId());

			List<BlockedUserResponseDto> list = userBlockService.getBlockedUsers(me.getId());

			assertThat(list).extracting(BlockedUserResponseDto::userId)
				.containsExactly(third.getId(), other.getId());
			assertThat(list.get(0))
				.isEqualTo(new BlockedUserResponseDto(third.getId(), "세번째", "https://cdn.example.com/p/3.jpg", LATER));
			assertThat(list.get(1))
				.isEqualTo(new BlockedUserResponseDto(other.getId(), "상대방", null, FIRST));
		}

		// 검증: FR-MOD-15, AC-569-04
		@Test
		@DisplayName("나를 차단한 사람은 내 목록에 없다")
		void 나를_차단한_사람은_내_목록에_없다() {
			userBlockService.block(other.getId(), me.getId());

			assertThat(userBlockService.getBlockedUsers(me.getId())).isEmpty();
			assertThat(userBlockService.getBlockedUsers(other.getId()))
				.extracting(BlockedUserResponseDto::userId).containsExactly(me.getId());
		}

		// 검증: FR-MOD-15, AC-569-04
		@Test
		@DisplayName("차단이 없으면 빈 배열이다")
		void 차단이_없으면_빈_배열이다() {
			assertThat(userBlockService.getBlockedUsers(me.getId())).isEmpty();
		}
	}

	@Nested
	@DisplayName("친구 관계 삭제")
	class FriendshipRemoval {

		// 검증: FR-MOD-16, AC-569-05
		@Test
		@DisplayName("차단하면 ACCEPTED 친구 관계가 삭제된다")
		void 차단하면_ACCEPTED_친구_관계가_삭제된다() {
			friendService.request(other.getId(), me.getFriendCode());
			friendService.accept(me.getId(), other.getId());
			assertThat(friendshipRepository.existsAcceptedPair(me.getId(), other.getId())).isTrue();

			userBlockService.block(me.getId(), other.getId());

			assertThat(friendshipRepository.findPairWithoutLock(me.getId(), other.getId())).isEmpty();
		}

		// 검증: FR-MOD-16, AC-569-05
		@Test
		@DisplayName("차단하면 양방향 PENDING 요청이 삭제된다")
		void 차단하면_양방향_PENDING_요청이_삭제된다() {
			// 상대가 나에게 보낸 대기 요청 — 방향이 반대여도 지워져야 한다.
			friendshipRepository.save(Friendship.request(other.getId(), me.getId()));
			userBlockService.block(me.getId(), other.getId());
			assertThat(friendshipRepository.findPairWithoutLock(me.getId(), other.getId())).isEmpty();

			// 내가 보낸 대기 요청 — 같은 방향도 지워진다.
			User third = seedUser("세번째");
			friendshipRepository.save(Friendship.request(me.getId(), third.getId()));
			userBlockService.block(me.getId(), third.getId());
			assertThat(friendshipRepository.findPairWithoutLock(me.getId(), third.getId())).isEmpty();
		}

		// 검증: FR-MOD-16, AC-569-05
		@Test
		@DisplayName("해제해도 친구 관계는 돌아오지 않는다")
		void 해제해도_친구_관계는_돌아오지_않는다() {
			friendService.request(other.getId(), me.getFriendCode());
			friendService.accept(me.getId(), other.getId());
			userBlockService.block(me.getId(), other.getId());

			userBlockService.unblock(me.getId(), other.getId());

			assertThat(friendshipRepository.findPairWithoutLock(me.getId(), other.getId())).isEmpty();
			assertThat(friendshipRepository.existsAcceptedPair(me.getId(), other.getId())).isFalse();
		}
	}

	@Nested
	@DisplayName("차단과 무관한 것")
	class Unaffected {

		// 검증: FR-MOD-17, AC-569-11
		@Test
		@DisplayName("차단한 사용자의 영상도 신고할 수 있다")
		void 차단한_사용자의_영상도_신고할_수_있다() {
			Video video = seedVideo(other);
			userBlockService.block(me.getId(), other.getId());

			assertThat(reportService.report(me.getId(), video.getId(), new ReportCreateRequestDto("SPAM", null))
				.status()).isEqualTo("PENDING");
		}

		// 검증: FR-MOD-20, AC-569-14
		@Test
		@DisplayName("사용자를 삭제하면 걸었거나 당한 차단 행이 함께 사라진다")
		void 사용자를_삭제하면_걸었거나_당한_차단_행이_함께_사라진다() {
			User third = seedUser("세번째");
			userBlockService.block(me.getId(), other.getId());
			userBlockService.block(third.getId(), me.getId());

			userRepository.delete(me);
			em.flush();

			// 애플리케이션 코드 없이 양쪽 FK 의 ON DELETE CASCADE 가 지운다.
			assertThat(rowCount(me, other)).isZero();
			assertThat(rowCount(third, me)).isZero();
		}

	}

	/** 바다 격자에 PUBLIC·READY·ACTIVE 영상 하나 — 재생 후보이자 격자 전역 목록 후보다. */
	private Video seedVideo(User owner) {
		String gridId = 바다.gridY() + "_" + 바다.gridX();
		GridPoint center = GridEncoder.center(gridId);
		videoRepository.upsertGrid(gridId, 바다.gridY(), 바다.gridX(), center.lat(), center.lon(),
			GeoSupport.bboxWkt(gridId));
		Video video = videoRepository.save(Video.create(owner.getId(), gridId,
			"videos/original/" + UUID.randomUUID() + ".mp4", GeoSupport.toPoint(center.lat(), center.lon()),
			(short) 10, FIRST, Visibility.PUBLIC));
		video.markReady("videos/encoded/" + UUID.randomUUID() + ".mp4", "thumb/" + UUID.randomUUID() + ".jpg",
			video.getDurationSec());
		em.flush();
		return video;
	}
}
