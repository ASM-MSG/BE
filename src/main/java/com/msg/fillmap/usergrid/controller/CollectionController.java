package com.msg.fillmap.usergrid.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.response.SuccessResponse;
import com.msg.fillmap.usergrid.dto.CollectionGridPageResponseDto;
import com.msg.fillmap.usergrid.dto.CollectionGridResponseDto;
import com.msg.fillmap.usergrid.dto.CollectionGridSort;
import com.msg.fillmap.usergrid.dto.CollectionSummaryResponseDto;
import com.msg.fillmap.usergrid.dto.RegionVideoResponseDto;
import com.msg.fillmap.usergrid.dto.UploadHistoryResponseDto;
import com.msg.fillmap.usergrid.service.CollectionGridPage;
import com.msg.fillmap.usergrid.service.CollectionGridView;
import com.msg.fillmap.usergrid.service.CollectionSummaryView;
import com.msg.fillmap.usergrid.service.RegionVideoView;
import com.msg.fillmap.usergrid.service.UploadHistoryView;
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
		description = "로그인 사용자의 점령한 격자 수·올린 영상 총합·방문한 행정동 수에 더해 현재 스트릭·"
			+ "최장 스트릭·획득 뱃지 수를 한 번에 반환한다. 현재 스트릭은 마지막 기록이 KST 그제 이전이면 0이다. "
			+ "업로드 경험 0 사용자도 에러 없이 여섯 값이 모두 0으로 응답한다."
	)
	@GetMapping("/summary")
	public SuccessResponse<CollectionSummaryResponseDto> getSummary(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal
	) {
		CollectionSummaryView view = userGridQueryService.getCollectionSummary(principal.userId());
		return SuccessResponse.of(CollectionSummaryResponseDto.from(view));
	}

	@Operation(
		summary = "갤러리 격자 목록 조회",
		description = "로그인 사용자가 수집한 격자를 카드로 반환한다(무커서). 파라미터를 모두 생략하면 전국을 "
			+ "first_collected_at 내림차순 최대 30개로 준다(기존 계약). regionCode 를 주면 그 행정동에 속한 내 격자만 "
			+ "나가며, 귀속은 격자 축이라 영상 좌표가 옆 동이어도 격자 소속 행정동 기준으로 잡힌다. "
			+ "각 항목은 gridId·gridY/gridX·수집/방문 시각·영상 수·cover 영상 ID·cover 썸네일 URL·cover 길이(초)를 담는다. "
			+ "내 격자가 없거나 미존재 regionCode 면 에러 없이 빈 배열을 받는다."
	)
	@GetMapping("/grids")
	public SuccessResponse<List<CollectionGridResponseDto>> getCollectionGrids(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "행정동 코드 — 생략하면 전국. by-grid 응답의 regionCode 를 그대로 전달",
			example = "1168051500")
		@RequestParam(required = false) String regionCode,
		@Parameter(description = "정렬 축 — COLLECTED(수집 시각순, 기본) 또는 UPLOADED(최신 업로드순)")
		@RequestParam(defaultValue = "COLLECTED") CollectionGridSort sort,
		@Parameter(description = "카드 수 상한. 생략하면 전국은 30, 행정동은 20. 1 미만은 1로 보정",
			example = "20")
		@RequestParam(required = false) Integer limit
	) {
		List<CollectionGridView> views =
			userGridQueryService.getCollectionGrids(principal.userId(), regionCode, sort, limit);
		return SuccessResponse.of(views.stream().map(CollectionGridResponseDto::from).toList());
	}

	@Operation(
		summary = "행정동 전체 보기 개인 격자 페이지 조회",
		description = "로그인 사용자의 해당 행정동 격자를 최근 업로드 시각, 영상 수, 격자 ID "
			+ "내림차순으로 최대 20개씩 반환한다. 첫 요청은 cursor를 생략하고, hasNext가 true면 "
			+ "nextCursor를 다음 요청에 "
			+ "그대로 넣는다. 다른 행정동에서 발급된 커서와 깨진 커서는 400이다."
	)
	@GetMapping("/regions/{regionCode}/grids")
	public SuccessResponse<CollectionGridPageResponseDto> getCollectionGridPage(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "행정동 코드", example = "1168051500") @PathVariable String regionCode,
		@Parameter(description = "직전 응답의 nextCursor. 첫 페이지는 생략")
		@RequestParam(required = false) String cursor
	) {
		CollectionGridPage page = userGridQueryService.getCollectionGridPage(principal.userId(), regionCode, cursor);
		return SuccessResponse.of(CollectionGridPageResponseDto.from(page));
	}

	@Operation(
		summary = "동 단위 내 영상 조회",
		description = "행정동(regionCode) 격자들에 올린 로그인 사용자의 영상을 created_at 내림차순으로 반환한다(무커서). "
			+ "regionCode 는 by-grid 응답의 regionCode 를 그대로 넘긴다. 귀속은 격자 축이라 영상 좌표가 옆 동이어도 "
			+ "격자 소속 행정동 기준으로 포함된다. 내 도감이라 PRIVATE·인코딩 중 영상도 포함하며(status ACTIVE 만), "
			+ "그 행정동에 내 영상이 없거나 미존재 regionCode 면 에러 없이 빈 배열을 받는다."
	)
	@GetMapping("/videos")
	public SuccessResponse<List<RegionVideoResponseDto>> getRegionVideos(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "행정동 코드 — by-grid 응답의 regionCode 를 그대로 전달", example = "1168051500")
		@RequestParam String regionCode
	) {
		List<RegionVideoView> views = userGridQueryService.getRegionVideos(principal.userId(), regionCode);
		return SuccessResponse.of(views.stream().map(RegionVideoResponseDto::from).toList());
	}

	@Operation(
		summary = "날짜별 업로드 기록 조회",
		description = "로그인 사용자 본인의 업로드를 KST 날짜로 접어, 업로드가 있었던 날과 그날의 건수를 "
			+ "날짜 오름차순으로 반환한다(잔디 재료 — 빈 날은 항목 없음, 빈 칸 채우기는 FE 몫). "
			+ "삭제·블라인드된 영상의 업로드도 센다. 업로드 0건 사용자는 에러 없이 빈 배열을 받는다."
	)
	@GetMapping("/upload-history")
	public SuccessResponse<List<UploadHistoryResponseDto>> getUploadHistory(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal
	) {
		List<UploadHistoryView> views = userGridQueryService.getUploadHistory(principal.userId());
		return SuccessResponse.of(views.stream().map(UploadHistoryResponseDto::from).toList());
	}
}
