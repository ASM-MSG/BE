package com.msg.fillmap.mission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.mission.config.MissionViewportProperties;
import com.msg.fillmap.mission.entity.MissionType;
import com.msg.fillmap.mission.repository.MissionGridRepository;
import com.msg.fillmap.mission.repository.MissionRepository;
import com.msg.fillmap.mission.service.impl.MissionQueryServiceImpl;
import com.msg.fillmap.video.repository.VideoRepository;

/**
 * 운영 기본 클럭의 존 계약 회귀 검증 (MSG-223 후속 결함 수정). findActive 의 :now 는 UTC 저장
 * start_at/end_at(§D3)과 비교되므로 UTC 벽시계여야 한다 — 기본 생성자가 systemDefaultZone 이면
 * KST JVM(로컬)에서 활성 판정이 +9h 스큐된다(dev 서버는 UTC 라 무증상이던 잠복 결함).
 * JVM 기본존을 KST 로 고정해 UTC CI 에서도 스큐가 결정적으로 드러나게 한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MissionQueryServiceImpl 기본 클럭 존 계약")
class MissionQueryServiceClockTest {

	@Mock
	private MissionRepository missionRepository;

	@Mock
	private MissionGridRepository missionGridRepository;

	@Mock
	private VideoRepository videoRepository;

	// 검증: FR-MISSION-02
	@Test
	@DisplayName("기본 클럭은 JVM 기본존과 무관하게 UTC 벽시계로 활성 판정한다")
	void 기본_클럭은_JVM_기본존과_무관하게_UTC_벽시계로_활성_판정한다() {
		given(missionRepository.findActive(any())).willReturn(List.of());

		TimeZone original = TimeZone.getDefault();
		try {
			TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
			// 운영 배선과 같은 기본 생성자 — 주입 생성자로는 이 결함(기본 클럭 선택)이 재현되지 않는다.
			new MissionQueryServiceImpl(missionRepository, missionGridRepository, videoRepository,
				new ObjectMapper(), new MissionViewportProperties(Map.of()))
				.getMissionsInViewport(new ViewportBounds(37.50, 127.00, 37.55, 127.05), MissionType.EVENT);
		} finally {
			TimeZone.setDefault(original);
		}

		ArgumentCaptor<LocalDateTime> passed = ArgumentCaptor.forClass(LocalDateTime.class);
		then(missionRepository).should().findActive(passed.capture());
		long driftMinutes = Math.abs(
			Duration.between(LocalDateTime.now(ZoneOffset.UTC), passed.getValue()).toMinutes());
		assertThat(driftMinutes).as("KST 기본존 클럭이면 +9h(540분) 스큐가 잡힌다").isLessThan(60);
	}
}
