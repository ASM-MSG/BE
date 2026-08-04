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

import com.msg.fillmap.friend.dto.FriendCollectionGridResponseDto;
import com.msg.fillmap.friend.dto.FriendProfileResponseDto;
import com.msg.fillmap.friend.exception.FriendErrorCode;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.user.entity.GridColor;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.usergrid.dto.CollectionSummaryResponseDto;
import com.msg.fillmap.usergrid.service.UserGridQueryService;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;

/**
 * 친구 프로필·도감 요약 조회 (MSG-186, 실 DB). @Transactional 롤백 격리로 공유 로컬 DB 에 시드를 남기지
 * 않고, 합성 격자는 서해 공해상 기준점(행정동 무귀속)이라 regions 를 건드리지 않는다
 * (CollectionGridsRepositoryTest 패턴). 검증 축 = 관계 은닉(실패 전건 9424)과 썸네일 공개범위 정합
 * (재생 허용 영상 것만, 없으면 null).
 */
@SpringBootTest
@Transactional
@DisplayName("친구 프로필·도감 요약 조회 (실 DB)")
class FriendProfileIntegrationTest {

	// 서해 공해상 — 어떤 행정동에도 안 속하는 기준점.
	private static final double SEA_LAT = 36.0;
	private static final double SEA_LON = 124.0;

	private static final LocalDateTime BASE = LocalDateTime.of(2026, 7, 20, 12, 0, 0);

	private static final long UNKNOWN_USER_ID = 999_999_999L;

	@Autowired
	private FriendService friendService;

	@Autowired
	private UserGridQueryService userGridQueryService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	/**
	 * presign 은 AWS 자격증명 의존을 피해 mock 으로 대체하고 key 를 URL 에 그대로 실어, 쿼리가 어떤 영상의
	 * 썸네일을 골랐는지까지 단언한다 (VideoGlobalListViewCountIntegrationTest 선례). null key 는
	 * anyString() 에 걸리지 않아 null 을 반환한다 — 실빈과 동일한 동작.
	 */
	@MockitoBean
	private ThumbnailUrlPresigner thumbnailUrlPresigner;

	/** 실 S3 호출 차단용 — 컨텍스트의 다른 빈이 주입받는 의존성, 이 테스트에선 미사용. */
	@MockitoBean
	private S3Client s3Client;

	private User me;
	private User friend;
	private long baseY;
	private long baseX;

	@BeforeEach
	void setUp() {
		given(thumbnailUrlPresigner.presign(anyString())).willAnswer(call -> "https://signed/" + call.getArgument(0));
		me = seedUser("나채움");
		friend = seedUser("채우미");
		becomeFriends(me, friend);
		GridIndex base = GridEncoder.decode(GridEncoder.encode(SEA_LAT, SEA_LON));
		baseY = base.gridY();
		baseX = base.gridX();
	}

	@Test
	@DisplayName("친구 프로필은 닉네임·이미지·색상과 도감 요약을 담는다 (FR-5)")
	void 친구_프로필은_닉네임_이미지_색상과_도감_요약을_담는다() {
		String gridId = grid(0, 0);
		occupy(friend.getId(), gridId, BASE, BASE, 2, null);

		FriendProfileResponseDto profile = friendService.getFriendProfile(me.getId(), friend.getId());

		assertThat(profile.nickname()).isEqualTo("채우미");
		assertThat(profile.profileImageUrl()).isNull();
		assertThat(profile.gridColor()).isEqualTo(GridColor.BLUE);
		// 같은 산식 재사용 — 친구가 자기 도감에서 보는 값과 완전히 같아야 한다 (PRD 비기능).
		assertThat(profile.summary())
			.isEqualTo(CollectionSummaryResponseDto.from(userGridQueryService.getCollectionSummary(friend.getId())));
		assertThat(profile.summary().totalGridCount()).isEqualTo(1);
		assertThat(profile.summary().totalVideoCount()).isEqualTo(2L);

		FriendCollectionGridResponseDto item = profile.recentGrids().get(0);
		assertThat(item.gridId()).isEqualTo(gridId);
		assertThat(item.gridY()).isEqualTo((int) baseY);
		assertThat(item.gridX()).isEqualTo((int) baseX);
		assertThat(item.firstCollectedAt()).isEqualTo(BASE);
		assertThat(item.regionName()).isNull();
	}

	@Test
	@DisplayName("수집 0건 친구는 요약 0과 빈 격자 목록이다 — 에러 아님")
	void 수집_0건_친구는_요약_0과_빈_격자_목록이다() {
		FriendProfileResponseDto profile = friendService.getFriendProfile(me.getId(), friend.getId());

		assertThat(profile.summary().totalGridCount()).isZero();
		assertThat(profile.summary().totalVideoCount()).isZero();
		assertThat(profile.summary().visitedRegionCount()).isZero();
		assertThat(profile.recentGrids()).isEmpty();
	}

	@Test
	@DisplayName("비친구 조회는 9424 다 (FR-7)")
	void 비친구_조회는_9424를_반환한다() {
		User stranger = seedUser("남");

		assertThatThrownBy(() -> friendService.getFriendProfile(me.getId(), stranger.getId()))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", FriendErrorCode.FRIENDSHIP_NOT_FOUND);
	}

	@Test
	@DisplayName("본인 ID 조회는 9424 다 — 본인 도감은 도감 API 몫 (FR-7)")
	void 본인_ID_조회는_9424를_반환한다() {
		assertThatThrownBy(() -> friendService.getFriendProfile(me.getId(), me.getId()))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", FriendErrorCode.FRIENDSHIP_NOT_FOUND);
	}

	@Test
	@DisplayName("대기 중 요청 상대 조회는 9424 다 (FR-7)")
	void 대기중_요청_상대_조회는_9424를_반환한다() {
		User pending = seedUser("대기");
		friendService.request(pending.getId(), me.getFriendCode());

		assertThatThrownBy(() -> friendService.getFriendProfile(me.getId(), pending.getId()))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", FriendErrorCode.FRIENDSHIP_NOT_FOUND);
	}

	@Test
	@DisplayName("미존재 userId 조회는 비친구와 구분 불가한 9424 다 — 계정 존재 은닉 (FR-7)")
	void 미존재_userId_조회는_9424를_반환한다() {
		assertThatThrownBy(() -> friendService.getFriendProfile(me.getId(), UNKNOWN_USER_ID))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", FriendErrorCode.FRIENDSHIP_NOT_FOUND);
	}

	@Test
	@DisplayName("친구 삭제 즉시 프로필 조회는 9424 다 — 실시간 판정 (FR-8)")
	void 친구_삭제_즉시_프로필_조회는_9424를_반환한다() {
		friendService.deleteFriend(me.getId(), friend.getId());

		assertThatThrownBy(() -> friendService.getFriendProfile(me.getId(), friend.getId()))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", FriendErrorCode.FRIENDSHIP_NOT_FOUND);
	}

	@Test
	@DisplayName("최근 수집 격자는 수집 시각 역순 최대 30개다 (FR-6)")
	void 최근_수집_격자는_수집_시각_역순_최대_30개다() {
		// i=0 이 가장 최근(BASE), i=30 이 가장 오래됨 — 31개 중 상위 30 만 나와야 한다.
		String newest = null;
		String oldest = null;
		for (int i = 0; i <= 30; i++) {
			String gridId = grid(0, i);
			occupy(friend.getId(), gridId, BASE.minusMinutes(i), BASE.minusMinutes(i), 1, null);
			if (i == 0) {
				newest = gridId;
			}
			if (i == 30) {
				oldest = gridId;
			}
		}

		List<FriendCollectionGridResponseDto> grids =
			friendService.getFriendProfile(me.getId(), friend.getId()).recentGrids();

		assertThat(grids).hasSize(30);
		assertThat(grids.get(0).gridId()).isEqualTo(newest);
		assertThat(grids).extracting(FriendCollectionGridResponseDto::gridId).doesNotContain(oldest);
	}

	@Test
	@DisplayName("공개 영상이 있는 격자는 썸네일이 붙는다 (FR-6)")
	void 공개_영상이_있는_격자는_썸네일이_붙는다() {
		String gridId = grid(0, 0);
		seedVideo(friend.getId(), gridId, "thumbs/m186-public.jpg", "PUBLIC", "READY");
		occupy(friend.getId(), gridId, BASE, BASE, 1, null);

		FriendCollectionGridResponseDto item = firstGrid();

		assertThat(item.thumbnailUrl()).isEqualTo("https://signed/thumbs/m186-public.jpg");
	}

	@Test
	@DisplayName("비공개 영상만 있는 격자는 썸네일이 null 이고 격자 사실은 내려간다 (FR-6)")
	void 비공개_영상만_있는_격자는_썸네일이_null_이고_격자_사실은_내려간다() {
		String gridId = grid(0, 0);
		long privateVideo = seedVideo(friend.getId(), gridId, "thumbs/m186-private.jpg", "PRIVATE", "READY");
		occupy(friend.getId(), gridId, BASE, BASE, 1, privateVideo);

		FriendCollectionGridResponseDto item = firstGrid();

		assertThat(item.thumbnailUrl()).isNull();
		assertThat(item.gridId()).isEqualTo(gridId);
		assertThat(item.videoCount()).isEqualTo(1);
		assertThat(item.firstCollectedAt()).isEqualTo(BASE);
	}

	@Test
	@DisplayName("cover 가 비공개면 다른 공개 영상 썸네일로 폴백한다 (§D6)")
	void cover가_비공개면_다른_공개_영상_썸네일로_폴백한다() {
		String gridId = grid(0, 0);
		long privateCover = seedVideo(friend.getId(), gridId, "thumbs/m186-cover-private.jpg", "PRIVATE", "READY");
		seedVideo(friend.getId(), gridId, "thumbs/m186-fallback.jpg", "PUBLIC", "READY");
		occupy(friend.getId(), gridId, BASE, BASE, 2, privateCover);

		FriendCollectionGridResponseDto item = firstGrid();

		assertThat(item.thumbnailUrl()).isEqualTo("https://signed/thumbs/m186-fallback.jpg");
	}

	@Test
	@DisplayName("cover 가 공개면 더 최신 영상보다 cover 썸네일이 우선이다 (§D6)")
	void cover가_공개면_최신_영상보다_cover_썸네일이_우선이다() {
		String gridId = grid(0, 0);
		long publicCover = seedVideo(friend.getId(), gridId, "thumbs/m186-cover.jpg", "PUBLIC", "READY");
		seedVideo(friend.getId(), gridId, "thumbs/m186-newer.jpg", "PUBLIC", "READY");
		occupy(friend.getId(), gridId, BASE, BASE, 2, publicCover);

		FriendCollectionGridResponseDto item = firstGrid();

		assertThat(item.thumbnailUrl()).isEqualTo("https://signed/thumbs/m186-cover.jpg");
	}

	@Test
	@DisplayName("공개여도 READY 이전 영상은 썸네일 대상이 아니다 (§D6)")
	void 공개여도_READY_이전_영상은_썸네일_대상이_아니다() {
		String gridId = grid(0, 0);
		// 교체·재인코딩 경계 재현 — pre-READY 행에 stale 썸네일이 남아 있어도 READY 게이트가 막는다.
		seedVideo(friend.getId(), gridId, "thumbs/m186-encoding.jpg", "PUBLIC", "ENCODING");
		occupy(friend.getId(), gridId, BASE, BASE, 1, null);

		assertThat(firstGrid().thumbnailUrl()).isNull();
	}

	@Test
	@DisplayName("videoCount 는 비공개 영상을 포함한 전체 수다 — 요약 totalVideoCount 와 같은 축")
	void videoCount는_비공개_영상을_포함한_전체_수다() {
		String gridId = grid(0, 0);
		seedVideo(friend.getId(), gridId, "thumbs/m186-public.jpg", "PUBLIC", "READY");
		seedVideo(friend.getId(), gridId, "thumbs/m186-private-1.jpg", "PRIVATE", "READY");
		seedVideo(friend.getId(), gridId, "thumbs/m186-private-2.jpg", "PRIVATE", "READY");
		occupy(friend.getId(), gridId, BASE, BASE, 3, null);

		FriendProfileResponseDto profile = friendService.getFriendProfile(me.getId(), friend.getId());

		assertThat(profile.recentGrids().get(0).videoCount()).isEqualTo(3);
		assertThat(profile.summary().totalVideoCount()).isEqualTo(3L);
	}

	private FriendCollectionGridResponseDto firstGrid() {
		return friendService.getFriendProfile(me.getId(), friend.getId()).recentGrids().get(0);
	}

	private User seedUser(String nickname) {
		return userRepository.save(
			User.createLocalUser("m186-" + UUID.randomUUID() + "@example.com", "hash", nickname));
	}

	/** requester 가 요청하고 addressee 가 수락해 ACCEPTED 관계를 만든다. */
	private void becomeFriends(User requester, User addressee) {
		friendService.request(requester.getId(), addressee.getFriendCode());
		friendService.accept(addressee.getId(), requester.getId());
	}

	private String grid(long dy, long dx) {
		return GridFixtures.seedGrid(em, baseY + dy, baseX + dx);
	}

	/** (userId, gridId) 수집 row 삽입. cover 는 nullable 이라 CAST 로 null 타입을 지정한다. */
	private void occupy(long userId, String gridId, LocalDateTime firstCollectedAt,
		LocalDateTime lastUploadedAt, int videoCount, Long coverVideoId) {
		em.createNativeQuery("""
			INSERT INTO user_grids (user_id, grid_id, first_collected_at, last_uploaded_at, video_count, cover_video_id)
			VALUES (:userId, :gridId, :firstCollectedAt, :lastUploadedAt, :videoCount, CAST(:coverVideoId AS bigint))
			""")
			.setParameter("userId", userId)
			.setParameter("gridId", gridId)
			.setParameter("firstCollectedAt", firstCollectedAt)
			.setParameter("lastUploadedAt", lastUploadedAt)
			.setParameter("videoCount", videoCount)
			.setParameter("coverVideoId", coverVideoId)
			.executeUpdate();
	}

	/**
	 * 영상 하나를 삽입하고 id 를 돌려준다. visibility 는 컬럼 기본값이 PRIVATE 이라 항상 명시한다.
	 * geom NOT NULL 이라 서해 공해상 좌표를 넣고, 유니크한 original_s3_key 로 id 를 되읽는다.
	 */
	private long seedVideo(long userId, String gridId, String thumbnailKey, String visibility,
		String processingStatus) {
		String originalKey = "videos/original/m186-" + System.nanoTime() + ".mp4";
		em.createNativeQuery("""
			INSERT INTO videos (
				user_id, grid_id, original_s3_key, thumbnail_url, geom, duration_sec, recorded_at,
				processing_status, visibility, status
			)
			VALUES (
				:userId, :gridId, :originalKey, CAST(:thumbKey AS text),
				ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography,
				10, now(), :processingStatus, :visibility, 'ACTIVE'
			)
			""")
			.setParameter("userId", userId)
			.setParameter("gridId", gridId)
			.setParameter("originalKey", originalKey)
			.setParameter("thumbKey", thumbnailKey)
			.setParameter("processingStatus", processingStatus)
			.setParameter("visibility", visibility)
			.setParameter("lon", SEA_LON)
			.setParameter("lat", SEA_LAT)
			.executeUpdate();
		return ((Number) em.createNativeQuery("SELECT id FROM videos WHERE original_s3_key = :originalKey")
			.setParameter("originalKey", originalKey)
			.getSingleResult()).longValue();
	}
}
