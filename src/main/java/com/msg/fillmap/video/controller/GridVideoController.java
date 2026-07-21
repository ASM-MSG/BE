package com.msg.fillmap.video.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.response.SuccessResponse;
import com.msg.fillmap.video.dto.GridCoverVideoResponseDto;
import com.msg.fillmap.video.dto.GridVideoResponseDto;
import com.msg.fillmap.video.service.VideoService;

/**
 * 격자 상세 = 격자 하위 리소스(내 영상). 경로 접두사는 /api/grids 지만 videos 테이블을 다루는 Owner B
 * 코드라 video 패키지에 둔다 (grid 패키지 무수정 — MSG-127). "격자 탭 → 그 안의 영상" FE 멘탈모델과 일치.
 */
@Tag(name = "격자 상세 (Grid Videos)", description = "격자를 탭했을 때 그 격자에 내가 올린 영상 리스트 조회 API.")
@RestController
@RequiredArgsConstructor
public class GridVideoController {

	private final VideoService videoService;

	@Operation(
		summary = "격자별 내 영상 리스트 조회",
		description = "로그인 사용자가 해당 격자에 올린 본인 영상을 최근 업로드 순(createdAt DESC)으로 반환한다. "
			+ "미점령·타인만 점령한 격자·존재하지 않는 gridId 는 빈 배열이다. 썸네일은 presigned GET URL 로 내려주며 "
			+ "READY 이전이면 null 이다."
	)
	@GetMapping("/api/grids/{gridId}/videos")
	public SuccessResponse<List<GridVideoResponseDto>> getGridVideos(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "격자 ID", example = "41642_110458") @PathVariable String gridId
	) {
		return SuccessResponse.of(videoService.getGridVideos(principal.userId(), gridId));
	}

	@Operation(
		summary = "격자 전역 대표 영상 조회",
		description = "그 격자를 전역에서 대표하는 영상 1건을 반환한다. 공개(PUBLIC)·READY 영상 중 조회수(view_count) "
			+ "→ 최신(createdAt) 순으로 뽑으며, 본인·타인 영상 모두 후보다. 비공개·삭제·인코딩 미완 영상은 제외한다. "
			+ "후보가 없으면(미점령·비공개만·존재하지 않는 gridId) body 는 null 이다. 썸네일은 presigned GET URL 로 내려준다."
	)
	@GetMapping("/api/grids/{gridId}/cover")
	public SuccessResponse<GridCoverVideoResponseDto> getGridCover(
		@Parameter(description = "격자 ID", example = "41642_110458") @PathVariable String gridId
	) {
		return SuccessResponse.of(videoService.getGridCover(gridId));
	}
}
