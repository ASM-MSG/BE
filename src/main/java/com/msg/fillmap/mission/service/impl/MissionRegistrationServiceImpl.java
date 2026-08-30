package com.msg.fillmap.mission.service.impl;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.mission.entity.Mission;
import com.msg.fillmap.mission.entity.MissionGrid;
import com.msg.fillmap.mission.entity.MissionMetadata;
import com.msg.fillmap.mission.repository.MissionGridRepository;
import com.msg.fillmap.mission.repository.MissionRepository;
import com.msg.fillmap.mission.seed.FestivalMissionSeeder;
import com.msg.fillmap.mission.seed.MissionRepresentativeGrids;
import com.msg.fillmap.mission.service.MissionQueryService;
import com.msg.fillmap.mission.service.MissionRegistrationService;

/**
 * 승인 미션 등재 (MSG-500 D-2). 시더가 만드는 미션과 다른 점은 셋뿐이다: source 가
 * {@code ORG_SUBMISSION} 이라 시더 정리·dedupe 가 건드리지 않고, sourceKey 가 신청 번호라 이중 승인이
 * DB 부분 유니크에서 막히며, 커밋 후 목록 스냅숏을 비운다(요청 경로에서 만들어지므로).
 */
@Service
@RequiredArgsConstructor
public class MissionRegistrationServiceImpl implements MissionRegistrationService {

	/**
	 * 적재 출처 값 — 이 경로 산출물 식별자다. 시더 정리({@code deleteEndedBySourceWithoutStamps})와 dedupe 가
	 * 전부 source 한정이라, 이 값 하나로 승인 미션이 시더 파이프라인과 완전히 절연된다.
	 * 값 상수를 러너가 아니라 등재 서비스가 갖는 것은 Mission.source 의 소유 규칙 그대로다.
	 */
	public static final String SOURCE_ORG_SUBMISSION = "ORG_SUBMISSION";

	/**
	 * 목표 칸수 1 (축제 시더 선례) — 관대함으로만 작용하고, 대표 격자를 갖는 행의 전제
	 * ({@code chk_missions_rep_grid_type})이기도 하다.
	 */
	private static final int TARGET_COUNT = 1;

	private final MissionRepository missionRepository;
	private final MissionGridRepository missionGridRepository;
	private final MissionQueryService missionQueryService;
	private final AwsProperties awsProperties;

	@Override
	@Transactional
	public long register(MissionRegistration registration) {
		Mission mission = missionRepository.save(Mission.builder()
			.type(registration.type())
			.title(registration.title())
			// KST 날짜 라벨 → UTC 순간. 축제 시더의 규칙을 그대로 부른다 — 두 벌로 복제하면 한쪽만 고쳐진다.
			.startAt(FestivalMissionSeeder.toUtcStart(registration.startsOn()))
			.endAt(FestivalMissionSeeder.toUtcEnd(registration.endsOn()))
			.targetCount(TARGET_COUNT)
			.source(SOURCE_ORG_SUBMISSION)
			.sourceKey(registration.sourceKey())
			.metadata(new MissionMetadata(registration.description(), null, null, registration.operationTime(),
				awsProperties.publicUrl(registration.imageKey()), null, null, null))
			.build());

		missionGridRepository.saveAll(registration.gridIds().stream()
			.map(gridId -> new MissionGrid(mission.getId(), gridId))
			.toList());
		// 대표 격자도 시더와 같은 규칙 하나를 쓴다 — 홀수 직사각형이면 정중앙, 아니면 중심 최근접 포함 격자다.
		// 판정 집합 소속은 지연 FK(fk_missions_rep_grid)가 커밋 시점에 다시 확인한다.
		MissionRepresentativeGrids.reassignIfOutside(mission, registration.gridIds());

		invalidateSnapshotAfterCommit();
		return mission.getId();
	}

	@Override
	@Transactional
	public void hide(long missionId, LocalDateTime now) {
		// 0행 = 이미 숨겨진 미션. 그래도 무효화는 건다 — 값이 없어도 비용이 다음 조회 한 번의 재계산뿐이고,
		// 실패로 보고할 사건이 아니다(중지의 목표 상태는 이미 성립해 있다).
		missionRepository.hide(missionId, now);
		invalidateSnapshotAfterCommit();
	}

	/**
	 * 스냅숏 무효화 예약 (D-12) — <b>커밋 후</b>다. 커밋 전에 비우면 재계산이 아직 안 보이는 이 미션을 놓친
	 * 스냅숏을 도로 채워, 무효화를 하고도 최대 1시간 안 실리는 결과가 그대로 남는다.
	 * 트랜잭션이 없으면(단위 테스트 등) 그냥 지금 실행한다 (EventSubmissionImageStore.afterCommit 패턴).
	 */
	private void invalidateSnapshotAfterCommit() {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			missionQueryService.invalidateSnapshot();
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				missionQueryService.invalidateSnapshot();
			}
		});
	}
}
