package com.msg.fillmap.event.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.event.dto.EventLocationVideoPageResponseDto;
import com.msg.fillmap.event.dto.EventVideoDetailResponseDto;
import com.msg.fillmap.event.dto.EventVideoUploadRequestDto;
import com.msg.fillmap.event.dto.EventVideoUploadResponseDto;
import com.msg.fillmap.event.service.EventVideoService;
import com.msg.fillmap.response.SuccessResponse;

/**
 * 행사 영상 API (MSG-440). 업로드(POST)는 로그인 필수, 피드와 상세(GET)는 비로그인 열람이다 — MSG-439 가
 * 확정한 "조회는 비로그인, 쓰기는 로그인" 경계의 연장이다. SecurityConfig 에 GET 한정 matcher 만 등록해
 * 같은 경로의 업로드 POST 가 함께 열리지 않게 한다.
 */
@Tag(name = "행사 (Events)", description = "행사 위치의 영상 업로드·피드·상세 API.")
@RestController
@RequiredArgsConstructor
public class EventVideoController {

	private final EventVideoService eventVideoService;

	@Operation(
		summary = "행사 영상 업로드 확정",
		description = "행사 위치에 영상을 올린다. 파일은 기존 presigned 발급(POST /api/videos/presigned-url)으로 "
			+ "S3 에 먼저 올리고 이 API 가 확정한다 — 촬영이든 갤러리 선택이든 서버 계약은 하나다.\n\n"
			+ "좌표를 받지 않는다. 격자는 서버가 그 위치의 대표 격자로 정하므로 현장에 없어도 올릴 수 있고, "
			+ "공개 범위는 PUBLIC 으로 고정된다. 업로드는 일반 업로드와 똑같이 그 격자의 점령을 만들고 뱃지·"
			+ "스트릭도 그대로 반영된다(미션만 연계되지 않는다).\n\n"
			+ "같은 s3Key 로 다시 보내면 영상이 하나 더 생기지 않고 저장된 행 기준의 성공이 돌아온다. 이때 "
			+ "occupied 는 false, newBadges 는 빈 배열이다(첫 응답 전용 필드).\n\n"
			+ "올릴 수 있는 기간은 행사 시작부터 종료 30일 후 직전까지다. 시작 전이면 409 + developCode "
			+ "13410, 마감 이후면 409 + 13409 다. 존재하지 않거나 아직 노출 기간 전인 회차는 404 + 13404, "
			+ "위치가 없거나 그 회차의 위치가 아니면 404 + 13405 다.\n\n"
			+ "인코딩이 끝나기 전에는 피드에 잡히지 않는다 — 업로드 직후 화면에 카드를 보여주려면 이 응답으로 "
			+ "낙관적으로 그린다(기존 업로드와 같은 성질)."
	)
	@PostMapping("/api/event-occurrences/{occurrenceId}/locations/{locationId}/videos")
	public SuccessResponse<EventVideoUploadResponseDto> upload(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "행사 회차 id", example = "12") @PathVariable long occurrenceId,
		@Parameter(description = "행사 위치 id", example = "34") @PathVariable long locationId,
		@Valid @RequestBody EventVideoUploadRequestDto request
	) {
		return SuccessResponse.of(
			eventVideoService.upload(principal.userId(), occurrenceId, locationId, request));
	}

	@Operation(
		summary = "위치별 영상 피드 조회",
		description = "행사 위치에 올라온 영상을 최신 업로드순으로 한 페이지 돌려준다. 영역 안 어느 격자를 "
			+ "눌러 들어와도 같은 위치의 같은 피드다.\n\n"
			+ "여기 담기는 영상은 위치 목록의 영상 수와 정확히 같은 집합이다 — 삭제·비공개·처리 미완료 영상은 "
			+ "숫자에서도 목록에서도 함께 빠진다. 인코딩이 끝나기 전 영상은 아직 담기지 않는다.\n\n"
			+ "cursor 는 직전 응답의 nextCursor 를 그대로 넣는다(첫 페이지는 생략). 형식이 깨졌거나 다른 위치 "
			+ "피드에서 받은 커서면 400 + developCode 13402 다. size 는 1~50 범위 밖이면 잘라서 적용하고 "
			+ "생략하면 20 이다.\n\n"
			+ "아카이브된 행사에서도 조회할 수 있고 영상이 없으면 실패가 아니라 빈 페이지다. 존재하지 않거나 "
			+ "노출 기간 전인 회차는 404 + 13404, 위치가 없거나 그 회차의 위치가 아니면 404 + 13405 다. "
			+ "비로그인으로도 조회할 수 있다."
	)
	@GetMapping("/api/event-occurrences/{occurrenceId}/locations/{locationId}/videos")
	public SuccessResponse<EventLocationVideoPageResponseDto> getLocationVideos(
		@Parameter(description = "행사 회차 id", example = "12") @PathVariable long occurrenceId,
		@Parameter(description = "행사 위치 id", example = "34") @PathVariable long locationId,
		@Parameter(description = "직전 응답의 nextCursor. 첫 페이지는 생략") @RequestParam(required = false) String cursor,
		@Parameter(description = "페이지 크기 (1~50, 기본 20)", example = "20")
		@RequestParam(required = false, defaultValue = "0") int size
	) {
		return SuccessResponse.of(eventVideoService.getLocationVideos(occurrenceId, locationId, cursor, size));
	}

	@Operation(
		summary = "행사 영상 상세 조회",
		description = "영상 하나의 재생본 presigned GET URL 과 표시 재료를 돌려준다. 소속 행사 회차·위치·대표 "
			+ "격자와 그 표시명 재료가 함께 담겨, 상세 화면이 추가 호출 없이 위치줄을 그린다.\n\n"
			+ "피드에 보이는 영상만 열린다 — 삭제·블라인드·비공개·처리 미완료 영상은 올린 본인에게도 404 + "
			+ "developCode 13406 이다(본인 영상 확인은 GET /api/videos/{videoId}). 행사 영상이 아닌 영상 id 도 "
			+ "같은 404 다.\n\n"
			+ "interactionLocked 는 아카이브 전환(행사 종료 + 30일)부터 true 이며 댓글·도움돼요 입력 UI 를 "
			+ "비활성화하는 재료다(기존 수는 계속 표시. 유예 기간에는 반응을 계속 남길 수 있다). 재생 URL 을 "
			+ "발급받은 타인 조회는 조회수를 올린다 — 비로그인 조회도 "
			+ "포함이고 올린 본인은 제외다."
	)
	@GetMapping("/api/event-videos/{videoId}")
	public SuccessResponse<EventVideoDetailResponseDto> getVideoDetail(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "영상 id", example = "1042") @PathVariable long videoId
	) {
		return SuccessResponse.of(eventVideoService.getVideoDetail(videoId, userId(principal)));
	}

	// 명시 HEAD 매핑 — 없으면 Spring 이 GET 핸들러로 폴백해 view_count 가 오른다(VideoController 선례).
	// 접근 제어 없이 200 만 반환한다: 전 id 동일 응답이라 존재 오라클이 아니다.
	@Hidden   // API 표면이 아닌 내부 심 — OpenAPI 스펙 노출 제외
	@RequestMapping(value = "/api/event-videos/{videoId}", method = RequestMethod.HEAD)
	public SuccessResponse<Void> headVideoDetail() {
		return new SuccessResponse<>(null);   // data 없는 성공
	}

	/** 비로그인 요청은 principal 이 없다 — 상세의 소유자 판정(조회수 제외) 재료라 null 을 그대로 넘긴다. */
	private Long userId(AuthPrincipal principal) {
		return principal == null ? null : principal.userId();
	}
}
