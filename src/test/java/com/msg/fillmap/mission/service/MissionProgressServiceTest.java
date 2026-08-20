package com.msg.fillmap.mission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.mission.config.MissionViewportProperties;
import com.msg.fillmap.mission.dto.MissionProgressResponseDto;
import com.msg.fillmap.mission.repository.MissionGridRepository;
import com.msg.fillmap.mission.repository.MissionProgressProjection;
import com.msg.fillmap.mission.repository.MissionRepository;
import com.msg.fillmap.mission.service.impl.MissionQueryServiceImpl;
import com.msg.fillmap.region.service.RegionQueryService;
import com.msg.fillmap.video.repository.VideoRepository;

/**
 * 진행도 조회의 서비스 몫 검증 (순수 단위 · MSG-398 D7 · Owner B) — 벌크 id 가 DB 왕복 한 번으로
 * 내려가는지와 프로젝션 → DTO 매핑. 집계 값 자체(교집합·LEAST·스탬프)는 실 PostgreSQL 의
 * MissionProgressQueryTest 담당이라 여기서는 목 프로젝션을 쓴다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MissionQueryServiceImpl 진행도 조회 (서비스)")
class MissionProgressServiceTest {

	@Mock
	private MissionRepository missionRepository;

	@Mock
	private MissionGridRepository missionGridRepository;

	@Mock
	private VideoRepository videoRepository;

	@Mock
	private RegionQueryService regionQueryService;

	private MissionQueryService newService() {
		return new MissionQueryServiceImpl(missionRepository, missionGridRepository, videoRepository,
			new ObjectMapper(), new MissionViewportProperties(Map.of()), regionQueryService,
			Clock.systemUTC(), Duration.ofHours(1).toMillis());
	}

	private static MissionProgressProjection projection(long missionId, int target, int filled, boolean completed) {
		return new MissionProgressProjection() {
			@Override
			public Long getMissionId() {
				return missionId;
			}

			@Override
			public Integer getTargetCount() {
				return target;
			}

			@Override
			public Integer getFilledCount() {
				return filled;
			}

			@Override
			public Boolean getCompleted() {
				return completed;
			}
		};
	}

	// 검증: FR-MISSION-18
	@Test
	@DisplayName("미션 id 여러 개를 한 번에 조회한다 — 카드 수만큼 왕복이 늘지 않는다")
	void 미션_id_여러_개를_한_번에_조회한다() {
		List<Long> missionIds = List.of(412L, 413L, 414L);
		given(missionRepository.findProgress(7L, missionIds)).willReturn(List.of(
			projection(412L, 1, 1, true),
			projection(413L, 3, 0, false),
			projection(414L, 3, 2, false)));

		List<MissionProgressResponseDto> result = newService().getMyProgress(7L, missionIds);

		// DB 왕복은 요청당 한 번 — id 하나씩 반복 조회로 바꾸면 여기서 깨진다.
		then(missionRepository).should(times(1)).findProgress(7L, missionIds);
		assertThat(result).containsExactly(
			new MissionProgressResponseDto(412L, 1, 1, true),
			new MissionProgressResponseDto(413L, 3, 0, false),
			new MissionProgressResponseDto(414L, 3, 2, false));
	}

	@Test
	@DisplayName("missionIds가 없거나 비면 DB에 가지 않고 빈 배열이다")
	void missionIds가_없거나_비면_DB에_가지_않고_빈_배열이다() {
		MissionQueryService service = newService();

		assertThat(service.getMyProgress(7L, null)).isEmpty();
		assertThat(service.getMyProgress(7L, List.of())).isEmpty();
		then(missionRepository).shouldHaveNoInteractions();
	}
}
