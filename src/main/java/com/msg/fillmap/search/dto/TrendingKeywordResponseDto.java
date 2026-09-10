package com.msg.fillmap.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 인기 검색어 1건 (MSG-258). 검색 횟수·장소 정보는 담지 않는다 — 횟수 노출은 어뷰징 유도 우려로 제외(FR-4),
 * 장소는 클릭 후 기존 검색 API 실시간 호출로 얻는다(FR-8, 약관).
 */
@Schema(description = "인기 검색어 1건. 클릭 시 keyword 로 기존 장소 검색 API 를 다시 호출한다.",
	requiredProperties = {"rank", "keyword"})
public record TrendingKeywordResponseDto(
	@Schema(description = "순위 (1부터)", example = "1")
	int rank,

	@Schema(description = "정규화된 검색어", example = "홍대 카페")
	String keyword
) {
}
