package com.msg.fillmap.friend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
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

import com.msg.fillmap.friend.exception.FriendErrorCode;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.dto.FriendGridVideoResponseDto;
import com.msg.fillmap.video.dto.VideoPlaybackResponseDto;
import com.msg.fillmap.video.service.VideoService;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;

/**
 * 친구 격자 영상 목록 (MSG-187 D5, 실 DB). @Transactional 롤백 격리로 공유 로컬 DB 에 시드를 남기지 않고,
 * 합성 격자는 서해 공해상 기준점(행정동 무귀속)이라 regions 를 건드리지 않는다 (FriendProfileIntegrationTest
 * 패턴). 검증 축 = 공개범위 게이트(PUBLIC+FRIENDS 만, PRIVATE 절대 제외)·상태 게이트(ACTIVE·READY)·
 * 최신순 정렬·목록과 재생의 정합.
 */
@SpringBootTest
@Transactional
@DisplayName("친구 격자 영상 목록 (실 DB)")
class FriendGridVideosTest {

	// 서해 공해상 — 어떤 행정동에도 안 속하는 기준점.
	private static final double SEA_LAT = 36.1;
	private static final double SEA_LON = 124.1;

	private static final LocalDateTime BASE = LocalDateTime.of(2026, 7, 20, 12, 0, 0);

	@Autowired
	private FriendService friendService;

	@Autowired
	private VideoService videoService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	/** presign 은 AWS 자격증명 의존을 피해 mock 으로 대체하고 key 를 URL 에 그대로 실어 어떤 영상인지까지 단언한다. */
	@MockitoBean
	private ThumbnailUrlPresigner thumbnailUrlPresigner;

	/** 실 S3 호출 차단용 — 컨텍스트의 다른 빈이 주입받는 의존성, 이 테스트에선 미사용. */
	@MockitoBean
	private S3Client s3Client;

	private User me;
	private User friend;
	private long baseY;
	private long baseX;
	private String gridId;

	@BeforeEach
	void setUp() {
		given(thumbnailUrlPresigner.presign(anyString())).willAnswer(call -> "https://signed/" + call.getArgument(0));
		given(thumbnailUrlPresigner.ttlSeconds()).willReturn(600L);
		me = seedUser("나채움");
		friend = seedUser("채우미");
		becomeFriends(me, friend);
		GridIndex base = GridEncoder.decode(GridEncoder.encode(SEA_LAT, SEA_LON));
		baseY = base.gridY();
		baseX = base.gridX();
		gridId = grid(0, 0);
	}

	@Test
	@DisplayName("PUBLIC 과 FRIENDS 영상만 최신순으로 반환한다 (FR-4·D5)")
	void PUBLIC과_FRIENDS_영상만_최신순으로_반환한다() {
		long older = seedVideo("public", "PUBLIC", "READY", "ACTIVE", BASE.minusMinutes(2));
		seedVideo("private", "PRIVATE", "READY", "ACTIVE", BASE.minusMinutes(1));
		long newest = seedVideo("friends", "FRIENDS", "READY", "ACTIVE", BASE);

		List<FriendGridVideoResponseDto> videos = videos(gridId);

		assertThat(videos).extracting(FriendGridVideoResponseDto::videoId).containsExactly(newest, older);
		assertThat(videos.get(0).thumbnailUrl()).isEqualTo("https://signed/thumbs/m187-friends.jpg");
		assertThat(videos.get(0).durationSec()).isEqualTo((short) 10);
		assertThat(videos.get(0).createdAt()).isEqualTo(BASE);
	}

	@Test
	@DisplayName("PRIVATE 영상만 있는 격자는 빈 배열이다 (FR-4)")
	void PRIVATE_영상은_포함되지_않는다() {
		seedVideo("private-only", "PRIVATE", "READY", "ACTIVE", BASE);

		assertThat(videos(gridId)).isEmpty();
	}

	@Test
	@DisplayName("삭제·블라인드·인코딩 미완 영상은 포함되지 않는다 (FR-9)")
	void 삭제_블라인드_인코딩_미완_영상은_포함되지_않는다() {
		seedVideo("deleted", "PUBLIC", "READY", "DELETED", BASE);
		seedVideo("blinded", "PUBLIC", "READY", "BLINDED", BASE);
		seedVideo("encoding", "FRIENDS", "ENCODING", "ACTIVE", BASE);

		assertThat(videos(gridId)).isEmpty();
	}

	@Test
	@DisplayName("목록에 보인 FRIENDS 영상은 재생도 허용된다 (FR-5)")
	void 목록에_보인_FRIENDS_영상은_재생도_허용된다() {
		long videoId = seedVideo("playable", "FRIENDS", "READY", "ACTIVE", BASE);

		assertThat(videos(gridId)).extracting(FriendGridVideoResponseDto::videoId).containsExactly(videoId);

		VideoPlaybackResponseDto playback = videoService.getVideoPlayback(me.getId(), videoId);

		assertThat(playback.playbackUrl()).isEqualTo("https://signed/videos/encoded/m187-playable.mp4");
	}

	@Test
	@DisplayName("비친구 조회는 9424 다 (FR-2)")
	void 비친구_조회는_9424다() {
		User stranger = seedUser("남");
		seedVideo("public-of-stranger", "PUBLIC", "READY", "ACTIVE", BASE);

		assertThatThrownBy(() -> friendService.getFriendGridVideos(stranger.getId(), friend.getId(), gridId))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", FriendErrorCode.FRIENDSHIP_NOT_FOUND);
	}

	@Test
	@DisplayName("친구 삭제 직후 조회는 9424 다 — 실시간 판정 (FR-7)")
	void 친구_삭제_직후_조회는_9424다() {
		seedVideo("public", "PUBLIC", "READY", "ACTIVE", BASE);
		assertThat(videos(gridId)).hasSize(1);

		friendService.deleteFriend(me.getId(), friend.getId());

		assertThatThrownBy(() -> videos(gridId))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", FriendErrorCode.FRIENDSHIP_NOT_FOUND);
	}

	@Test
	@DisplayName("친구가 점령하지 않은 격자는 빈 배열이다 (D7)")
	void 친구가_점령하지_않은_격자는_빈_배열이다() {
		seedVideo("elsewhere", "PUBLIC", "READY", "ACTIVE", BASE);

		assertThat(videos(grid(0, 1))).isEmpty();
	}

	@Test
	@DisplayName("존재하지 않는 gridId 는 빈 배열이다 — 포맷 무검증 (D7)")
	void 존재하지_않는_gridId는_빈_배열이다() {
		assertThat(videos("99999999_99999999")).isEmpty();
		assertThat(videos("not-a-grid")).isEmpty();
	}

	/** 진입점은 friend 도메인 — 친구 판정(9424)을 지나 video 도메인 위임까지 한 경로로 검증한다 (D1·D5). */
	private List<FriendGridVideoResponseDto> videos(String targetGridId) {
		return friendService.getFriendGridVideos(me.getId(), friend.getId(), targetGridId);
	}

	private User seedUser(String nickname) {
		return userRepository.save(
			User.createLocalUser("m187-" + UUID.randomUUID() + "@example.com", "hash", nickname));
	}

	/** requester 가 요청하고 addressee 가 수락해 ACCEPTED 관계를 만든다. */
	private void becomeFriends(User requester, User addressee) {
		friendService.request(requester.getId(), addressee.getFriendCode());
		friendService.accept(addressee.getId(), requester.getId());
	}

	private String grid(long dy, long dx) {
		return GridFixtures.seedGrid(em, baseY + dy, baseX + dx);
	}

	/**
	 * 친구 소유 영상 하나를 삽입하고 id 를 돌려준다. label 로 썸네일·인코딩본 key 를 만들어 presign 결과에서
	 * 어떤 영상이 골라졌는지 식별한다. geom NOT NULL 이라 서해 공해상 좌표를 넣고, created_at 은 정렬 단언을
	 * 위해 항상 명시한다(DB 기본값 now() 면 같은 밀리초에 몰린다).
	 */
	private long seedVideo(String label, String visibility, String processingStatus, String status,
		LocalDateTime createdAt) {
		String originalKey = "videos/original/m187-" + label + "-" + UUID.randomUUID() + ".mp4";
		em.createNativeQuery("""
			INSERT INTO videos (
				user_id, grid_id, original_s3_key, encoded_url, thumbnail_url, geom, duration_sec, recorded_at,
				processing_status, visibility, status, created_at
			)
			VALUES (
				:userId, :gridId, :originalKey, :encodedKey, :thumbKey,
				ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography,
				10, now(), :processingStatus, :visibility, :status, :createdAt
			)
			""")
			.setParameter("userId", friend.getId())
			.setParameter("gridId", gridId)
			.setParameter("originalKey", originalKey)
			.setParameter("encodedKey", "videos/encoded/m187-" + label + ".mp4")
			.setParameter("thumbKey", "thumbs/m187-" + label + ".jpg")
			.setParameter("processingStatus", processingStatus)
			.setParameter("visibility", visibility)
			.setParameter("status", status)
			.setParameter("createdAt", createdAt)
			.setParameter("lon", SEA_LON)
			.setParameter("lat", SEA_LAT)
			.executeUpdate();
		return ((Number) em.createNativeQuery("SELECT id FROM videos WHERE original_s3_key = :originalKey")
			.setParameter("originalKey", originalKey)
			.getSingleResult()).longValue();
	}
}
