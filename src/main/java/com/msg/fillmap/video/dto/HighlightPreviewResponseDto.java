package com.msg.fillmap.video.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "하이라이트 선분석 응답 (MSG-351). 결과는 저장되지 않는 임시 값이다 — 확정본의 하이라이트는 "
	+ "업로드 확정 후 블러 파이프라인이 따로 계산한다.", requiredProperties = {"highlights"})
public record HighlightPreviewResponseDto(
	// MSG-350 의 null 통일과 다르게 빈 배열을 그대로 내린다 — 성공 응답이 곧 "분석이 돌았다"는 뜻이라
	// [] 가 "추천 없음, FE 스킵"(FR-4)이라는 유효한 정보다.
	@Schema(description = "[[시작초, 끝초], ...] 최대 3구간, 초는 소수점 둘째 자리. 배열 순서가 추천 우선순위(첫 요소가 "
		+ "최우선)다. 각 구간은 5초 이상이고 시작점끼리 5초 이상 벌어진다. 5초 미만 원본이거나 조건을 채우는 구간이 "
		+ "없으면 빈 배열 [] — 추천 없음이니 FE 는 추천 단계를 스킵한다",
		example = "[[0.0, 5.12], [10.0, 16.4]]")
	List<List<Double>> highlights
) {
}
