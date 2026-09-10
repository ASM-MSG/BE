package com.msg.fillmap.video.repository;

/**
 * countMissionVideosByGrid 네이티브 조회 결과 프로젝션 (MSG-399). 격자당 한 행 — gridId 와 그 격자의
 * 게이트 통과 영상 수만 노출한다 (HourlyUploadProjection 선례). GROUP BY 결과라 videoCount 는 항상
 * 1 이상이고, 영상이 없는 격자는 행 자체가 없다 — 0 채움은 호출자(MissionQueryServiceImpl) 몫이다.
 */
public interface MissionVideoCountProjection {

	String getGridId();

	Long getVideoCount();
}
