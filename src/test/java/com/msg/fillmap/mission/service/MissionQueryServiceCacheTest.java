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
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.msg.fillmap.mission.repository.MissionGridRepository;
import com.msg.fillmap.mission.repository.MissionRepository;
import com.msg.fillmap.mission.service.impl.MissionQueryServiceImpl;

/**
 * 1h 전역 캐시 만료 검증 (MissionQueryServiceImpl, 순수 단위 · MSG-222 §도메인 4 · Owner B). TTL 내 재호출은
 * 재쿼리 없이 스냅샷을 재사용하고, TTL 만료 후 재호출은 재계산하는지 — 주입 클럭을 앞당겨 검증한다. shape 합성은
 * MissionQueryServiceImplTest 담당이라 findActive 는 빈 리스트를 반환해 grid 조회 없이 홀더 동작만 격리한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MissionQueryServiceImpl 1h 전역 캐시")
class MissionQueryServiceCacheTest {

	private static final long TTL_MILLIS = Duration.ofHours(1).toMillis();

	@Mock
	private MissionRepository missionRepository;

	@Mock
	private MissionGridRepository missionGridRepository;

	/** 만료 경계를 넘기려 시각을 임의로 앞당길 수 있는 클럭 — 홀더가 clock.millis()/now(clock) 로 시간을 읽는다. */
	private static final class MutableClock extends Clock {

		private Instant now;

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

	@Test
	@DisplayName("TTL 내 재호출은 재쿼리없이 같은 스냅샷을 반환한다")
	void TTL_내_재호출은_재쿼리없이_같은_스냅샷을_반환한다() {
		MutableClock clock = new MutableClock(Instant.ofEpochMilli(0));
		MissionQueryService service = new MissionQueryServiceImpl(
			missionRepository, missionGridRepository, clock, TTL_MILLIS);
		given(missionRepository.findActive(any())).willReturn(List.of());

		service.getActiveMissions();
		clock.set(Instant.ofEpochMilli(TTL_MILLIS - 1));
		service.getActiveMissions();

		// TTL 안이면 findActive 는 첫 계산 1회뿐 — 두 번째 호출은 스냅샷 재사용.
		verify(missionRepository, times(1)).findActive(any());
	}

	@Test
	@DisplayName("TTL 만료 후 재호출은 재계산한다")
	void TTL_만료_후_재호출은_재계산한다() {
		MutableClock clock = new MutableClock(Instant.ofEpochMilli(0));
		MissionQueryService service = new MissionQueryServiceImpl(
			missionRepository, missionGridRepository, clock, TTL_MILLIS);
		given(missionRepository.findActive(any())).willReturn(List.of());

		service.getActiveMissions();
		clock.set(Instant.ofEpochMilli(TTL_MILLIS + 1));
		List<?> result = service.getActiveMissions();

		// 만료를 넘겼으니 재계산 — findActive 2회.
		verify(missionRepository, times(2)).findActive(any());
		assertThat(result).isEmpty();
	}
}
