package com.msg.fillmap.mission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.grid.GridFixtures;
import com.msg.fillmap.grid.dto.RegionUnit;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.mission.config.MissionViewportProperties;
import com.msg.fillmap.mission.dto.MissionRegionAggregateResponseDto;
import com.msg.fillmap.mission.entity.MissionType;
import com.msg.fillmap.mission.repository.MissionGridRepository;
import com.msg.fillmap.mission.repository.MissionRepository;
import com.msg.fillmap.mission.service.impl.MissionQueryServiceImpl;
import com.msg.fillmap.region.RegionTestFixtures;
import com.msg.fillmap.region.repository.RegionRepository;
import com.msg.fillmap.region.service.RegionQueryService;
import com.msg.fillmap.video.repository.VideoRepository;

/**
 * 미션 행정 단위 집계 통합 (MSG-437 D2·D3, 실 PostGIS · Owner B). 귀속 판정을 실제 역지오코딩(ST_Covers)으로
 * 돌린 뒤 접두 그룹핑·이름 토큰·평균 좌표·정렬을 확인한다. 도감 집계(GridAggregationIntegrationTest)와 같은
 * 방식으로 격리한다 — 실 행정동이 덮지 않는 서해 공해상 블록에 9997 대역 합성 행정동을 심고 @Transactional
 * 롤백으로 지운다. 역지오코딩 호출 횟수·실패 분류는 MissionRegionAnchorTest 담당.
 */
// 검증: FR-MISSION-20
@SpringBootTest
@Transactional
@DisplayName("미션 행정 단위 집계 통합 (실 PostGIS)")
class MissionAggregationIntegrationTest {

	/** 서해 공해상 기준점 — 실 행정동 경계 밖이라 이 테스트가 심는 합성 행정동만 판정에 잡힌다. */
	private static final double 공해상_LAT = 36.4;
	private static final double 공해상_LON = 124.4;

	/** 합성 행정동 4개. 앞 5자리(시군구)는 A·B·D 가 같고 C 만 다르며, 앞 2자리(시도)는 넷 다 같다. */
	private static final String DONG_A = "9997110001";
	private static final String DONG_B = "9997110002";
	private static final String DONG_C = "9997220003";
	private static final String DONG_D = "9997110004";
	private static final String NAME_A = "합성광역시 합성일구 합성일동";
	private static final String NAME_B = "합성광역시 합성일구 합성이동";
	private static final String NAME_C = "합성광역시 합성이구 합성삼동";
	private static final String NAME_D = "합성광역시 합성일구 합성사동";

	@Autowired
	private MissionRepository missionRepository;

	@Autowired
	private MissionGridRepository missionGridRepository;

	@Autowired
	private VideoRepository videoRepository;

	@Autowired
	private RegionQueryService regionQueryService;

	@Autowired
	private RegionRepository regionRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private EntityManager em;

	private MissionQueryService service;

	private long baseY;
	private long baseX;

	private long 축제_A1;
	private long 축제_A2;
	private long 축제_B;
	private long 축제_C;
	private long 축제_무귀속;
	private long 팝업_A;

	@BeforeEach
	void setUp() {
		service = null;
		GridIndex base = GridEncoder.decode(GridEncoder.encode(공해상_LAT, 공해상_LON));
		baseY = base.gridY();
		baseX = base.gridX();

		// 행정동 블록은 3행(300m)이고, A 와 D 만 맞붙여 심는다(걸침 검증용). 나머지는 10행씩 띄운다.
		seedRegion(DONG_A, NAME_A, 0);
		seedRegion(DONG_D, NAME_D, 3);
		seedRegion(DONG_B, NAME_B, 10);
		seedRegion(DONG_C, NAME_C, 20);

		축제_A1 = seedMission(MissionType.EVENT, 0, 0);
		축제_A2 = seedMission(MissionType.EVENT, 1, 1);
		축제_B = seedMission(MissionType.EVENT, 10, 0);
		축제_C = seedMission(MissionType.EVENT, 20, 0);
		축제_무귀속 = seedMission(MissionType.EVENT, 30, 0);
		팝업_A = seedMission(MissionType.POPUP, 0, 2);
		em.flush();
	}

	/** (baseY + dy) 부터 3행 × 3열 블록을 덮는 합성 행정동. */
	private void seedRegion(String regionCode, String regionName, long dy) {
		regionRepository.upsert(regionCode, regionName, regionCode.substring(0, 5),
			RegionTestFixtures.cellBlockPolygonJson(baseY + dy, baseY + dy + 3, baseX, baseX + 3),
			RegionTestFixtures.CELL_AREA_M2);
	}

	/** 격자 한 칸짜리 무기간(항상 활성) 미션 — 귀속점은 그 칸의 중심이다. */
	private long seedMission(MissionType type, long dy, long dx) {
		return seedMission(type, dy, dx, dy, dx);
	}

	/** [dy1..dy2] × [dx1..dx2] 블록을 판정 격자로 갖는 무기간 미션 — 귀속점은 그 사각형 중앙 셀의 중심이다. */
	private long seedMission(MissionType type, long dy1, long dx1, long dy2, long dx2) {
		String title = "MSG437-agg-" + System.nanoTime();
		em.createNativeQuery("""
			INSERT INTO missions (type, title, start_at, end_at, target_count)
			VALUES (:type, :title, NULL, NULL, 1)
			""")
			.setParameter("type", type.name())
			.setParameter("title", title)
			.executeUpdate();
		long missionId = ((Number) em.createNativeQuery("SELECT id FROM missions WHERE title = :title")
			.setParameter("title", title)
			.getSingleResult()).longValue();
		for (long dy = dy1; dy <= dy2; dy++) {
			for (long dx = dx1; dx <= dx2; dx++) {
				em.createNativeQuery("""
					INSERT INTO mission_grids (mission_id, grid_id, seq) VALUES (:missionId, :gridId, NULL)
					""")
					.setParameter("missionId", missionId)
					.setParameter("gridId", GridFixtures.gridId(baseY + dy, baseX + dx))
					.executeUpdate();
			}
		}
		return missionId;
	}

	/** 격자가 하나도 없는 미션 — 위치를 알 수 없어 어느 집계에도 실리지 않는다. */
	private long seedGridlessMission() {
		String title = "MSG437-nogrid-" + System.nanoTime();
		em.createNativeQuery("""
			INSERT INTO missions (type, title, start_at, end_at, target_count)
			VALUES ('EVENT', :title, NULL, NULL, 1)
			""")
			.setParameter("title", title)
			.executeUpdate();
		return ((Number) em.createNativeQuery("SELECT id FROM missions WHERE title = :title")
			.setParameter("title", title)
			.getSingleResult()).longValue();
	}

	/**
	 * 캐시를 비운 서비스 — 테스트마다 새로 만들어 방금 심은 미션까지 스냅숏에 넣는다. 한 테스트 안에서는
	 * 같은 인스턴스를 재사용한다(스냅숏 재계산이 활성 미션 전량의 귀속 판정을 부르므로 호출마다 만들지 않는다).
	 */
	private MissionQueryService service() {
		if (service == null) {
			service = new MissionQueryServiceImpl(missionRepository, missionGridRepository, videoRepository,
				objectMapper, new MissionViewportProperties(Map.of()), regionQueryService, Clock.systemUTC(),
				Duration.ofHours(1).toMillis());
		}
		return service;
	}

	private List<MissionRegionAggregateResponseDto> aggregate(RegionUnit unit) {
		return service().getMissionAggregates(blockBounds(), MissionType.EVENT, unit);
	}

	/** 시드 블록 전체를 감싸는 뷰포트. 격자 축이 위경도 축과 안 맞아 꼭짓점 4점의 min/max 로 잡는다. */
	private ViewportBounds blockBounds() {
		return boundsOf(baseY - 1, baseX - 1, baseY + 32, baseX + 4);
	}

	private ViewportBounds boundsOf(long minGy, long minGx, long maxGy, long maxGx) {
		GridPoint southWest = GridFixtures.pointAt(minGy, minGx);
		GridPoint southEast = GridFixtures.pointAt(minGy, maxGx);
		GridPoint northEast = GridFixtures.pointAt(maxGy, maxGx);
		GridPoint northWest = GridFixtures.pointAt(maxGy, minGx);
		return new ViewportBounds(
			Math.min(southWest.lat(), southEast.lat()), Math.min(southWest.lon(), northWest.lon()),
			Math.max(northEast.lat(), northWest.lat()), Math.max(northEast.lon(), southEast.lon()));
	}

	private static MissionRegionAggregateResponseDto itemOf(
		List<MissionRegionAggregateResponseDto> items, String regionCode) {
		return items.stream()
			.filter(item -> regionCode.equals(item.regionCode()))
			.findFirst()
			.orElseThrow();
	}

	private GridPoint centerOf(long dy, long dx) {
		return GridEncoder.center(GridFixtures.gridId(baseY + dy, baseX + dx));
	}

	@Nested
	@DisplayName("단위별 묶음")
	class 단위별_묶음 {

		@Test
		@DisplayName("동 단위로 묶으면 행정동 코드 접두가 같은 미션이 한 항목으로 센다")
		void 동_단위로_묶으면_행정동_코드_접두가_같은_미션이_한_항목으로_센다() {
			List<MissionRegionAggregateResponseDto> items = aggregate(RegionUnit.DONG);

			assertThat(itemOf(items, DONG_A).count()).isEqualTo(2);
			assertThat(itemOf(items, DONG_A).missionIds()).containsExactlyInAnyOrder(축제_A1, 축제_A2);
			assertThat(itemOf(items, DONG_B).missionIds()).containsExactly(축제_B);
			assertThat(itemOf(items, DONG_C).missionIds()).containsExactly(축제_C);
		}

		@Test
		@DisplayName("구 단위 항목들을 시 접두로 다시 합치면 시 단위 집계와 개수가 같다")
		void 구_단위_항목들을_시_접두로_다시_합치면_시_단위_집계와_개수가_같다() {
			List<MissionRegionAggregateResponseDto> sigungu = aggregate(RegionUnit.SIGUNGU);
			List<MissionRegionAggregateResponseDto> sido = aggregate(RegionUnit.SIDO);

			// A·B 는 같은 시군구(99971)로 합쳐지고 C(99972)는 따로 남는다.
			assertThat(itemOf(sigungu, "99971").count()).isEqualTo(3);
			assertThat(itemOf(sigungu, "99972").count()).isEqualTo(1);
			int 시도로_다시_합친_수 = sigungu.stream()
				.filter(item -> item.regionCode() != null && item.regionCode().startsWith("99"))
				.mapToInt(MissionRegionAggregateResponseDto::count)
				.sum();
			assertThat(itemOf(sido, "99").count()).isEqualTo(시도로_다시_합친_수).isEqualTo(4);
		}

		@Test
		@DisplayName("묶음의 이름은 단위별 토큰이다")
		void 묶음의_이름은_단위별_토큰이다() {
			// 전체 경로 이름의 동 3·구 2·시 1 번째 공백 토큰 — 도감 집계 split_part 와 같은 규칙(D2).
			assertThat(itemOf(aggregate(RegionUnit.DONG), DONG_A).name()).isEqualTo("합성일동");
			assertThat(itemOf(aggregate(RegionUnit.SIGUNGU), "99971").name()).isEqualTo("합성일구");
			assertThat(itemOf(aggregate(RegionUnit.SIDO), "99").name()).isEqualTo("합성광역시");
		}

		@Test
		@DisplayName("대표 좌표는 묶음에 속한 미션 귀속점의 평균이다")
		void 대표_좌표는_묶음에_속한_미션_귀속점의_평균이다() {
			MissionRegionAggregateResponseDto item = itemOf(aggregate(RegionUnit.DONG), DONG_A);

			GridPoint first = centerOf(0, 0);
			GridPoint second = centerOf(1, 1);
			assertThat(item.lat()).isCloseTo((first.lat() + second.lat()) / 2, within(1e-9));
			assertThat(item.lng()).isCloseTo((first.lon() + second.lon()) / 2, within(1e-9));
		}

		@Test
		@DisplayName("missionIds는 오름차순이고 크기가 count와 같다")
		void missionIds는_오름차순이고_크기가_count와_같다() {
			for (MissionRegionAggregateResponseDto item : aggregate(RegionUnit.SIDO)) {
				assertThat(item.missionIds()).isSorted().hasSize(item.count());
			}
		}

		@Test
		@DisplayName("항목은 regionCode 오름차순이고 무귀속 항목이 마지막이다")
		void 항목은_regionCode_오름차순이고_무귀속_항목이_마지막이다() {
			assertThat(aggregate(RegionUnit.DONG))
				.extracting(MissionRegionAggregateResponseDto::regionCode)
				.containsExactly(DONG_A, DONG_B, DONG_C, null);
		}

		@Test
		@DisplayName("행정동 판정이 없는 미션은 regionCode가 null인 항목 하나로 묶인다")
		void 행정동_판정이_없는_미션은_regionCode가_null인_항목_하나로_묶인다() {
			long 두번째_무귀속 = seedMission(MissionType.EVENT, 31, 0);
			em.flush();

			List<MissionRegionAggregateResponseDto> items = aggregate(RegionUnit.DONG);

			MissionRegionAggregateResponseDto 무귀속 = items.get(items.size() - 1);
			assertThat(무귀속.regionCode()).isNull();
			assertThat(무귀속.name()).isNull();
			assertThat(무귀속.missionIds()).containsExactlyInAnyOrder(축제_무귀속, 두번째_무귀속);
		}
	}

	@Nested
	@DisplayName("귀속점 기준 포함 판정 (D3)")
	class 귀속점_기준_포함_판정 {

		@Test
		@DisplayName("축제 사각형이 두 동에 걸쳐도 중심이 속한 동 하나로만 센다")
		void 축제_사각형이_두_동에_걸쳐도_중심이_속한_동_하나로만_센다() {
			// 행 1~3 = A(행 0~2)와 D(행 3~5)에 걸치는 사각형. 중앙 셀은 행 2 라 A 소속이다.
			long 걸친_축제 = seedMission(MissionType.EVENT, 1, 0, 3, 0);
			em.flush();

			List<MissionRegionAggregateResponseDto> items = aggregate(RegionUnit.DONG);

			assertThat(itemOf(items, DONG_A).missionIds()).contains(걸친_축제);
			assertThat(items).extracting(MissionRegionAggregateResponseDto::regionCode).doesNotContain(DONG_D);
			// 이중 계상하면 묶음 개수의 합이 미션 수보다 커진다.
			assertThat(items.stream().mapToInt(MissionRegionAggregateResponseDto::count).sum()).isEqualTo(6);
		}

		@Test
		@DisplayName("귀속점이 bbox 밖인 미션은 사각형이 걸쳐도 집계에서 빠진다")
		void 귀속점이_bbox_밖인_미션은_사각형이_걸쳐도_집계에서_빠진다() {
			// 행 0~8 사각형(중앙 셀 행 4)은 아래 뷰포트(행 0~2)에 걸치지만 귀속점은 밖이다.
			long 걸친_축제 = seedMission(MissionType.EVENT, 0, 0, 8, 0);
			em.flush();

			List<MissionRegionAggregateResponseDto> items = service()
				.getMissionAggregates(boundsOf(baseY, baseX, baseY + 2, baseX + 2), MissionType.EVENT,
					RegionUnit.DONG);

			assertThat(items).flatExtracting(MissionRegionAggregateResponseDto::missionIds)
				.doesNotContain(걸친_축제)
				.contains(축제_A1);
		}

		@Test
		@DisplayName("귀속점이 bbox 경계선 위면 집계에 포함된다")
		void 귀속점이_bbox_경계선_위면_집계에_포함된다() {
			GridPoint anchor = centerOf(0, 0);
			ViewportBounds 남서모서리가_귀속점인_뷰포트 =
				new ViewportBounds(anchor.lat(), anchor.lon(), anchor.lat() + 0.05, anchor.lon() + 0.05);

			List<MissionRegionAggregateResponseDto> items = service()
				.getMissionAggregates(남서모서리가_귀속점인_뷰포트, MissionType.EVENT, RegionUnit.DONG);

			assertThat(items).flatExtracting(MissionRegionAggregateResponseDto::missionIds).contains(축제_A1);
		}

		@Test
		@DisplayName("격자가 없는 미션은 집계에 실리지 않는다")
		void 격자가_없는_미션은_집계에_실리지_않는다() {
			long 격자없는_미션 = seedGridlessMission();
			em.flush();

			// 위치를 모르는 미션이 무귀속 묶음에 섞이면 화면 어디에도 못 그릴 마커가 생긴다.
			assertThat(aggregate(RegionUnit.SIDO)).flatExtracting(MissionRegionAggregateResponseDto::missionIds)
				.doesNotContain(격자없는_미션);
		}

		@Test
		@DisplayName("type이 다른 미션은 집계에 섞이지 않는다")
		void type이_다른_미션은_집계에_섞이지_않는다() {
			List<MissionRegionAggregateResponseDto> 축제 = aggregate(RegionUnit.DONG);
			List<MissionRegionAggregateResponseDto> 팝업 =
				service().getMissionAggregates(blockBounds(), MissionType.POPUP, RegionUnit.DONG);

			assertThat(축제).flatExtracting(MissionRegionAggregateResponseDto::missionIds).doesNotContain(팝업_A);
			assertThat(itemOf(팝업, DONG_A).missionIds()).containsExactly(팝업_A);
			assertThat(itemOf(팝업, DONG_A).count()).isEqualTo(1);
		}
	}
}
