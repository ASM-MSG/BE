package com.msg.fillmap.region.service;

/**
 * 역지오코딩 결과 내부 뷰 (서비스 간 계약 — MSG-93). HTTP 응답 DTO(RegionResponseDto) 로의 변환은 컨트롤러가 한다.
 * 소비처: (1) RegionController, (2) MSG-66 업로드 라벨러(Owner B, 계약만 제공).
 */
public record RegionView(String regionCode, String regionName, String parentCode) {
}
