package com.msg.fillmap.friend.controller;

import java.util.List;
import java.util.Locale;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.friend.dto.FriendCodeResponseDto;
import com.msg.fillmap.friend.dto.FriendListItemResponseDto;
import com.msg.fillmap.friend.dto.FriendPreviewResponseDto;
import com.msg.fillmap.friend.dto.FriendProfileResponseDto;
import com.msg.fillmap.friend.dto.FriendRequestCreateRequestDto;
import com.msg.fillmap.friend.dto.FriendRequestCreateResponseDto;
import com.msg.fillmap.friend.dto.ReceivedFriendRequestResponseDto;
import com.msg.fillmap.friend.service.FriendService;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.grid.dto.OccupiedGridPageResponseDto;
import com.msg.fillmap.grid.dto.RegionAggregateResponseDto;
import com.msg.fillmap.grid.dto.RegionUnit;
import com.msg.fillmap.grid.dto.ViewportBounds;
import com.msg.fillmap.grid.exception.GridErrorCode;
import com.msg.fillmap.grid.service.OccupiedGridPage;
import com.msg.fillmap.grid.service.RegionAggregateView;
import com.msg.fillmap.response.SuccessResponse;
import com.msg.fillmap.video.dto.FriendGridVideoResponseDto;

@Tag(
	name = "친구 (Friend)",
	description = "고정 친구 코드 기반 친구 관계 API — 코드·요청·수락·거절·삭제 (MSG-185), "
		+ "친구 목록·친구 프로필 조회 (MSG-186), 친구 도감 레이어(격자 뷰포트·격자 영상 목록, MSG-187, "
		+ "축소 시야의 행정 단위 집계는 MSG-356). 인증 필수."
)
@RestController
@RequestMapping("/api/friends")
@RequiredArgsConstructor
public class FriendController {

	private final FriendService friendService;

	@Operation(
		summary = "내 친구 코드 조회",
		description = "가입 시 자동 부여된 고정 8자 코드를 반환한다. 상대에게 임의 채널(카톡 등)로 공유하면 "
			+ "상대가 이 코드로 친구 요청을 보낼 수 있다. 재발급 없음."
	)
	@GetMapping("/code")
	public SuccessResponse<FriendCodeResponseDto> getMyFriendCode(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal
	) {
		return SuccessResponse.of(friendService.getMyFriendCode(principal.userId()));
	}

	@Operation(
		summary = "친구 코드 미리보기",
		description = "요청을 보내기 전 확인 화면용 — 코드 소유자의 닉네임만 반환한다. "
			+ "요청 가능 여부 검증(자기 자신·중복 등)은 요청 API 가 수행한다."
	)
	@GetMapping("/preview")
	public SuccessResponse<FriendPreviewResponseDto> preview(@RequestParam("code") String code) {
		return SuccessResponse.of(friendService.preview(code));
	}

	@Operation(
		summary = "친구 요청",
		description = "상대의 친구 코드로 요청을 보낸다. 응답 status 가 PENDING 이면 상대 수락 대기, "
			+ "ACCEPTED 면 상대가 먼저 보낸 요청이 있어 즉시 친구 성립(자동 수락)이다."
	)
	@PostMapping("/requests")
	public SuccessResponse<FriendRequestCreateResponseDto> request(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Valid @RequestBody FriendRequestCreateRequestDto request
	) {
		return SuccessResponse.of(friendService.request(principal.userId(), request.friendCode()));
	}

	@Operation(
		summary = "친구 목록 조회",
		description = "수락된 친구 전체를 반환한다 — 누가 먼저 요청했는지와 무관하다. 기본 정렬은 친구가 된 "
			+ "시각 내림차순이고 sort=nickname 이면 닉네임순이다. 친구가 없으면 빈 배열."
	)
	@GetMapping
	public SuccessResponse<List<FriendListItemResponseDto>> getFriends(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "정렬 기준 — recent(기본, 친구가 된 시각 내림차순) 또는 nickname", example = "nickname")
		@RequestParam(required = false) String sort
	) {
		return SuccessResponse.of(friendService.getFriends(principal.userId(), sort));
	}

	@Operation(
		summary = "친구 프로필·도감 요약 조회",
		description = "친구의 프로필(닉네임·프로필 이미지·도감 색상)과 도감 요약(수집 격자 수·영상 총합·"
			+ "방문 동 수), 최근 수집 격자 최대 30개를 한 번에 반환한다. 도감 요약 수치는 그 친구가 자기 "
			+ "도감에서 보는 값과 같다. 썸네일은 그 격자에 재생 가능한 공개 영상이 있을 때만 붙는다. "
			+ "친구가 아닌 사용자·본인·존재하지 않는 사용자 조회는 모두 같은 404 다."
	)
	@GetMapping("/{userId}/profile")
	public SuccessResponse<FriendProfileResponseDto> getFriendProfile(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@PathVariable Long userId
	) {
		return SuccessResponse.of(friendService.getFriendProfile(principal.userId(), userId));
	}

	@Operation(
		summary = "친구 격자 뷰포트 조회",
		description = "지도 화면 bbox(남서~북동 좌표) 안에서 그 친구가 점령한 격자를 (grid_y, grid_x) 오름차순으로 "
			+ "반환한다. 응답 형상·검증 규칙·에러는 내 격자 조회(GET /api/grids)와 같다 — 응답의 nextCursor 를 "
			+ "다음 요청 cursor 에 넣어 이어 조회하고, bbox span 상한 0.5도는 위도·경도 각 변에 따로 적용되며 "
			+ "초과 시 400 + 4402(VIEWPORT_TOO_LARGE)로 거절된다. 격자 색상은 내려주지 "
			+ "않는다(FE 단일색 렌더). 친구가 아닌 사용자·본인·존재하지 않는 사용자 조회는 모두 같은 404 다."
	)
	@GetMapping("/{userId}/grids")
	public SuccessResponse<OccupiedGridPageResponseDto> getFriendGrids(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@PathVariable Long userId,
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
		OccupiedGridPage page = friendService.getFriendGrids(principal.userId(), userId, bounds, cursor, size);
		return SuccessResponse.of(OccupiedGridPageResponseDto.from(page));
	}

	@Operation(
		summary = "친구 격자 행정 단위 집계 조회 (줌아웃)",
		description = "지도를 축소한 시야에서 그 친구가 점령한 격자를 행정 단위로 묶어 센 목록을 페이지 없이 한 번에 "
			+ "반환한다. 파라미터·응답·에러가 내 집계 조회(GET /api/grids/aggregation)와 완전히 같다 — 단위 전환 "
			+ "시점은 서버가 정하지 않고 클라이언트가 화면 축척에 맞춰 unit 만 바꿔 부른다.\n\n"
			+ "항목마다 마커 식별 키(regionCode), 표시 이름, 대표 좌표, 격자 수가 온다. 행정동이 판정되지 않은 "
			+ "격자(해상 등)는 제외가 아니라 regionCode·name 이 null 인 항목 하나로 묶여 오고, 그 친구가 점령한 "
			+ "격자가 없으면 빈 배열이다.\n\n"
			+ "bbox span 상한은 단위별로 다르다(DONG 1도, SIGUNGU 4도, SIDO 10도 — 위도·경도 각 변에 따로 적용). "
			+ "초과 시 400 + developCode 4402, bbox 가 뒤집히거나 파라미터가 빠지면 4401, unit 이 없거나 미지원 "
			+ "값이면 4405 다. 친구가 아닌 사용자·본인·존재하지 않는 사용자 조회는 모두 같은 404 다."
	)
	@GetMapping("/{userId}/grids/aggregation")
	public SuccessResponse<List<RegionAggregateResponseDto>> getFriendGridAggregates(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@PathVariable Long userId,
		@Parameter(description = "남서 모서리 위도", required = true, example = "35.10")
		@RequestParam(required = false) Double swLat,
		@Parameter(description = "남서 모서리 경도", required = true, example = "128.90")
		@RequestParam(required = false) Double swLng,
		@Parameter(description = "북동 모서리 위도", required = true, example = "35.30")
		@RequestParam(required = false) Double neLat,
		@Parameter(description = "북동 모서리 경도", required = true, example = "129.20")
		@RequestParam(required = false) Double neLng,
		@Parameter(description = "집계 단위 — DONG(동), SIGUNGU(시군구), SIDO(시도). 대소문자 무관",
			required = true, example = "DONG")
		@RequestParam(required = false) String unit
	) {
		ViewportBounds bounds = toBounds(swLat, swLng, neLat, neLng);
		List<RegionAggregateView> items =
			friendService.getFriendGridAggregates(principal.userId(), userId, bounds, toUnit(unit));
		return SuccessResponse.of(items.stream().map(RegionAggregateResponseDto::from).toList());
	}

	@Operation(
		summary = "친구 격자 영상 목록 조회",
		description = "그 친구가 해당 격자에 올린 영상을 최근 업로드 순으로 반환한다. 친구에게 공개된 영상"
			+ "(전체 공개·친구만 보기)만 담기고 비공개 영상은 포함되지 않으며, 삭제·인코딩 미완 영상도 제외된다 "
			+ "— 목록의 영상은 모두 재생 조회로 바로 진입할 수 있다. 친구가 점령하지 않은 격자·존재하지 않는 "
			+ "gridId 는 빈 배열이다. 친구가 아닌 사용자·본인·존재하지 않는 사용자 조회는 모두 같은 404 다."
	)
	@GetMapping("/{userId}/grids/{gridId}/videos")
	public SuccessResponse<List<FriendGridVideoResponseDto>> getFriendGridVideos(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@PathVariable Long userId,
		@Parameter(description = "격자 ID", example = "19422_9582") @PathVariable String gridId
	) {
		return SuccessResponse.of(friendService.getFriendGridVideos(principal.userId(), userId, gridId));
	}

	@Operation(
		summary = "받은 친구 요청 목록",
		description = "내가 수신자인 대기 중 요청을 최신순으로 반환한다. 항목의 requesterId 를 "
			+ "수락/거절 경로 변수로 그대로 쓴다."
	)
	@GetMapping("/requests/received")
	public SuccessResponse<List<ReceivedFriendRequestResponseDto>> getReceivedRequests(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal
	) {
		return SuccessResponse.of(friendService.getReceivedRequests(principal.userId()));
	}

	@Operation(
		summary = "친구 요청 수락",
		description = "받은 요청을 수락해 친구 관계를 성립시킨다. 요청의 수신자 본인만 가능하다."
	)
	@PostMapping("/requests/{requesterId}/accept")
	public SuccessResponse<Void> accept(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@PathVariable Long requesterId
	) {
		friendService.accept(principal.userId(), requesterId);
		return new SuccessResponse<>(null);
	}

	@Operation(
		summary = "친구 요청 거절",
		description = "받은 요청을 거절한다. 보낸 쪽에 통지는 없고, 상대는 다시 요청할 수 있다."
	)
	@PostMapping("/requests/{requesterId}/reject")
	public SuccessResponse<Void> reject(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@PathVariable Long requesterId
	) {
		friendService.reject(principal.userId(), requesterId);
		return new SuccessResponse<>(null);
	}

	@Operation(
		summary = "친구 삭제",
		description = "친구 관계를 해소한다. 어느 쪽이든 삭제할 수 있고 즉시 양쪽 모두에서 사라진다. "
			+ "대기 중 요청은 대상이 아니다."
	)
	@DeleteMapping("/{userId}")
	public SuccessResponse<Void> deleteFriend(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@PathVariable Long userId
	) {
		friendService.deleteFriend(principal.userId(), userId);
		return new SuccessResponse<>(null);
	}

	/**
	 * bbox 4파라미터 누락은 4401 — GridController.toBounds 동형 사본이다 (MSG-187 D3-1). FE 가 내 격자 조회와
	 * 이 API 를 같은 에러 핸들링으로 다뤄야 해서(FR-10) 전역 400 이 아니라 격자 도메인 코드를 쓴다.
	 * 관계 정보를 누설하지 않으므로 친구 판정(9424)보다 앞서도 은닉이 깨지지 않는다.
	 */
	private ViewportBounds toBounds(Double swLat, Double swLng, Double neLat, Double neLng) {
		if (swLat == null || swLng == null || neLat == null || neLng == null) {
			throw new ApiException(GridErrorCode.INVALID_VIEWPORT);
		}
		return new ViewportBounds(swLat, swLng, neLat, neLng);
	}

	/** unit 누락·미지원 값은 4405 — GridController.toUnit 동형 사본이다 (toBounds 와 같은 이유·같은 규칙). */
	private RegionUnit toUnit(String unit) {
		if (unit == null) {
			throw new ApiException(GridErrorCode.INVALID_AGGREGATION_UNIT);
		}
		try {
			return RegionUnit.valueOf(unit.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new ApiException(GridErrorCode.INVALID_AGGREGATION_UNIT, e);
		}
	}
}
