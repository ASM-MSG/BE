package com.msg.fillmap.event.submission.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.Valid;

/**
 * 신청 위치 하나 (MSG-498). <b>이름 필드가 없다</b> — 배열 순서가 곧 순번이고(서버가 1부터 매긴다) 화면
 * 식별은 순번과 지역 라벨로 한다 (피그마 #102). 영역이 비었거나 상한을 넘는 경우는 13431·13432 로
 * 도메인이 판정하므로 여기에 크기 제약을 걸지 않는다.
 */
@Schema(description = "신청 위치 — 영역 사각형 목록만 담는다 (이름 없음)")
public record EventSubmissionLocationRequestDto(
	@Schema(description = "영역 사각형 목록. 겹쳐도 되고 합집합 크기로 81칸 상한을 판정한다.")
	@Valid List<EventSubmissionAreaRectDto> areaRects
) {
}
