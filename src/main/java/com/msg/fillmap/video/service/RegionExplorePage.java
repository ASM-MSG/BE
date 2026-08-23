package com.msg.fillmap.video.service;

import java.util.List;

import com.msg.fillmap.video.dto.RegionGridCountResponseDto;

/** 검색 무입력 전체 지역 목록의 커서 페이지. */
public record RegionExplorePage(
	List<RegionGridCountResponseDto> items,
	boolean hasNext,
	String nextCursor
) {
}
