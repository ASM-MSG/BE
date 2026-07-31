package com.msg.fillmap.mission.entity;

/**
 * 미션 유형 6종 (missions.type, chk_missions_type — V14 에서 POPUP 추가). type 이 곧 렌더 shape 를
 * 1:1 로 결정한다 (COURSE→PATH · AREA→REGION · EVENT/POPUP→BOX · THEME/CONTINUOUS→CELLS,
 * MSG-222 §유형→shape 매핑 + MSG-235 D5).
 */
public enum MissionType {

	COURSE,
	AREA,
	EVENT,
	THEME,
	CONTINUOUS,
	POPUP
}
