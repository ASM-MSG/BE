package com.msg.fillmap.mission.entity;

/**
 * 미션 유형 5종 (missions.type, chk_missions_type). type 이 곧 렌더 shape 를 1:1 로 결정한다
 * (COURSE→PATH · AREA→REGION · EVENT→BOX · THEME/CONTINUOUS→CELLS, MSG-222 §유형→shape 매핑).
 */
public enum MissionType {

	COURSE,
	AREA,
	EVENT,
	THEME,
	CONTINUOUS
}
