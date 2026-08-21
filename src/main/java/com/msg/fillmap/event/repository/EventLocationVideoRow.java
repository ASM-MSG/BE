package com.msg.fillmap.event.repository;

import java.time.LocalDateTime;

/**
 * 위치별 영상 피드 한 줄 (MSG-440 API 2). 카드에 필요한 네 값만 뽑는 JPQL 생성자 프로젝션이라 Video 엔티티
 * 전체(geom 포함)를 영속성 컨텍스트에 올리지 않는다. thumbnailKey 는 S3 키 그대로이고, presigned GET URL
 * 변환은 서비스 몫이다 — 리포지토리는 서명 같은 외부 관심사를 갖지 않는다.
 */
public record EventLocationVideoRow(Long videoId, String thumbnailKey, Short durationSec, LocalDateTime createdAt) {
}
