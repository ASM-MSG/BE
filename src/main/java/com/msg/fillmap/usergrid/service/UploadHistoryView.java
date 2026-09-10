package com.msg.fillmap.usergrid.service;

import java.time.LocalDate;

/**
 * 날짜별 업로드 기록 내부 뷰 (MSG-362). uploadDate 는 KST 로 접은 날짜, uploadCount 는 그날 업로드한
 * 영상 수(항상 1 이상 — 업로드 없는 날은 항목 자체가 없다). HTTP 응답 DTO 로의 변환은 컨트롤러가 한다.
 */
public record UploadHistoryView(LocalDate uploadDate, int uploadCount) {
}
