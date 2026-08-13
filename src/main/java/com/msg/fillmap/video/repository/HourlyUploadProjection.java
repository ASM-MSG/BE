package com.msg.fillmap.video.repository;

/**
 * 격자 전역 시간대 집계 프로젝션 (MSG-372). 네이티브 쿼리는 컬럼 별칭을 hour/count 로 맞춘다.
 * hour 는 업로드 시각(created_at)을 KST 로 변환해 뽑은 시(0~23), count 는 그 시간대의 게이트 통과
 * 영상 수(GROUP BY 결과라 항상 1 이상)다. 업로드가 없는 시간대는 행 자체가 없고, 24구간 채움은
 * 호출자(VideoServiceImpl)가 한다.
 */
public interface HourlyUploadProjection {

	Integer getHour();

	Long getCount();
}
