package com.msg.fillmap.grid.controller;

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
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.dto.GridCellResponseDto;
import com.msg.fillmap.grid.dto.OccupiedGridPageResponseDto;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.grid.exception.GridErrorCode;
import com.msg.fillmap.grid.service.GridCellView;
import com.msg.fillmap.grid.service.GridQueryService;
import com.msg.fillmap.grid.service.OccupiedGridPage;
import com.msg.fillmap.response.SuccessResponse;

/**
 * 격자 색칠 조회 API (MSG-73 · MSG-90 페이지네이션). 3-layer 얇게 — 파싱 + 서비스 호출 + SuccessResponse 변환만.
 * userId 는 SecurityContext(AuthPrincipal)에서 획득한다(개인 도감).
 */
@Tag(
	name = "격자 (Grid)",
	description = "개인 도감 색칠 격자 조회 API — 로그인 사용자가 점령한 격자만 반환한다.\n\n"
		+ "격자는 EPSG:5179 미터 평면에서 100m 로 나눈 셀이다(2026-08-08 MSG-347 전까지는 위경도 등간격 근사였다). "
		+ "gridId 포맷 `\"{grid_y}_{grid_x}\"` 와 이 API 들의 요청·응답 구조는 그대로지만 **값은 전면 교체됐다** "
		+ "(같은 장소가 `41642_110458` 에서 `19422_9582` 로 바뀌었다). 예전 gridId 를 저장해 둔 클라이언트는 "
		+ "빈 결과를 받으므로 캐시를 비워야 한다.\n\n"
		+ "셀은 위경도 축과 평행하지 않다(자오선 수렴 최대 약 1.6도). 지도에 그릴 때 남서·북동 2점으로 만든 "
		+ "직사각형을 쓰면 어긋나므로 **꼭짓점 4점 폴리곤**으로 그린다. 화면에 보이는 격자 범위를 구할 때도 "
		+ "2점이 아니라 꼭짓점 4점의 min/max 를 써야 가장자리 셀이 빠지지 않는다.\n\n"
		+ "클라이언트가 같은 격자를 계산하려면 서버와 **글자 단위로 같은 proj4 정의**를 써야 한다: "
		+ "`+proj=tmerc +lat_0=38 +lon_0=127.5 +k=0.9996 +x_0=1000000 +y_0=2000000 +ellps=GRS80 "
		+ "+towgs84=0,0,0,0,0,0,0 +units=m +no_defs`. "
		+ "대조용 전국 샘플 200건은 서버 레포 `src/test/resources/fixtures/grid-epsg5179-samples.json` 에 있다."
)
@RestController
@RequestMapping("/api/grids")
@RequiredArgsConstructor
public class GridController {

	private final GridQueryService gridQueryService;

	@Operation(
		summary = "단일 격자 색칠 상태 조회",
		description = "특정 격자를 내가 점령(색칠)했는지와 내 영상 수를 반환한다. "
			+ "미점령 격자도 404가 아니라 occupied=false로 응답한다.\n\n"
			+ "표시 이름 재료가 함께 온다: zoneName이 null이면 regionName(행정동)이 표시 이름이다"
			+ "(폴백에는 칸 번호를 붙이지 않는다). regionName은 아직 아무도 영상을 올리지 않은 격자에도 실리고, "
			+ "어느 행정동에도 속하지 않거나 서비스 범위(한국) 밖인 격자면 null이다(에러가 아니다)."
	)
	@GetMapping("/{gridId}")
	public SuccessResponse<GridCellResponseDto> getCell(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "격자 ID (\"{grid_y}_{grid_x}\" 포맷)", example = "19422_9582")
		@PathVariable String gridId
	) {
		GridCellView view = gridQueryService.getCell(principal.userId(), gridId);
		return SuccessResponse.of(GridCellResponseDto.from(view));
	}

	@Operation(
		summary = "뷰포트 내 색칠 격자 조회 (커서 페이지네이션)",
		description = "지도 화면 bbox(남서~북동 좌표) 안에서 내가 점령한 격자를 (grid_y, grid_x) 오름차순으로 반환한다. "
			+ "응답의 nextCursor를 다음 요청 cursor에 넣어 이어서 조회한다. bbox span 상한은 0.5도로 위도·경도 "
			+ "각 변에 따로 적용된다(정확히 0.5도는 허용). 초과 시 잘라서 응답하지 않고 400 + developCode "
			+ "4402(VIEWPORT_TOO_LARGE)로 거절한다.\n\n"
			+ "항목마다 표시 이름 재료가 함께 온다: zoneName이 null이면 regionName(행정동)이 표시 이름이다"
			+ "(폴백에는 칸 번호를 붙이지 않는다). 이름 때문에 다른 API를 더 호출할 필요가 없다."
	)
	@GetMapping
	public SuccessResponse<OccupiedGridPageResponseDto> getOccupiedInViewport(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "남서 모서리 위도", required = true, example = "37.50")
		@RequestParam(required = false) Double swLat,
		@Parameter(description = "남서 모서리 경도", required = true, example = "127.00")
		@RequestParam(required = false) Double swLng,
		@Parameter(description = "북동 모서리 위도", required = true, example = "37.55")
		@RequestParam(required = false) Double neLat,
		@Parameter(description = "북동 모서리 경도", required = true, example = "127.05")
		@RequestParam(required = false) Double neLng,
		@Parameter(description = "다음 페이지 커서 (직전 응답의 nextCursor). 첫 페이지는 생략", example = "MTk0MjJfOTU4Mg==")
		@RequestParam(required = false) String cursor,
		@Parameter(description = "페이지 크기 (기본 1000, 최대 5000)", example = "1000")
		@RequestParam(required = false, defaultValue = "1000") int size
	) {
		ViewportBounds bounds = toBounds(swLat, swLng, neLat, neLng);
		OccupiedGridPage page = gridQueryService.getOccupiedInViewport(principal.userId(), bounds, cursor, size);
		return SuccessResponse.of(OccupiedGridPageResponseDto.from(page));
	}

	private ViewportBounds toBounds(Double swLat, Double swLng, Double neLat, Double neLng) {
		if (swLat == null || swLng == null || neLat == null || neLng == null) {
			throw new ApiException(GridErrorCode.INVALID_VIEWPORT);
		}
		return new ViewportBounds(swLat, swLng, neLat, neLng);
	}
}
