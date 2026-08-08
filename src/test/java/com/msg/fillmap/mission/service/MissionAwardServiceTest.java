package com.msg.fillmap.mission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.msg.fillmap.badge.dto.EarnedBadgeResponseDto;
import com.msg.fillmap.badge.entity.BadgeConditionType;
import com.msg.fillmap.badge.service.BadgeAwardService;
import com.msg.fillmap.mission.dto.CompletedMissionResponseDto;
import com.msg.fillmap.mission.dto.MissionAwardResult;
import com.msg.fillmap.mission.repository.CompletedMissionProjection;
import com.msg.fillmap.mission.repository.MissionRepository;
import com.msg.fillmap.mission.repository.UserMissionRepository;
import com.msg.fillmap.mission.service.impl.MissionAwardServiceImpl;

/**
 * awardOnUpload 의 조기 종료(FR-18)·"INSERT 성공분만 응답" 필터(FR-14)·MISSION_COUNT 뱃지 배선(FR-17)
 * 단위 검증. 경합 타이밍(판정과 INSERT 사이의 상대 커밋)은 통합 테스트로 결정적 재현이 안 돼 mock 으로
 * 가른다 — BadgeAwardServiceTest 선례. 쿼리 자체는 MissionAwardQueryTest 가 실 DB 로 본다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MissionAwardService 판정·스탬프 발급")
class MissionAwardServiceTest {

	private static final long USER_ID = 42L;
	private static final String GRID_ID = "19422_9582";

	@Mock
	private MissionRepository missionRepository;

	@Mock
	private UserMissionRepository userMissionRepository;

	@Mock
	private BadgeAwardService badgeAwardService;

	@InjectMocks
	private MissionAwardServiceImpl missionAwardService;

	@Test
	@DisplayName("후보가 없으면 판정 쿼리 없이 조기 종료한다 — 역조회 1회뿐(FR-18)")
	void 후보가_없으면_판정_쿼리_없이_조기_종료한다() {
		given(missionRepository.findAwardCandidateIds(GRID_ID, USER_ID)).willReturn(List.of());

		MissionAwardResult result = missionAwardService.awardOnUpload(USER_ID, GRID_ID);

		assertThat(result.completedMissions()).isEmpty();
		assertThat(result.newBadges()).isEmpty();
		then(missionRepository).should(never()).findCompleted(anyLong(), any());
		then(userMissionRepository).should(never()).insertIgnoreConflict(anyLong(), anyLong());
	}

	@Test
	@DisplayName("충족 미션의 스탬프가 발급되고 결과에 담긴다 (FR-11)")
	void 충족_미션의_스탬프가_발급되고_결과에_담긴다() {
		given(missionRepository.findAwardCandidateIds(GRID_ID, USER_ID)).willReturn(List.of(3L));
		given(missionRepository.findCompleted(USER_ID, List.of(3L)))
			.willReturn(List.of(completed(3L, "성수 골목 코스", "COURSE")));
		given(userMissionRepository.insertIgnoreConflict(USER_ID, 3L)).willReturn(1);
		given(userMissionRepository.countMyStamps(USER_ID)).willReturn(BigDecimal.ONE);
		given(badgeAwardService.award(eq(USER_ID), eq(BadgeConditionType.MISSION_COUNT), any()))
			.willReturn(List.of());

		MissionAwardResult result = missionAwardService.awardOnUpload(USER_ID, GRID_ID);

		assertThat(result.completedMissions())
			.containsExactly(new CompletedMissionResponseDto(3L, "성수 골목 코스", "COURSE"));
	}

	@Test
	@DisplayName("경합으로 이미 발급된 스탬프는 결과에서 제외된다 — INSERT 0 경로(FR-14)")
	void 경합으로_이미_발급된_스탬프는_결과에서_제외된다() {
		given(missionRepository.findAwardCandidateIds(GRID_ID, USER_ID)).willReturn(List.of(3L, 4L));
		given(missionRepository.findCompleted(USER_ID, List.of(3L, 4L)))
			.willReturn(List.of(completed(3L, "성수 골목 코스", "COURSE"), completed(4L, "여름 축제", "EVENT")));
		// 판정과 INSERT 사이 동시 업로드의 상대 트랜잭션이 4번을 먼저 발급한 경합 — 내 INSERT 는 0.
		given(userMissionRepository.insertIgnoreConflict(USER_ID, 3L)).willReturn(1);
		given(userMissionRepository.insertIgnoreConflict(USER_ID, 4L)).willReturn(0);
		given(userMissionRepository.countMyStamps(USER_ID)).willReturn(BigDecimal.ONE);
		given(badgeAwardService.award(eq(USER_ID), eq(BadgeConditionType.MISSION_COUNT), any()))
			.willReturn(List.of());

		MissionAwardResult result = missionAwardService.awardOnUpload(USER_ID, GRID_ID);

		assertThat(result.completedMissions())
			.extracting(CompletedMissionResponseDto::missionId)
			.containsExactly(3L);
	}

	@Test
	@DisplayName("첫 스탬프에 MISSION_COUNT 1 뱃지가 함께 지급된다 (FR-17)")
	void 첫_스탬프에_MISSION_COUNT_1_뱃지가_함께_지급된다() {
		given(missionRepository.findAwardCandidateIds(GRID_ID, USER_ID)).willReturn(List.of(3L));
		given(missionRepository.findCompleted(USER_ID, List.of(3L)))
			.willReturn(List.of(completed(3L, "성수 골목 코스", "COURSE")));
		given(userMissionRepository.insertIgnoreConflict(USER_ID, 3L)).willReturn(1);
		given(userMissionRepository.countMyStamps(USER_ID)).willReturn(BigDecimal.ONE);
		given(badgeAwardService.award(USER_ID, BadgeConditionType.MISSION_COUNT, BigDecimal.ONE))
			.willReturn(List.of(badge(12L, "MISSION_1")));

		MissionAwardResult result = missionAwardService.awardOnUpload(USER_ID, GRID_ID);

		assertThat(result.newBadges()).extracting(EarnedBadgeResponseDto::code).containsExactly("MISSION_1");
	}

	@Test
	@DisplayName("스탬프 5개째에 다음 티어가 지급된다 — metric 은 내 스탬프 총수(FR-17)")
	void 스탬프_5개째에_다음_티어가_지급된다() {
		given(missionRepository.findAwardCandidateIds(GRID_ID, USER_ID)).willReturn(List.of(9L));
		given(missionRepository.findCompleted(USER_ID, List.of(9L)))
			.willReturn(List.of(completed(9L, "여름 축제", "EVENT")));
		given(userMissionRepository.insertIgnoreConflict(USER_ID, 9L)).willReturn(1);
		BigDecimal five = BigDecimal.valueOf(5);
		given(userMissionRepository.countMyStamps(USER_ID)).willReturn(five);
		given(badgeAwardService.award(USER_ID, BadgeConditionType.MISSION_COUNT, five))
			.willReturn(List.of(badge(13L, "MISSION_5")));

		MissionAwardResult result = missionAwardService.awardOnUpload(USER_ID, GRID_ID);

		assertThat(result.newBadges()).extracting(EarnedBadgeResponseDto::code).containsExactly("MISSION_5");
	}

	@Test
	@DisplayName("미충족이면 스탬프도 뱃지도 발급되지 않는다")
	void 미충족이면_스탬프도_뱃지도_발급되지_않는다() {
		given(missionRepository.findAwardCandidateIds(GRID_ID, USER_ID)).willReturn(List.of(3L));
		given(missionRepository.findCompleted(USER_ID, List.of(3L))).willReturn(List.of());

		MissionAwardResult result = missionAwardService.awardOnUpload(USER_ID, GRID_ID);

		assertThat(result.completedMissions()).isEmpty();
		assertThat(result.newBadges()).isEmpty();
		then(userMissionRepository).should(never()).insertIgnoreConflict(anyLong(), anyLong());
		then(badgeAwardService).should(never()).award(anyLong(), any(), any());
	}

	@Test
	@DisplayName("새 스탬프가 없으면 뱃지 판정을 호출하지 않는다 — 경합 전패 경로")
	void 새_스탬프가_없으면_뱃지_판정을_호출하지_않는다() {
		given(missionRepository.findAwardCandidateIds(GRID_ID, USER_ID)).willReturn(List.of(3L));
		given(missionRepository.findCompleted(USER_ID, List.of(3L)))
			.willReturn(List.of(completed(3L, "성수 골목 코스", "COURSE")));
		given(userMissionRepository.insertIgnoreConflict(USER_ID, 3L)).willReturn(0);

		MissionAwardResult result = missionAwardService.awardOnUpload(USER_ID, GRID_ID);

		assertThat(result.completedMissions()).isEmpty();
		assertThat(result.newBadges()).isEmpty();
		then(badgeAwardService).should(never()).award(anyLong(), any(), any());
	}

	private CompletedMissionProjection completed(long missionId, String title, String type) {
		return new CompletedMissionProjection() {
			@Override
			public Long getMissionId() {
				return missionId;
			}

			@Override
			public String getTitle() {
				return title;
			}

			@Override
			public String getType() {
				return type;
			}
		};
	}

	private EarnedBadgeResponseDto badge(long badgeId, String code) {
		return new EarnedBadgeResponseDto(badgeId, code, code, null, null);
	}
}
