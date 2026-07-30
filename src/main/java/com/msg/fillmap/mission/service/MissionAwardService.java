package com.msg.fillmap.mission.service;

import com.msg.fillmap.mission.dto.MissionAwardResult;

/**
 * 미션 완료 판정·스탬프 발급 엔진 (MSG-223). B 내부 서비스 — 계약 인터페이스 아님
 * (BadgeAwardService 와 같은 지위, video→mission 호출은 Owner B 도메인 내부).
 */
public interface MissionAwardService {

	/**
	 * 업로드 확정 이벤트의 미션 판정 (§D2). 업로드 격자를 대상으로 하는 활성·미발급 미션을 역조회하고,
	 * 빈 결과(대부분의 업로드)면 판정 쿼리 없이 조기 종료한다(FR-18). 충족 미션은 스탬프를 발급하되
	 * INSERT 영향 행 1인 것만 결과에 담고(FR-14), 새 스탬프가 있을 때만 MISSION_COUNT 뱃지를
	 * 판정한다(FR-17). 업로드 트랜잭션에 참여한다 — 판정 실패 시 업로드째 롤백(badge·streak 훅 동일 정책).
	 *
	 * @return 이번 업로드로 완료된 미션·획득 뱃지 쌍 (없으면 EMPTY — 지배적 경로)
	 */
	MissionAwardResult awardOnUpload(long userId, String gridId);
}
