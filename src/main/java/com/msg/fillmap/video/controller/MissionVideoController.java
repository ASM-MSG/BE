package com.msg.fillmap.video.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.response.SuccessResponse;
import com.msg.fillmap.video.dto.GridVideoPageResponseDto;
import com.msg.fillmap.video.service.VideoService;

/**
 * 미션 상세 = 미션 하위 리소스(그 미션의 영상). 경로 접두사는 /api/missions 지만 videos 테이블을 다루는
 * Owner B 코드라 video 패키지에 둔다 (mission 패키지 무수정 — GridVideoController/MSG-127 과 같은 배치).
 * 격자 하위 리소스로 선언된 GridVideoController 에 얹지 않는 것은 태그·문서가 어긋나기 때문이다.
 */
@Tag(name = "미션 영상 (Mission Videos)",
	description = "미션 상세 하단 \"이 미션의 영상\" 목록 API — 그 미션의 대상 격자에서 미션 기간에 촬영된 공개 영상.")
@RestController
@RequiredArgsConstructor
public class MissionVideoController {

	private final VideoService videoService;

	@Operation(
		summary = "미션 영상 목록 조회",
		description = "그 미션의 대상 격자에서 미션 기간에 촬영된 공개(PUBLIC)·READY 영상을 촬영 시각(recordedAt) "
			+ "최신순으로 페이지 조회한다 — 촬영 시각이 같으면 videoId 내림차순으로 갈린다. 기간이 없는 미션"
			+ "(코스·지속형)은 기간 조건 없이 과거 영상까지 담고, 기간이 끝난 미션도 목록은 그대로 조회된다. "
			+ "비공개·친구 공개·삭제·블라인드·인코딩 미완 영상은 본인 것이라도 제외되며, 응답은 누가 부르든 같다. "
			+ "첫 요청은 cursor 없이 부르고, hasNext 가 true 면 응답의 nextCursor 를 다음 요청 cursor 로 넘기면 "
			+ "이어진다. 커서는 발급된 그 미션 전용이라 다른 미션 커서는 400(INVALID_CURSOR)이고, 형식이 깨진 "
			+ "커서도 같다. size 는 1~50 밖이면 클램프된다. 조건에 맞는 영상이 없거나 존재하지 않는 missionId 는 "
			+ "빈 페이지다. 썸네일은 presigned GET URL 로 내려준다."
	)
	@GetMapping("/api/missions/{missionId}/videos")
	public SuccessResponse<GridVideoPageResponseDto> getMissionVideos(
		@Parameter(description = "미션 ID", example = "12") @PathVariable long missionId,
		@Parameter(description = "직전 응답의 nextCursor (opaque). 생략하면 첫 페이지")
		@RequestParam(required = false) String cursor,
		@Parameter(description = "페이지 크기 (1~50, 기본 20)") @RequestParam(defaultValue = "20") int size
	) {
		return SuccessResponse.of(videoService.getMissionVideos(missionId, cursor, size));
	}
}
