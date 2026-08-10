package com.msg.fillmap.mission.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.badge.dto.EarnedBadgeResponseDto;
import com.msg.fillmap.badge.entity.BadgeConditionType;
import com.msg.fillmap.badge.service.BadgeAwardService;
import com.msg.fillmap.mission.dto.CompletedMissionResponseDto;
import com.msg.fillmap.mission.dto.MissionAwardResult;
import com.msg.fillmap.mission.repository.CompletedMissionProjection;
import com.msg.fillmap.mission.repository.MissionRepository;
import com.msg.fillmap.mission.repository.UserMissionRepository;
import com.msg.fillmap.mission.service.MissionAwardService;

/**
 * 미션 판정 구현 (MSG-223 §D2 + MSG-363 §D3). 후보 역조회 → 단일 판정 쿼리 → 스탬프 INSERT →
 * 새 스탬프의 종류마다 그 종류의 뱃지 판정 순 — 지배 경로(미션 미해당 격자)는 1단계 빈 결과로 끝나
 * 비용이 인덱스드 조회 하나다(FR-18). 결과에는 INSERT 가 실제로 성공한(영향 행 1) 스탬프만 담는다:
 * 동시 업로드 경합에서 상대 트랜잭션이 먼저 발급했다면 내 INSERT 는 ON CONFLICT 로 0 을 받고,
 * "이 요청으로 새로 획득한" 게 아니므로 제외한다
 * (BadgeAwardServiceImpl §D5 미러 — PostgreSQL 이 conflict 판정 전 선행 커밋을 대기해 경합에도 안전).
 * 조회 캐시(MissionQueryServiceImpl 1h 스냅샷)는 읽지 않는다 — 방금 시작한 미션이 누락된다.
 */
@Service
@RequiredArgsConstructor
public class MissionAwardServiceImpl implements MissionAwardService {

	// 미션 유형에서 뱃지 판정 축으로 (MSG-363 §D3). 화면 칩이 있는 3종만 매핑하고 나머지는 부재(FR-6).
	private static final Map<String, BadgeConditionType> BADGE_AXIS = Map.of(
		"EVENT", BadgeConditionType.EVENT_COUNT,
		"COURSE", BadgeConditionType.COURSE_COUNT,
		"POPUP", BadgeConditionType.POPUP_COUNT);

	private final MissionRepository missionRepository;
	private final UserMissionRepository userMissionRepository;
	private final BadgeAwardService badgeAwardService;

	@Override
	@Transactional
	public MissionAwardResult awardOnUpload(long userId, String gridId) {
		List<Long> candidateIds = missionRepository.findAwardCandidateIds(gridId, userId);
		if (candidateIds.isEmpty()) {
			return MissionAwardResult.EMPTY;
		}
		List<CompletedMissionResponseDto> stamps = new ArrayList<>();
		// 이번 요청으로 실제 발급된 스탬프의 종류만 모은다 — 경합에서 진 스탬프는 판정 축에도 안 들어간다.
		// 같은 종류가 둘이어도 metric 이 그 종류의 총계라 축당 한 번이면 충분하고(Set), 응답 배열 순서가
		// 실행마다 흔들리지 않게 삽입 순서를 유지한다(LinkedHashSet). MSG-363 §D3.
		Set<String> newStampTypes = new LinkedHashSet<>();
		for (CompletedMissionProjection completed : missionRepository.findCompleted(userId, candidateIds)) {
			if (userMissionRepository.insertIgnoreConflict(userId, completed.getMissionId()) == 1) {
				stamps.add(CompletedMissionResponseDto.from(completed));
				newStampTypes.add(completed.getType());
			}
		}
		if (stamps.isEmpty()) {
			return MissionAwardResult.EMPTY;
		}
		List<EarnedBadgeResponseDto> newBadges = new ArrayList<>();
		for (String type : newStampTypes) {
			// 매핑에 없는 유형(AREA·THEME·CONTINUOUS)을 건너뛰는 것이 정상 경로라(FR-6) MissionType 으로
			// valueOf 하지 않는다 — 예외를 던질 수 있는 변환을 끼울 이유가 없다.
			BadgeConditionType axis = BADGE_AXIS.get(type);
			if (axis == null) {
				continue;
			}
			newBadges.addAll(badgeAwardService.award(
				userId, axis, userMissionRepository.countMyStampsByType(userId, type)));
		}
		return new MissionAwardResult(stamps, newBadges);
	}
}
