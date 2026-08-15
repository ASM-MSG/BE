package com.msg.fillmap.mission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.mission.config.MissionViewportProperties;
import com.msg.fillmap.mission.dto.MissionResponseDto;
import com.msg.fillmap.mission.entity.Mission;
import com.msg.fillmap.mission.entity.MissionGrid;
import com.msg.fillmap.mission.entity.MissionType;
import com.msg.fillmap.mission.repository.MissionGridRepository;
import com.msg.fillmap.mission.repository.MissionRepository;
import com.msg.fillmap.mission.service.impl.MissionQueryServiceImpl;

/**
 * 1h 전역 캐시 만료 검증 (MissionQueryServiceImpl, 순수 단위 · MSG-222 §도메인 4 → MSG-398 D1 · Owner B).
 * TTL 내 재호출은 재쿼리 없이 스냅샷을 재사용하고, TTL 만료 후 재호출은 재계산하는지 — 주입 클럭을 앞당겨
 * 검증한다. MSG-398 이 뷰포트 파라미터를 붙여도 스냅샷의 내용과 캐시 키는 바뀌지 않는다(D1) — 뷰포트가
 * 달라져도 재계산이 없는지, 재계산된 스냅샷에도 판정 사각형이 함께 들어오는지가 이 파일 몫이다.
 * shape 합성은 MissionQueryServiceImplTest 담당.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MissionQueryServiceImpl 1h 전역 캐시")
class MissionQueryServiceCacheTest {

	private static final long TTL_MILLIS = Duration.ofHours(1).toMillis();

	/** 강남 일대 소형 뷰포트 — 어떤 것을 써도 스냅샷은 전국 한 벌이다(D1). */
	private static final ViewportBounds 강남_뷰포트 = new ViewportBounds(37.50, 127.00, 37.55, 127.05);
	private static final ViewportBounds 홍대_뷰포트 = new ViewportBounds(37.54, 126.90, 37.57, 126.95);
	private static final ViewportBounds 부산_뷰포트 = new ViewportBounds(35.05, 129.00, 35.15, 129.10);

	@Mock
	private MissionRepository missionRepository;

	@Mock
	private MissionGridRepository missionGridRepository;

	/** 만료 경계를 넘기려 시각을 임의로 앞당길 수 있는 클럭 — 홀더가 clock.millis()/now(clock) 로 시간을 읽는다. */
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

	private MissionQueryService newService(MutableClock clock) {
		return new MissionQueryServiceImpl(missionRepository, missionGridRepository,
			new ObjectMapper(), new MissionViewportProperties(Map.of()), clock, TTL_MILLIS);
	}

	@Test
	@DisplayName("TTL 내 재호출은 재쿼리없이 같은 스냅샷을 반환한다")
	void TTL_내_재호출은_재쿼리없이_같은_스냅샷을_반환한다() {
		MutableClock clock = new MutableClock(Instant.ofEpochMilli(0));
		MissionQueryService service = newService(clock);
		given(missionRepository.findActive(any())).willReturn(List.of());

		service.getMissionsInViewport(강남_뷰포트, MissionType.EVENT);
		clock.set(Instant.ofEpochMilli(TTL_MILLIS - 1));
		service.getMissionsInViewport(강남_뷰포트, MissionType.EVENT);

		// TTL 안이면 findActive 는 첫 계산 1회뿐 — 두 번째 호출은 스냅샷 재사용.
		verify(missionRepository, times(1)).findActive(any());
	}

	@Test
	@DisplayName("스냅샷은 뷰포트가 달라도 한 번만 재계산된다 — 캐시 키에 뷰포트가 없다 (MSG-398 D1)")
	void 스냅샷은_뷰포트가_달라도_한_번만_재계산된다() {
		MutableClock clock = new MutableClock(Instant.ofEpochMilli(0));
		MissionQueryService service = newService(clock);
		given(missionRepository.findActive(any())).willReturn(List.of());

		service.getMissionsInViewport(강남_뷰포트, MissionType.EVENT);
		service.getMissionsInViewport(홍대_뷰포트, MissionType.POPUP);
		service.getMissionsInViewport(부산_뷰포트, MissionType.COURSE);

		// 뷰포트별 캐시 키를 만들면(D1 기각안 1) 세 번 재계산된다 — 스냅샷은 전국 한 벌.
		verify(missionRepository, times(1)).findActive(any());
	}

	@Test
	@DisplayName("TTL이 지나면 재계산한다")
	void TTL이_지나면_재계산한다() {
		MutableClock clock = new MutableClock(Instant.ofEpochMilli(0));
		MissionQueryService service = newService(clock);
		given(missionRepository.findActive(any())).willReturn(List.of());

		service.getMissionsInViewport(강남_뷰포트, MissionType.EVENT);
		clock.set(Instant.ofEpochMilli(TTL_MILLIS + 1));
		List<?> result = service.getMissionsInViewport(강남_뷰포트, MissionType.EVENT);

		// 만료를 넘겼으니 재계산 — findActive 2회.
		verify(missionRepository, times(2)).findActive(any());
		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("동시 만료에서 스냅샷이 한 번만 재계산된다 — 더블체크 락이 중복 조회를 흡수한다")
	void 동시_만료에서_스냅샷이_한_번만_재계산된다() throws Exception {
		MutableClock clock = new MutableClock(Instant.ofEpochMilli(0));
		MissionQueryService service = newService(clock);
		given(missionRepository.findActive(any())).willAnswer(invocation -> {
			Thread.sleep(50); // 재계산을 늘려 만료 동시 진입 창을 확보한다.
			return List.of();
		});
		service.getMissionsInViewport(강남_뷰포트, MissionType.EVENT);
		clock.set(Instant.ofEpochMilli(TTL_MILLIS + 1));

		int threads = 4;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<?>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				futures.add(pool.submit(() -> {
					start.await();
					return service.getMissionsInViewport(강남_뷰포트, MissionType.EVENT);
				}));
			}
			start.countDown();
			for (Future<?> future : futures) {
				future.get();
			}
		} finally {
			pool.shutdownNow();
		}

		// 첫 계산 1회 + 만료 재계산 1회 — 락 안 재확인이 없으면 만료 요청 수만큼 재계산된다.
		verify(missionRepository, times(2)).findActive(any());
	}

	@Test
	@DisplayName("재계산된 스냅샷에도 사각형이 함께 들어온다 — 만료 후에도 뷰포트 판정이 유지된다 (MSG-398 D2)")
	void 재계산된_스냅샷에도_사각형이_함께_들어온다() {
		MutableClock clock = new MutableClock(Instant.ofEpochMilli(0));
		MissionQueryService service = newService(clock);
		Mission mission = Mission.builder()
			.type(MissionType.EVENT).title("사각형 검증 축제").targetCount(1).build();
		ReflectionTestUtils.setField(mission, "id", 5L); // 시더 밖 합성 엔티티라 id 를 직접 부여한다.
		String insideGangnam = GridEncoder.encode(37.52, 127.02);
		given(missionRepository.findActive(any())).willReturn(List.of(mission));
		given(missionGridRepository.findByMissionIds(List.of(5L)))
			.willReturn(List.of(new MissionGrid(5L, insideGangnam)));

		List<MissionResponseDto> first = service.getMissionsInViewport(강남_뷰포트, MissionType.EVENT);
		clock.set(Instant.ofEpochMilli(TTL_MILLIS + 1));
		List<MissionResponseDto> outside = service.getMissionsInViewport(부산_뷰포트, MissionType.EVENT);
		List<MissionResponseDto> second = service.getMissionsInViewport(강남_뷰포트, MissionType.EVENT);

		verify(missionRepository, times(2)).findActive(any());
		assertThat(first).extracting(MissionResponseDto::missionId).containsExactly(5L);
		// 재계산된 스냅샷이 사각형을 잃으면 강남 뷰포트에서도 사라진다 — 부산에서는 빠지고 강남에서는 나와야 한다.
		assertThat(outside).isEmpty();
		assertThat(second).extracting(MissionResponseDto::missionId).containsExactly(5L);
	}
}
