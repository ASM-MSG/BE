package com.msg.fillmap.mission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.grid.dto.RegionUnit;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.mission.config.MissionViewportProperties;
import com.msg.fillmap.mission.dto.MissionRegionAggregateResponseDto;
import com.msg.fillmap.mission.dto.MissionResponseDto;
import com.msg.fillmap.mission.entity.Mission;
import com.msg.fillmap.mission.entity.MissionGrid;
import com.msg.fillmap.mission.entity.MissionType;
import com.msg.fillmap.mission.repository.MissionGridRepository;
import com.msg.fillmap.mission.repository.MissionRepository;
import com.msg.fillmap.mission.service.impl.MissionQueryServiceImpl;
import com.msg.fillmap.region.exception.RegionErrorCode;
import com.msg.fillmap.region.service.RegionQueryService;
import com.msg.fillmap.region.service.RegionView;
import com.msg.fillmap.video.repository.VideoRepository;

/**
 * 미션 행정 귀속 판정과 메모이즈 검증 (MissionQueryServiceImpl, 순수 단위 · MSG-437 D1 · Owner B).
 * 역지오코딩 호출 횟수와 실패 분류(무귀속 수용 대 전파)가 검증 대상이라 실 서비스로는 관찰할 수 없다 —
 * 인프라 예외 주입도 세대 간 호출 횟수 대조도 목이 있어야 성립한다. 집계 산술 자체는
 * MissionAggregationIntegrationTest 가 실 PostGIS 로 본다.
 */
// 검증: FR-MISSION-20
@ExtendWith(MockitoExtension.class)
@DisplayName("미션 행정 귀속 판정 (MSG-437 D1)")
class MissionRegionAnchorTest {

	private static final long TTL_MILLIS = Duration.ofHours(1).toMillis();

	/**
	 * 합성 미션을 전부 품는 뷰포트 (한 변 0.3도) — 이 파일은 bbox 자르기가 아니라 귀속 판정만 보므로
	 * 목록(0.5도)·DONG(1도)·SIGUNGU(4도) 상한을 한꺼번에 만족하는 하나를 쓴다.
	 */
	private static final ViewportBounds 기준_뷰포트 = new ViewportBounds(37.40, 126.90, 37.70, 127.20);

	/** 합성 미션을 얹을 기준 셀 (강남 일대). */
	private static final GridIndex 기준셀 = GridEncoder.decode(GridEncoder.encode(37.52, 127.02));

	@Mock
	private MissionRepository missionRepository;

	@Mock
	private MissionGridRepository missionGridRepository;

	@Mock
	private VideoRepository videoRepository;

	@Mock
	private RegionQueryService regionQueryService;

	/** 만료 경계를 넘기려 시각을 앞당길 수 있는 클럭 (MissionQueryServiceCacheTest 와 같은 도구). */
	private static final class MutableClock extends Clock {

		private volatile Instant now;

		private MutableClock(Instant now) {
			this.now = now;
		}

		private void set(Instant now) {
			this.now = now;
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public long millis() {
			return now.toEpochMilli();
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}
	}

	private final MutableClock clock = new MutableClock(Instant.ofEpochMilli(0));

	/** 부트 웜업은 인터페이스가 아니라 구현체의 이벤트 훅이라 구현 타입으로 돌려준다. */
	private MissionQueryServiceImpl newService() {
		return new MissionQueryServiceImpl(missionRepository, missionGridRepository, videoRepository,
			new ObjectMapper(), new MissionViewportProperties(Map.of()), regionQueryService, clock, TTL_MILLIS);
	}

	private static Mission mission(long id, MissionType type) {
		Mission mission = Mission.builder().type(type).title("귀속 검증 " + id).targetCount(1).build();
		ReflectionTestUtils.setField(mission, "id", id); // 시더 밖 합성 엔티티라 id 를 직접 부여한다.
		return mission;
	}

	private static String cell(long dy, long dx) {
		return (기준셀.gridY() + dy) + "_" + (기준셀.gridX() + dx);
	}

	private static RegionView 부전2동() {
		return new RegionView("2623058000", "부산광역시 부산진구 부전2동", "26230");
	}

	@Nested
	@DisplayName("귀속점 계산")
	class 귀속점_계산 {

		@Test
		@DisplayName("귀속점은 판정 사각형 중앙 셀의 중심이다")
		void 귀속점은_판정_사각형_중앙_셀의_중심이다() {
			given(missionRepository.findActive(any())).willReturn(List.of(mission(1L, MissionType.EVENT)));
			given(missionGridRepository.findByMissionIds(List.of(1L))).willReturn(List.of(
				new MissionGrid(1L, cell(0, 0)), new MissionGrid(1L, cell(2, 2))));
			given(regionQueryService.resolveByPoint(anyDouble(), anyDouble())).willReturn(Optional.of(부전2동()));

			newService().getMissionsInViewport(기준_뷰포트, MissionType.EVENT);

			// (minY+maxY)/2, (minX+maxX)/2 = 사각형 중앙 셀. 그 셀의 중심 위경도가 귀속점이다.
			GridPoint expected = GridEncoder.center(cell(1, 1));
			then(regionQueryService).should().resolveByPoint(expected.lat(), expected.lon());
		}

		@Test
		@DisplayName("코스와 AREA는 귀속 판정을 부르지 않는다")
		void 코스와_AREA는_귀속_판정을_부르지_않는다() {
			given(missionRepository.findActive(any()))
				.willReturn(List.of(mission(1L, MissionType.COURSE), mission(2L, MissionType.AREA)));
			given(missionGridRepository.findByMissionIds(List.of(1L, 2L)))
				.willReturn(List.of(new MissionGrid(1L, cell(0, 0)), new MissionGrid(2L, cell(1, 1))));

			newService().getMissionsInViewport(기준_뷰포트, MissionType.COURSE);

			// 집계 대상이 아닌 유형까지 판정하면 콜드 웜업 쿼리가 활성 미션 전량으로 불어난다 (D1).
			then(regionQueryService).should(never()).resolveByPoint(anyDouble(), anyDouble());
		}
	}

	@Nested
	@DisplayName("판정 실패 분류")
	class 판정_실패_분류 {

		@Test
		@DisplayName("포함 행정동이 없는 좌표는 무귀속으로 수용된다")
		void 포함_행정동이_없는_좌표는_무귀속으로_수용된다() {
			given(missionRepository.findActive(any())).willReturn(List.of(mission(1L, MissionType.EVENT)));
			given(missionGridRepository.findByMissionIds(List.of(1L)))
				.willReturn(List.of(new MissionGrid(1L, cell(0, 0))));
			given(regionQueryService.resolveByPoint(anyDouble(), anyDouble())).willReturn(Optional.empty());

			List<MissionRegionAggregateResponseDto> items =
				newService().getMissionAggregates(기준_뷰포트, MissionType.EVENT, RegionUnit.DONG);

			assertThat(items).singleElement().satisfies(item -> {
				assertThat(item.regionCode()).isNull();
				assertThat(item.name()).isNull();
				assertThat(item.missionIds()).containsExactly(1L);
			});
		}

		@Test
		@DisplayName("서비스 범위 밖 좌표는 무귀속으로 수용된다 — 그 미션만 빠지고 나머지는 판정된다")
		void 서비스_범위_밖_좌표는_무귀속으로_수용된다() {
			given(missionRepository.findActive(any()))
				.willReturn(List.of(mission(1L, MissionType.EVENT), mission(2L, MissionType.EVENT)));
			given(missionGridRepository.findByMissionIds(List.of(1L, 2L)))
				.willReturn(List.of(new MissionGrid(1L, cell(0, 0)), new MissionGrid(2L, cell(5, 5))));
			given(regionQueryService.resolveByPoint(anyDouble(), anyDouble())).willReturn(Optional.of(부전2동()));
			GridPoint 범위밖 = GridEncoder.center(cell(0, 0));
			given(regionQueryService.resolveByPoint(범위밖.lat(), 범위밖.lon()))
				.willThrow(new ApiException(RegionErrorCode.INVALID_COORDINATE));

			List<MissionRegionAggregateResponseDto> items =
				newService().getMissionAggregates(기준_뷰포트, MissionType.EVENT, RegionUnit.DONG);

			// 6400 은 좌표가 서비스 범위 밖이라는 데이터 성질의 실패 — 그 미션만 무귀속으로 싣는다.
			assertThat(items).extracting(MissionRegionAggregateResponseDto::regionCode)
				.containsExactly("2623058000", null);
			assertThat(items.get(0).missionIds()).containsExactly(2L);
			assertThat(items.get(1).missionIds()).containsExactly(1L);
		}

		@Test
		@DisplayName("역지오코딩 인프라 예외는 재계산을 실패시키고 기존 스냅숏이 유지된다")
		void 역지오코딩_인프라_예외는_재계산을_실패시키고_기존_스냅숏이_유지된다() {
			given(missionRepository.findActive(any()))
				.willReturn(List.of(mission(1L, MissionType.EVENT)))
				.willReturn(List.of(mission(1L, MissionType.EVENT), mission(2L, MissionType.EVENT)));
			given(missionGridRepository.findByMissionIds(List.of(1L)))
				.willReturn(List.of(new MissionGrid(1L, cell(0, 0))));
			given(missionGridRepository.findByMissionIds(List.of(1L, 2L)))
				.willReturn(List.of(new MissionGrid(1L, cell(0, 0)), new MissionGrid(2L, cell(5, 5))));
			given(regionQueryService.resolveByPoint(anyDouble(), anyDouble())).willReturn(Optional.of(부전2동()));
			GridPoint 신규미션_귀속점 = GridEncoder.center(cell(5, 5));
			given(regionQueryService.resolveByPoint(신규미션_귀속점.lat(), 신규미션_귀속점.lon()))
				.willThrow(new IllegalStateException("역지오코딩 DB 연결 실패"));
			MissionQueryService service = newService();
			service.getMissionsInViewport(기준_뷰포트, MissionType.EVENT);
			Object 첫_세대 = ReflectionTestUtils.getField(service, "cache");

			clock.set(Instant.ofEpochMilli(TTL_MILLIS + 1));

			// 인프라 예외를 흡수하면 전 미션이 무귀속으로 뭉개진 스냅숏이 1시간 캐시된다 (D1).
			assertThatThrownBy(() -> service.getMissionsInViewport(기준_뷰포트, MissionType.EVENT))
				.isInstanceOf(IllegalStateException.class);
			assertThat(ReflectionTestUtils.getField(service, "cache"))
				.as("실패한 세대가 발행되면 안 된다 — 캐시 엔트리가 첫 세대 그대로여야 한다")
				.isSameAs(첫_세대);
		}

		@Test
		@DisplayName("귀속 인프라 예외로 재계산이 실패하면 목록 조회도 같이 실패한다")
		void 귀속_인프라_예외로_재계산이_실패하면_목록_조회도_같이_실패한다() {
			// 스냅숏이 목록·집계 공용이라, 축제 한 건의 귀속 판정이 인프라 예외로 죽으면 그 세대가 통째로
			// 발행되지 않고 귀속을 읽지도 않는 코스 목록 조회까지 함께 실패한다. 의도된 의미론이다 —
			// 캐시를 갈라 두면 목록과 집계가 서로 다른 세대를 보고, findActive 실패가 목록을 실패시켜 온
			// 기존 동작과도 같은 결이다 (Codex P2-2, 리더 확정).
			given(missionRepository.findActive(any()))
				.willReturn(List.of(mission(1L, MissionType.COURSE), mission(2L, MissionType.EVENT)));
			given(missionGridRepository.findByMissionIds(List.of(1L, 2L)))
				.willReturn(List.of(new MissionGrid(1L, cell(0, 0)), new MissionGrid(2L, cell(3, 3))))
				.willReturn(List.of(new MissionGrid(1L, cell(0, 0)), new MissionGrid(2L, cell(9, 9))));
			given(regionQueryService.resolveByPoint(anyDouble(), anyDouble()))
				.willReturn(Optional.of(부전2동()))
				.willThrow(new IllegalStateException("역지오코딩 DB 연결 실패"));
			MissionQueryService service = newService();
			assertThat(service.getMissionsInViewport(기준_뷰포트, MissionType.COURSE)).hasSize(1);

			// 사각형이 바뀌어 메모이즈가 안 듣는 축제를 새 세대가 다시 판정하다 인프라 예외를 만난다.
			clock.set(Instant.ofEpochMilli(TTL_MILLIS + 1));

			assertThatThrownBy(() -> service.getMissionsInViewport(기준_뷰포트, MissionType.COURSE))
				.isInstanceOf(IllegalStateException.class);
		}
	}

	@Nested
	@DisplayName("판정 메모이즈")
	class 판정_메모이즈 {

		@Test
		@DisplayName("판정 사각형이 같은 미션은 다음 재계산에서 역지오코딩을 다시 부르지 않는다")
		void 판정_사각형이_같은_미션은_다음_재계산에서_역지오코딩을_다시_부르지_않는다() {
			given(missionRepository.findActive(any())).willReturn(List.of(mission(1L, MissionType.EVENT)));
			given(missionGridRepository.findByMissionIds(List.of(1L)))
				.willReturn(List.of(new MissionGrid(1L, cell(0, 0))));
			given(regionQueryService.resolveByPoint(anyDouble(), anyDouble())).willReturn(Optional.of(부전2동()));
			MissionQueryService service = newService();

			service.getMissionsInViewport(기준_뷰포트, MissionType.EVENT);
			clock.set(Instant.ofEpochMilli(TTL_MILLIS + 1));
			service.getMissionsInViewport(기준_뷰포트, MissionType.EVENT);

			verify(missionRepository, times(2)).findActive(any());
			// 사각형이 그대로면 판정 결과를 직전 세대에서 옮겨 싣는다 — 정상 상태의 시간당 추가 쿼리가 0 이다.
			verify(regionQueryService, times(1)).resolveByPoint(anyDouble(), anyDouble());
		}

		@Test
		@DisplayName("판정 사각형이 바뀐 미션은 다음 재계산에서 다시 판정한다")
		void 판정_사각형이_바뀐_미션은_다음_재계산에서_다시_판정한다() {
			given(missionRepository.findActive(any())).willReturn(List.of(mission(1L, MissionType.EVENT)));
			given(missionGridRepository.findByMissionIds(List.of(1L)))
				.willReturn(List.of(new MissionGrid(1L, cell(0, 0))))
				.willReturn(List.of(new MissionGrid(1L, cell(7, 7))));
			given(regionQueryService.resolveByPoint(anyDouble(), anyDouble())).willReturn(Optional.of(부전2동()));
			MissionQueryService service = newService();

			service.getMissionsInViewport(기준_뷰포트, MissionType.EVENT);
			clock.set(Instant.ofEpochMilli(TTL_MILLIS + 1));
			service.getMissionsInViewport(기준_뷰포트, MissionType.EVENT);

			GridPoint 옛_귀속점 = GridEncoder.center(cell(0, 0));
			GridPoint 새_귀속점 = GridEncoder.center(cell(7, 7));
			verify(regionQueryService).resolveByPoint(옛_귀속점.lat(), 옛_귀속점.lon());
			verify(regionQueryService).resolveByPoint(새_귀속점.lat(), 새_귀속점.lon());
		}

		@Test
		@DisplayName("집계도 같은 1시간 스냅숏을 쓴다 — TTL 안 재호출에 역지오코딩 추가 호출이 없다")
		void 집계도_같은_1시간_스냅숏을_쓴다() {
			given(missionRepository.findActive(any())).willReturn(List.of(mission(1L, MissionType.EVENT)));
			given(missionGridRepository.findByMissionIds(List.of(1L)))
				.willReturn(List.of(new MissionGrid(1L, cell(0, 0))));
			given(regionQueryService.resolveByPoint(anyDouble(), anyDouble())).willReturn(Optional.of(부전2동()));
			MissionQueryService service = newService();

			service.getMissionsInViewport(기준_뷰포트, MissionType.EVENT);
			clock.set(Instant.ofEpochMilli(TTL_MILLIS - 1));
			List<MissionRegionAggregateResponseDto> items =
				service.getMissionAggregates(기준_뷰포트, MissionType.EVENT, RegionUnit.SIGUNGU);

			// 집계가 자기 스냅숏을 따로 만들면 재계산과 역지오코딩이 한 벌 더 돈다 (D2).
			verify(missionRepository, times(1)).findActive(any());
			verify(regionQueryService, times(1)).resolveByPoint(anyDouble(), anyDouble());
			assertThat(items).singleElement()
				.extracting(MissionRegionAggregateResponseDto::regionCode).isEqualTo("26230");
		}
	}

	@Nested
	@DisplayName("부트 웜업")
	class 부트_웜업 {

		// 검증: FR-MISSION-20
		@Test
		@DisplayName("웜업이 스냅숏을 선계산해 첫 조회가 재계산 없이 응답한다")
		void 웜업이_스냅숏을_선계산해_첫_조회가_재계산_없이_응답한다() {
			given(missionRepository.findActive(any())).willReturn(List.of(mission(1L, MissionType.EVENT)));
			given(missionGridRepository.findByMissionIds(List.of(1L)))
				.willReturn(List.of(new MissionGrid(1L, cell(0, 0))));
			given(regionQueryService.resolveByPoint(anyDouble(), anyDouble())).willReturn(Optional.of(부전2동()));
			MissionQueryServiceImpl service = newService();

			service.warmUpSnapshot();
			List<MissionResponseDto> missions = service.getMissionsInViewport(기준_뷰포트, MissionType.EVENT);

			// 웜업이 재계산을 마쳤으면 첫 요청은 스냅숏만 읽는다 — dev 실측 콜드 1.51초를 사용자에게서 걷어낸다.
			verify(missionRepository, times(1)).findActive(any());
			verify(regionQueryService, times(1)).resolveByPoint(anyDouble(), anyDouble());
			assertThat(missions).extracting(MissionResponseDto::missionId).containsExactly(1L);
		}

		// 검증: FR-MISSION-20
		@Test
		@DisplayName("웜업 중 인프라 예외는 기동을 죽이지 않고 첫 요청이 재시도한다")
		void 웜업_중_인프라_예외는_기동을_죽이지_않고_첫_요청이_재시도한다() {
			given(missionRepository.findActive(any())).willReturn(List.of(mission(1L, MissionType.EVENT)));
			given(missionGridRepository.findByMissionIds(List.of(1L)))
				.willReturn(List.of(new MissionGrid(1L, cell(0, 0))));
			given(regionQueryService.resolveByPoint(anyDouble(), anyDouble()))
				.willThrow(new IllegalStateException("역지오코딩 DB 연결 실패"))
				.willReturn(Optional.of(부전2동()));
			MissionQueryServiceImpl service = newService();

			// 웜업은 성능 최적화지 기동 조건이 아니다 — 여기서 예외가 새면 앱이 뜨지 못한다.
			assertThatCode(service::warmUpSnapshot).doesNotThrowAnyException();
			List<MissionResponseDto> missions = service.getMissionsInViewport(기준_뷰포트, MissionType.EVENT);

			// 실패한 세대는 발행되지 않으므로 첫 사용자 요청이 재계산을 다시 시도해 정상 응답한다(D1).
			verify(missionRepository, times(2)).findActive(any());
			assertThat(missions).extracting(MissionResponseDto::missionId).containsExactly(1L);
		}

		// 검증: FR-MISSION-20
		@Test
		@DisplayName("웜업은 클래스 레벨 트랜잭션 밖이다 — NOT_SUPPORTED 가 지워지면 기동이 DB 장애에 죽는다")
		void 웜업은_클래스_레벨_트랜잭션_밖이다() throws Exception {
			// 이 어노테이션이 없으면 프록시가 리스너 진입에서 트랜잭션을 열다 실패하고(DB 장애 시
			// CannotCreateTransactionException), 메서드 안 try/catch 에 닿기 전에 예외가 새어 기동이 죽는다.
			// 위 두 테스트는 목 호출이라 프록시를 안 타므로 그 회귀를 못 잡는다 — 선언 자체를 잠근다.
			Transactional declared = MissionQueryServiceImpl.class
				.getDeclaredMethod("warmUpSnapshot").getAnnotation(Transactional.class);

			assertThat(declared).as("웜업 메서드에 @Transactional 선언이 없다").isNotNull();
			assertThat(declared.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
		}
	}
}
