package com.msg.fillmap.usergrid.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.response.SuccessResponse;
import com.msg.fillmap.usergrid.dto.CollectionSummaryResponseDto;
import com.msg.fillmap.usergrid.service.CollectionSummaryView;
import com.msg.fillmap.usergrid.service.UserGridQueryService;

/**
 * 개인 도감 요약 API (MSG-152). 3-layer 얇게 — principal 에서 userId 획득 + 서비스 호출 + SuccessResponse 변환만.
 * 구현 패키지는 usergrid, 사용자 대면 리소스는 collections(GridController 의 impl↔resource 분리와 동일).
 */
@Tag(name = "도감 (Collection)", description = "개인 도감 요약 조회 API — 로그인 사용자의 점령·영상·방문 행정동 집계.")
@RestController
@RequestMapping("/api/collections")
@RequiredArgsConstructor
public class CollectionController {

	private final UserGridQueryService userGridQueryService;

	@Operation(
		summary = "개인 도감 요약 조회",
		description = "로그인 사용자의 점령한 격자 수·올린 영상 총합·방문한 행정동 수를 한 번에 반환한다. "
			+ "점령 0건 사용자도 에러 없이 세 값이 모두 0으로 응답한다."
	)
	@GetMapping("/summary")
	public SuccessResponse<CollectionSummaryResponseDto> getSummary(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal
	) {
		CollectionSummaryView view = userGridQueryService.getCollectionSummary(principal.userId());
		return SuccessResponse.of(CollectionSummaryResponseDto.from(view));
	}
}
