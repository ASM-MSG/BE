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

import com.msg.fillmap.friend.exception.FriendErrorCode;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.grid.dto.RegionUnit;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.grid.exception.GridErrorCode;
import com.msg.fillmap.grid.service.OccupiedGridPage;
import com.msg.fillmap.grid.service.OccupiedGridView;
import com.msg.fillmap.grid.service.RegionAggregateView;
import com.msg.fillmap.region.RegionTestFixtures;
import com.msg.fillmap.region.repository.RegionRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.zone.entity.Zone;

/**
 * 친구 격자 뷰포트 조회 (MSG-187 D2·D3, 실 DB). @Transactional 롤백 격리로 공유 로컬 DB 에 시드를 남기지
 * 않고, 합성 격자는 서해 공해상 기준점(실 행정동 무귀속)이라 실데이터 판정에 끼어들지 않는다 — 행정동 이름
 * 검증(MSG-349)만 999 대역 합성 행정동을 그 자리에 심어 쓴다
 * (FriendProfileIntegrationTest 패턴). 검증 축 = 친구 도감만 보인다(내 격자 불포함)·커서 페이지네이션·
 * 관계 은닉(실패 전건 9424)·검증 규칙이 내 조회와 같다(4401/4402/4403/4404)·판정 순서(9424 우선).
 * 같은 판정 하나를 거쳐 위임하는 축소 뷰 집계 조회(MSG-356)도 여기서 함께 고정한다.
 */
@SpringBootTest
@Transactional
@DisplayName("친구 격자 뷰포트·집계 조회 (실 DB)")
class FriendGridViewportTest {

	// 서해 공해상 — 어떤 행정동에도 안 속하는 기준점.
	private static final double SEA_LAT = 36.2;
	private static final double SEA_LON = 124.2;

	// 행정동 이름 위임 검증용 합성 행정동 (MSG-349). 실존하지 않는 sido 999 대역이라 실데이터와 충돌하지 않고,
	// 트랜잭션 롤백으로 공유 로컬 DB 에 남지 않는다.
	private static final String REGION_CODE = "9996000003";
	private static final String REGION_NAME = "합성시 합성구 합성349동";

	private static final long UNKNOWN_USER_ID = 999_999_999L;

	private static final int DEFAULT_SIZE = 1000;

	@Autowired
	private FriendService friendService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RegionRepository regionRepository;

	@Autowired
	private EntityManager em;

	/** 실 S3 호출 차단용 — 컨텍스트의 다른 빈이 주입받는 의존성, 이 테스트에선 미사용. */
	@MockitoBean
	private S3Client s3Client;

	private User me;
	private User friend;
	private long baseY;
	private long baseX;

	@BeforeEach
	void setUp() {
		me = seedUser("나채움");
		friend = seedUser("채우미");
		becomeFriends(me, friend);
		GridIndex base = GridEncoder.decode(GridEncoder.encode(SEA_LAT, SEA_LON));
		baseY = base.gridY();
		baseX = base.gridX();
	}

	@Test
	@DisplayName("친구가 점령한 격자만 뷰포트로 조회된다 — 내 격자는 섞이지 않는다 (FR-1)")
	void 친구의_점령_격자를_뷰포트로_조회한다() {
		String first = occupy(friend, 0, 0);
		String second = occupy(friend, 1, 0);
		occupy(me, 2, 0);

		OccupiedGridPage page = friendGrids(bounds(0, 0, 3, 3), null, DEFAULT_SIZE);

		assertThat(page.items()).extracting(OccupiedGridView::gridId).containsExactly(first, second);
		assertThat(page.items().get(0).gridY()).isEqualTo((int) baseY);
		assertThat(page.items().get(0).gridX()).isEqualTo((int) baseX);
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	@DisplayName("친구 격자 뷰포트 항목에도 같은 이름이 실린다")
	void 친구_격자_뷰포트_항목에도_같은_이름이_실린다() {
		// MSG-341: 친구 뷰포트는 내 뷰포트와 같은 GridQueryService·같은 OccupiedGridView 를 통과시킬 뿐이라
		// friend 쪽에 이름 코드가 0줄이다. 그 통과가 실제로 이름을 실어 오는지를 실 DB 로 고정한다.
		// 구역은 이 트랜잭션 안에서만 살고 롤백되므로 공유 로컬 DB 에 남지 않는다.
		seedZone(baseY, baseY + 5, baseX, baseX + 5);
		occupy(friend, 0, 0);
		occupy(friend, 1, 0);

		OccupiedGridPage page = friendGrids(bounds(0, 0, 3, 3), null, DEFAULT_SIZE);

		// 행 = 'A' + (maxGridY − gridY): baseY 는 'F'(5칸 남쪽), baseY+1 은 'E'. 열 = gridX − minGridX + 1 = 1.
		assertThat(page.items()).extracting(OccupiedGridView::zoneName).containsOnly("서면");
		assertThat(page.items()).extracting(OccupiedGridView::zoneCell).containsExactly("F-1", "E-1");
	}

	@Test
	@DisplayName("친구 격자 조회에도 행정동 이름이 실린다 (MSG-349 FR-1)")
	void 친구_격자_조회에도_행정동_이름이_실린다() {
		// friend 쪽 파일은 한 줄도 안 바뀐다 — GridQueryService 위임 경로가 이름을 그대로 실어 오는지 고정한다.
		seedRegion(baseY, baseY + 3, baseX, baseX + 3);
		String labeled = occupyLabeled(friend, 0, 0);
		String unlabeled = occupy(friend, 1, 0);

		OccupiedGridPage page = friendGrids(bounds(0, 0, 3, 3), null, DEFAULT_SIZE);

		// 라벨 없는 격자도 항목에서 빠지지 않고 이름만 빈다 (LEFT JOIN).
		assertThat(page.items()).extracting(OccupiedGridView::gridId).containsExactly(labeled, unlabeled);
		assertThat(page.items()).extracting(OccupiedGridView::regionName).containsExactly(REGION_NAME, null);
	}

	@Test
	@DisplayName("구역 밖 친구 격자는 두 필드가 모두 null 이다")
	void 구역_밖_친구_격자는_두_필드가_모두_null이다() {
		// 구역을 아예 안 심으면(서해 공해상은 시드 구역과 무관) NONE 이 그대로 내려온다 — 이때 표시 이름은
		// 같은 항목의 regionName(행정동)이다 (MSG-349, 위 테스트가 그 축을 본다).
		occupy(friend, 0, 0);

		OccupiedGridPage page = friendGrids(bounds(0, 0, 3, 3), null, DEFAULT_SIZE);

		assertThat(page.items().get(0).zoneName()).isNull();
		assertThat(page.items().get(0).zoneCell()).isNull();
	}

	@Test
	@DisplayName("커서로 다음 페이지가 이어지고 마지막 페이지의 nextCursor 는 null 이다")
	void 커서로_다음_페이지가_이어지고_마지막_페이지의_nextCursor는_null이다() {
		String first = occupy(friend, 0, 0);
		String second = occupy(friend, 1, 0);
		String third = occupy(friend, 2, 0);

		OccupiedGridPage page1 = friendGrids(bounds(0, 0, 3, 3), null, 2);

		assertThat(page1.items()).extracting(OccupiedGridView::gridId).containsExactly(first, second);
		assertThat(page1.nextCursor()).isNotNull();

		OccupiedGridPage page2 = friendGrids(bounds(0, 0, 3, 3), page1.nextCursor(), 2);

		assertThat(page2.items()).extracting(OccupiedGridView::gridId).containsExactly(third);
		assertThat(page2.nextCursor()).isNull();
	}

	@Test
	@DisplayName("친구가 점령한 격자가 없는 뷰포트는 빈 목록이다 — 에러 아님 (FR-8)")
	void 친구가_점령한_격자가_없는_뷰포트는_빈_목록이다() {
		occupy(friend, 0, 0);

		OccupiedGridPage page = friendGrids(bounds(10, 10, 13, 13), null, DEFAULT_SIZE);

		assertThat(page.items()).isEmpty();
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	@DisplayName("비친구 조회는 9424 다 (FR-2)")
	void 비친구_조회는_9424다() {
		User stranger = seedUser("남");

		assertThatThrownBy(() -> friendService.getFriendGrids(
			stranger.getId(), friend.getId(), bounds(0, 0, 3, 3), null, DEFAULT_SIZE))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", FriendErrorCode.FRIENDSHIP_NOT_FOUND);
	}

	@Test
	@DisplayName("본인 ID 조회는 9424 다 — 내 도감은 격자 API 몫 (FR-2)")
	void 본인_ID_조회는_9424다() {
		assertThatThrownBy(() -> friendService.getFriendGrids(
			me.getId(), me.getId(), bounds(0, 0, 3, 3), null, DEFAULT_SIZE))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", FriendErrorCode.FRIENDSHIP_NOT_FOUND);
	}

	@Test
	@DisplayName("미존재 userId 조회는 비친구와 구분 불가한 9424 다 — 계정 존재 은닉 (FR-2)")
	void 미존재_userId_조회는_9424다() {
		assertThatThrownBy(() -> friendService.getFriendGrids(
			me.getId(), UNKNOWN_USER_ID, bounds(0, 0, 3, 3), null, DEFAULT_SIZE))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", FriendErrorCode.FRIENDSHIP_NOT_FOUND);
	}

	@Test
	@DisplayName("대기 중 요청 상대 조회는 9424 다 (FR-2)")
	void PENDING_상대_조회는_9424다() {
		User pending = seedUser("대기");
		friendService.request(pending.getId(), me.getFriendCode());

		assertThatThrownBy(() -> friendService.getFriendGrids(
			me.getId(), pending.getId(), bounds(0, 0, 3, 3), null, DEFAULT_SIZE))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", FriendErrorCode.FRIENDSHIP_NOT_FOUND);
	}

	@Test
	@DisplayName("친구 삭제 직후 조회는 9424 다 — 실시간 판정 (FR-7)")
	void 친구_삭제_직후_조회는_9424다() {
		occupy(friend, 0, 0);
		assertThat(friendGrids(bounds(0, 0, 3, 3), null, DEFAULT_SIZE).items()).hasSize(1);

		friendService.deleteFriend(me.getId(), friend.getId());

		assertThatThrownBy(() -> friendGrids(bounds(0, 0, 3, 3), null, DEFAULT_SIZE))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", FriendErrorCode.FRIENDSHIP_NOT_FOUND);
	}

	@Test
	@DisplayName("뒤집힌 bbox 는 4401 이다 — 내 조회와 같은 규칙 (FR-10)")
	void 뒤집힌_bbox는_4401이다() {
		ViewportBounds inverted = new ViewportBounds(SEA_LAT + 0.1, SEA_LON + 0.1, SEA_LAT, SEA_LON);

		assertThatThrownBy(() -> friendGrids(inverted, null, DEFAULT_SIZE))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", GridErrorCode.INVALID_VIEWPORT);
	}

	@Test
	@DisplayName("상한(0.5도) 초과 뷰포트는 4402 다 (FR-10)")
	void 상한_초과_뷰포트는_4402다() {
		ViewportBounds tooLarge = new ViewportBounds(SEA_LAT, SEA_LON, SEA_LAT + 0.6, SEA_LON + 0.1);

		assertThatThrownBy(() -> friendGrids(tooLarge, null, DEFAULT_SIZE))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", GridErrorCode.VIEWPORT_TOO_LARGE);
	}

	@Test
	@DisplayName("무효 커서는 4403 이다 (FR-10)")
	void 무효_커서는_4403이다() {
		assertThatThrownBy(() -> friendGrids(bounds(0, 0, 3, 3), "!!!not-a-cursor!!!", DEFAULT_SIZE))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", GridErrorCode.INVALID_CURSOR);
	}

	@Test
	@DisplayName("범위(1~5000) 밖 size 는 4404 다 (FR-10)")
	void 범위_밖_size는_4404다() {
		assertThatThrownBy(() -> friendGrids(bounds(0, 0, 3, 3), null, 0))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", GridErrorCode.INVALID_PAGE_SIZE);
		assertThatThrownBy(() -> friendGrids(bounds(0, 0, 3, 3), null, 5001))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", GridErrorCode.INVALID_PAGE_SIZE);
	}

	@Test
	@DisplayName("비친구는 무효 뷰포트여도 9424 가 먼저다 — 은닉 우선 (D3 순서)")
	void 비친구는_무효_뷰포트여도_9424가_먼저다() {
		User stranger = seedUser("남");
		ViewportBounds inverted = new ViewportBounds(SEA_LAT + 0.1, SEA_LON + 0.1, SEA_LAT, SEA_LON);

		assertThatThrownBy(() -> friendService.getFriendGrids(
			stranger.getId(), friend.getId(), inverted, null, DEFAULT_SIZE))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", FriendErrorCode.FRIENDSHIP_NOT_FOUND);
	}

	@Test
	@DisplayName("친구가 점령한 격자만 행정 단위로 묶여 집계된다 — 내 격자는 섞이지 않는다 (MSG-356 FR-4)")
	void 친구_집계는_ACCEPTED_관계에서_친구_기준으로_집계된다() {
		seedRegion(baseY, baseY + 3, baseX, baseX + 3);
		occupyLabeled(friend, 0, 0);
		occupyLabeled(friend, 1, 0);
		occupyLabeled(me, 2, 0);

		List<RegionAggregateView> aggregates = friendAggregates(bounds(0, 0, 3, 3), RegionUnit.DONG);

		assertThat(aggregates).singleElement().satisfies(item -> {
			assertThat(item.regionCode()).isEqualTo(REGION_CODE);
			assertThat(item.name()).isEqualTo("합성349동");
			// 내 격자(2, 0)가 같은 행정동 안에 있어도 세지 않는다 — 집계 대상은 친구 소유 점령분뿐이다.
			assertThat(item.count()).isEqualTo(2);
		});
	}

	@Test
	@DisplayName("요청한 집계 단위가 그대로 위임된다 — friend 는 단위를 해석하지 않는다 (MSG-356)")
	void 요청한_집계_단위가_그대로_위임된다() {
		// 위임 한 줄이 단위를 떨구거나 고정값을 넣지 않는지 고정한다 — 묶음 키·이름이 단위마다 달라지는 것이 신호다.
		seedRegion(baseY, baseY + 3, baseX, baseX + 3);
		occupyLabeled(friend, 0, 0);

		List<RegionAggregateView> aggregates = friendAggregates(bounds(0, 0, 3, 3), RegionUnit.SIGUNGU);

		assertThat(aggregates).singleElement().satisfies(item -> {
			assertThat(item.regionCode()).isEqualTo(REGION_CODE.substring(0, 5));
			assertThat(item.name()).isEqualTo("합성구");
		});
	}

	@Test
	@DisplayName("친구 집계도 실패는 9424 하나다 — 비친구·본인 ID·대기 중 상대·미존재 userId (MSG-356 FR-4)")
	void 비친구의_친구_집계_요청은_9424_단일_실패다() {
		User stranger = seedUser("남");
		User pending = seedUser("대기");
		friendService.request(pending.getId(), me.getFriendCode());

		assertAggregateRejected(stranger.getId(), friend.getId());
		assertAggregateRejected(me.getId(), me.getId());
		assertAggregateRejected(me.getId(), pending.getId());
		assertAggregateRejected(me.getId(), UNKNOWN_USER_ID);
	}

	private void assertAggregateRejected(long userId, long targetUserId) {
		assertThatThrownBy(() -> friendService.getFriendGridAggregates(
			userId, targetUserId, bounds(0, 0, 3, 3), RegionUnit.DONG))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("errorCode", FriendErrorCode.FRIENDSHIP_NOT_FOUND);
	}

	private List<RegionAggregateView> friendAggregates(ViewportBounds bounds, RegionUnit unit) {
		return friendService.getFriendGridAggregates(me.getId(), friend.getId(), bounds, unit);
	}

	private OccupiedGridPage friendGrids(ViewportBounds bounds, String cursor, int size) {
		return friendService.getFriendGrids(me.getId(), friend.getId(), bounds, cursor, size);
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

	/**
	 * 격자 인덱스 사각형으로 구역 하나를 심는다 (MSG-341). flush 로 같은 트랜잭션의 zones 조회에 보이게 하고,
	 * 테스트 롤백이 지우므로 공유 로컬 DB 에 남지 않는다.
	 */
	private void seedZone(long minGridY, long maxGridY, long minGridX, long maxGridX) {
		em.persist(Zone.builder()
			.zoneKey("m341-" + UUID.randomUUID().toString().substring(0, 8))
			.name("서면")
			.minGridY((int) minGridY).maxGridY((int) maxGridY)
			.minGridX((int) minGridX).maxGridX((int) maxGridX)
			.priority(0)
			.build());
		em.flush();
	}

	/** (baseY + dy, baseX + dx) 셀을 grids 에 만들고 그 사용자의 점령 row 를 넣는다. */
	private String occupy(User user, long dy, long dx) {
		String gridId = GridFixtures.seedGrid(em, baseY + dy, baseX + dx);
		GridFixtures.seedUserGrid(em, user.getId(), gridId, 1);
		return gridId;
	}

	/** occupy 와 같되 중심점 판정으로 행정동 라벨까지 붙인다 — 프로덕션 격자(탄생 시 라벨) 재현. */
	private String occupyLabeled(User user, long dy, long dx) {
		String gridId = GridFixtures.seedLabeledGrid(em, baseY + dy, baseX + dx);
		GridFixtures.seedUserGrid(em, user.getId(), gridId, 1);
		return gridId;
	}

	/** 격자 인덱스 사각형을 덮는 합성 행정동 하나를 심는다 — 라벨이 붙으려면 격자보다 먼저 있어야 한다. */
	private void seedRegion(long minGridY, long maxGridY, long minGridX, long maxGridX) {
		regionRepository.upsert(REGION_CODE, REGION_NAME, REGION_CODE.substring(0, 5),
			RegionTestFixtures.cellBlockPolygonJson(minGridY, maxGridY, minGridX, maxGridX),
			RegionTestFixtures.CELL_AREA_M2);
	}

	/** 셀 중심 좌표로 bbox 를 만든다 — 서비스가 GridEncoder 로 되돌려 인덱스 범위 [from, to] 를 얻는다. */
	private ViewportBounds bounds(long dyFrom, long dxFrom, long dyTo, long dxTo) {
		GridPoint from = GridFixtures.pointAt(baseY + dyFrom + 0.5, baseX + dxFrom + 0.5);
		GridPoint to = GridFixtures.pointAt(baseY + dyTo + 0.5, baseX + dxTo + 0.5);
		return new ViewportBounds(from.lat(), from.lon(), to.lat(), to.lon());
	}
}
