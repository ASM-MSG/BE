package com.msg.fillmap.mission.repository;

/**
 * findProgress 네이티브 조회 결과 프로젝션 (MSG-398 D8·D9). 미션당 한 행 — 진행도 조회가 응답에
 * 싣는 네 값만 노출한다 (CompletedMissionProjection 선례).
 */
public interface MissionProgressProjection {

	Long getMissionId();

	Integer getTargetCount();

	Integer getFilledCount();

	Boolean getCompleted();
}
