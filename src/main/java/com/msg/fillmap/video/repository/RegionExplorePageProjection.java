package com.msg.fillmap.video.repository;

import java.time.LocalDateTime;

/** 전체 지역 개인화 페이지 쿼리의 행 하나. */
public interface RegionExplorePageProjection extends RegionGridCountProjection {

	LocalDateTime getPersonalLastUploadedAt();
}
