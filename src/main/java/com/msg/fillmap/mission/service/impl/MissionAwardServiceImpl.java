package com.msg.fillmap.mission.service.impl;

import java.util.ArrayList;
import java.util.List;

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
 * 미션 판정 구현 (MSG-223 §D2). 후보 역조회 → 단일 판정 쿼리 → 스탬프 INSERT → MISSION_COUNT 뱃지 순 —
 * 지배 경로(미션 미해당 격자)는 1단계 빈 결과로 끝나 비용이 인덱스드 조회 하나다(FR-18). 결과에는
 * INSERT 가 실제로 성공한(영향 행 1) 스탬프만 담는다: 동시 업로드 경합에서 상대 트랜잭션이 먼저
 * 발급했다면 내 INSERT 는 ON CONFLICT 로 0 을 받고, "이 요청으로 새로 획득한" 게 아니므로 제외한다
 * (BadgeAwardServiceImpl §D5 미러 — PostgreSQL 이 conflict 판정 전 선행 커밋을 대기해 경합에도 안전).
 * 조회 캐시(MissionQueryServiceImpl 1h 스냅샷)는 읽지 않는다 — 방금 시작한 미션이 누락된다.
 */
@Service
@RequiredArgsConstructor
public class MissionAwardServiceImpl implements MissionAwardService {

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
		for (CompletedMissionProjection completed : missionRepository.findCompleted(userId, candidateIds)) {
			if (userMissionRepository.insertIgnoreConflict(userId, completed.getMissionId()) == 1) {
				stamps.add(CompletedMissionResponseDto.from(completed));
			}
		}
		if (stamps.isEmpty()) {
			return MissionAwardResult.EMPTY;
		}
		List<EarnedBadgeResponseDto> newBadges = badgeAwardService.award(
			userId, BadgeConditionType.MISSION_COUNT, userMissionRepository.countMyStamps(userId));
		return new MissionAwardResult(stamps, newBadges);
	}
}
