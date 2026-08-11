package com.msg.fillmap.usergrid.repository;

import java.time.LocalDate;

/**
 * 날짜별 업로드 기록 프로젝션 (MSG-362). 네이티브 쿼리는 컬럼 별칭을 uploadDate/uploadCount 로 맞춘다.
 * uploadDate 는 KST 로 접은 날짜, uploadCount 는 그날 업로드한 영상 수(GROUP BY 결과라 항상 1 이상).
 */
public interface UploadHistoryProjection {

	LocalDate getUploadDate();

	Integer getUploadCount();
}
