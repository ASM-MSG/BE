package com.msg.fillmap.mission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
 * awardOnUpload 의 조기 종료(FR-18)·"INSERT 성공분만 응답" 필터(FR-14)·종류별 뱃지 배선
 * (MSG-363 FR-1·FR-6) 단위 검증. 경합 타이밍(판정과 INSERT 사이의 상대 커밋)은 통합 테스트로
 * 결정적 재현이 안 돼 mock 으로 가른다 — BadgeAwardServiceTest 선례. 쿼리 자체는
 * MissionAwardQueryTest 가 실 DB 로 본다.
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

	// 검증: FR-MISSION-03
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

	// 검증: FR-MISSION-03, FR-MISSION-06
	@Test
	@DisplayName("충족 미션의 스탬프가 발급되고 결과에 담긴다 (FR-11)")
	void 충족_미션의_스탬프가_발급되고_결과에_담긴다() {
		given(missionRepository.findAwardCandidateIds(GRID_ID, USER_ID)).willReturn(List.of(3L));
		given(missionRepository.findCompleted(USER_ID, List.of(3L)))
			.willReturn(List.of(completed(3L, "성수 골목 코스", "COURSE")));
		given(userMissionRepository.insertIgnoreConflict(USER_ID, 3L)).willReturn(1);
		given(userMissionRepository.countMyStampsByType(USER_ID, "COURSE")).willReturn(BigDecimal.ONE);
		given(badgeAwardService.award(eq(USER_ID), eq(BadgeConditionType.COURSE_COUNT), any()))
			.willReturn(List.of());

		MissionAwardResult result = missionAwardService.awardOnUpload(USER_ID, GRID_ID);

		assertThat(result.completedMissions())
			.containsExactly(new CompletedMissionResponseDto(3L, "성수 골목 코스", "COURSE"));
	}

	// 검증: FR-MISSION-04
	@Test
	@DisplayName("경합으로 이미 발급된 스탬프는 결과에서 제외된다 — INSERT 0 경로(FR-14)")
	void 경합으로_이미_발급된_스탬프는_결과에서_제외된다() {
		given(missionRepository.findAwardCandidateIds(GRID_ID, USER_ID)).willReturn(List.of(3L, 4L));
		given(missionRepository.findCompleted(USER_ID, List.of(3L, 4L)))
			.willReturn(List.of(completed(3L, "성수 골목 코스", "COURSE"), completed(4L, "여름 축제", "EVENT")));
		// 판정과 INSERT 사이 동시 업로드의 상대 트랜잭션이 4번을 먼저 발급한 경합 — 내 INSERT 는 0.
		given(userMissionRepository.insertIgnoreConflict(USER_ID, 3L)).willReturn(1);
		given(userMissionRepository.insertIgnoreConflict(USER_ID, 4L)).willReturn(0);
		given(userMissionRepository.countMyStampsByType(USER_ID, "COURSE")).willReturn(BigDecimal.ONE);
		given(badgeAwardService.award(eq(USER_ID), eq(BadgeConditionType.COURSE_COUNT), any()))
			.willReturn(List.of());

		MissionAwardResult result = missionAwardService.awardOnUpload(USER_ID, GRID_ID);

		assertThat(result.completedMissions())
			.extracting(CompletedMissionResponseDto::missionId)
			.containsExactly(3L);
	}

	// 검증: FR-BADGE-12
	@Test
	@DisplayName("축제 미션 완료는 축제 축으로만 판정된다 — 종류 간 합산 없음(MSG-363 FR-1)")
	void 축제_미션_완료는_축제_축으로만_판정된다() {
		given(missionRepository.findAwardCandidateIds(GRID_ID, USER_ID)).willReturn(List.of(9L));
		given(missionRepository.findCompleted(USER_ID, List.of(9L)))
			.willReturn(List.of(completed(9L, "여름 축제", "EVENT")));
		given(userMissionRepository.insertIgnoreConflict(USER_ID, 9L)).willReturn(1);
		given(userMissionRepository.countMyStampsByType(USER_ID, "EVENT")).willReturn(BigDecimal.ONE);
		given(badgeAwardService.award(USER_ID, BadgeConditionType.EVENT_COUNT, BigDecimal.ONE))
			.willReturn(List.of(badge(18L, "EVENT_1")));

		MissionAwardResult result = missionAwardService.awardOnUpload(USER_ID, GRID_ID);

		assertThat(result.newBadges()).extracting(EarnedBadgeResponseDto::code).containsExactly("EVENT_1");
		then(badgeAwardService).should()
			.award(USER_ID, BadgeConditionType.EVENT_COUNT, BigDecimal.ONE);
		then(badgeAwardService).should(never())
			.award(anyLong(), eq(BadgeConditionType.COURSE_COUNT), any());
		then(badgeAwardService).should(never())
			.award(anyLong(), eq(BadgeConditionType.POPUP_COUNT), any());
	}

	// 검증: FR-BADGE-12
	@Test
	@DisplayName("한 업로드가 축제와 팝업을 동시에 완료하면 두 축이 각각 판정된다 (엣지 케이스 1)")
	void 한_업로드가_축제와_팝업을_동시에_완료하면_두_축이_각각_판정된다() {
		given(missionRepository.findAwardCandidateIds(GRID_ID, USER_ID)).willReturn(List.of(9L, 11L));
		given(missionRepository.findCompleted(USER_ID, List.of(9L, 11L)))
			.willReturn(List.of(completed(9L, "여름 축제", "EVENT"), completed(11L, "성수 팝업", "POPUP")));
		given(userMissionRepository.insertIgnoreConflict(USER_ID, 9L)).willReturn(1);
		given(userMissionRepository.insertIgnoreConflict(USER_ID, 11L)).willReturn(1);
		given(userMissionRepository.countMyStampsByType(USER_ID, "EVENT")).willReturn(BigDecimal.ONE);
		given(userMissionRepository.countMyStampsByType(USER_ID, "POPUP")).willReturn(BigDecimal.ONE);
		given(badgeAwardService.award(USER_ID, BadgeConditionType.EVENT_COUNT, BigDecimal.ONE))
			.willReturn(List.of(badge(18L, "EVENT_1")));
		given(badgeAwardService.award(USER_ID, BadgeConditionType.POPUP_COUNT, BigDecimal.ONE))
			.willReturn(List.of(badge(24L, "POPUP_1")));

		MissionAwardResult result = missionAwardService.awardOnUpload(USER_ID, GRID_ID);

		// 순서는 완료 스탬프 발급 순서를 따른다 (LinkedHashSet).
		assertThat(result.newBadges()).extracting(EarnedBadgeResponseDto::code)
			.containsExactly("EVENT_1", "POPUP_1");
	}

	// 검증: FR-BADGE-12
	@Test
	@DisplayName("같은 종류 미션 두 개를 완료해도 그 축 판정은 한 번이다 — metric 이 종류 총계라 (MSG-363 §D3)")
	void 같은_종류_미션_두_개를_완료해도_그_축_판정은_한_번이다() {
		given(missionRepository.findAwardCandidateIds(GRID_ID, USER_ID)).willReturn(List.of(9L, 10L));
		given(missionRepository.findCompleted(USER_ID, List.of(9L, 10L)))
			.willReturn(List.of(completed(9L, "여름 축제", "EVENT"), completed(10L, "가을 축제", "EVENT")));
		given(userMissionRepository.insertIgnoreConflict(USER_ID, 9L)).willReturn(1);
		given(userMissionRepository.insertIgnoreConflict(USER_ID, 10L)).willReturn(1);
		BigDecimal two = BigDecimal.valueOf(2);
		given(userMissionRepository.countMyStampsByType(USER_ID, "EVENT")).willReturn(two);
		given(badgeAwardService.award(USER_ID, BadgeConditionType.EVENT_COUNT, two))
			.willReturn(List.of(badge(18L, "EVENT_1")));

		MissionAwardResult result = missionAwardService.awardOnUpload(USER_ID, GRID_ID);

		assertThat(result.newBadges()).extracting(EarnedBadgeResponseDto::code).containsExactly("EVENT_1");
		then(badgeAwardService).should().award(anyLong(), any(), any());
	}

	// 검증: FR-BADGE-12
	@Test
	@DisplayName("구역·테마·지속 미션 완료는 어떤 뱃지도 판정하지 않는다 — 화면 칩이 없는 유형(MSG-363 FR-6)")
	void 구역_테마_지속_미션_완료는_어떤_뱃지도_판정하지_않는다() {
		given(missionRepository.findAwardCandidateIds(GRID_ID, USER_ID)).willReturn(List.of(7L));
		given(missionRepository.findCompleted(USER_ID, List.of(7L)))
			.willReturn(List.of(completed(7L, "성동구 한 바퀴", "AREA")));
		given(userMissionRepository.insertIgnoreConflict(USER_ID, 7L)).willReturn(1);

		MissionAwardResult result = missionAwardService.awardOnUpload(USER_ID, GRID_ID);

		assertThat(result.completedMissions())
			.extracting(CompletedMissionResponseDto::missionId)
			.containsExactly(7L);
		assertThat(result.newBadges()).isEmpty();
		then(badgeAwardService).should(never()).award(anyLong(), any(), any());
		then(userMissionRepository).should(never()).countMyStampsByType(anyLong(), anyString());
	}

	// 검증: FR-BADGE-12
	@Test
	@DisplayName("칩 없는 유형이 먼저 완료돼도 뒤따르는 축은 판정된다 — 건너뛰기지 중단이 아니다(FR-6)")
	void 칩_없는_유형이_먼저_완료돼도_뒤따르는_축은_판정된다() {
		given(missionRepository.findAwardCandidateIds(GRID_ID, USER_ID)).willReturn(List.of(7L, 9L));
		given(missionRepository.findCompleted(USER_ID, List.of(7L, 9L)))
			.willReturn(List.of(completed(7L, "성동구 한 바퀴", "AREA"), completed(9L, "여름 축제", "EVENT")));
		given(userMissionRepository.insertIgnoreConflict(USER_ID, 7L)).willReturn(1);
		given(userMissionRepository.insertIgnoreConflict(USER_ID, 9L)).willReturn(1);
		given(userMissionRepository.countMyStampsByType(USER_ID, "EVENT")).willReturn(BigDecimal.ONE);
		given(badgeAwardService.award(USER_ID, BadgeConditionType.EVENT_COUNT, BigDecimal.ONE))
			.willReturn(List.of(badge(18L, "EVENT_1")));

		MissionAwardResult result = missionAwardService.awardOnUpload(USER_ID, GRID_ID);

		// 매핑에 없는 AREA 가 앞에 있어도 뒤의 EVENT 축까지 루프가 이어져야 한다 — break 면 여기서 빈다.
		assertThat(result.completedMissions()).extracting(CompletedMissionResponseDto::missionId)
			.containsExactly(7L, 9L);
		assertThat(result.newBadges()).extracting(EarnedBadgeResponseDto::code).containsExactly("EVENT_1");
	}

	// 검증: FR-BADGE-12
	@Test
	@DisplayName("경합으로 발급되지 않은 스탬프의 종류는 판정 축에 들어가지 않는다 (MSG-363 §D3)")
	void 경합으로_발급되지_않은_스탬프의_종류는_판정_축에_들어가지_않는다() {
		given(missionRepository.findAwardCandidateIds(GRID_ID, USER_ID)).willReturn(List.of(9L, 11L));
		given(missionRepository.findCompleted(USER_ID, List.of(9L, 11L)))
			.willReturn(List.of(completed(9L, "여름 축제", "EVENT"), completed(11L, "성수 팝업", "POPUP")));
		given(userMissionRepository.insertIgnoreConflict(USER_ID, 9L)).willReturn(1);
		given(userMissionRepository.insertIgnoreConflict(USER_ID, 11L)).willReturn(0);
		given(userMissionRepository.countMyStampsByType(USER_ID, "EVENT")).willReturn(BigDecimal.ONE);
		given(badgeAwardService.award(USER_ID, BadgeConditionType.EVENT_COUNT, BigDecimal.ONE))
			.willReturn(List.of(badge(18L, "EVENT_1")));

		MissionAwardResult result = missionAwardService.awardOnUpload(USER_ID, GRID_ID);

		assertThat(result.newBadges()).extracting(EarnedBadgeResponseDto::code).containsExactly("EVENT_1");
		then(badgeAwardService).should(never())
			.award(anyLong(), eq(BadgeConditionType.POPUP_COUNT), any());
		then(userMissionRepository).should(never()).countMyStampsByType(USER_ID, "POPUP");
	}

	// 검증: FR-MISSION-03
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

	// 검증: FR-MISSION-04
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
