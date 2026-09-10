package com.msg.fillmap.video.repository;

/**
 * countVideosByMissionIds 네이티브 조회 결과 프로젝션 (MSG-459). 미션당 한 행 — 격자 역조회 응답의
 * videoCount 재료다(MissionVideoCountProjection 의 미션 단위 판박이). GROUP BY 결과라 videoCount 는
 * 항상 1 이상이고, 영상이 없는 미션은 행 자체가 없다 — 0 채움은 호출자 몫이다.
 */
public interface MissionTotalVideoCountProjection {

	Long getMissionId();

	Long getVideoCount();
}
