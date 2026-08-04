package com.msg.fillmap.video.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.response.SuccessResponse;
import com.msg.fillmap.video.dto.PresignedUrlRequestDto;
import com.msg.fillmap.video.dto.PresignedUrlResponseDto;
import com.msg.fillmap.video.dto.VideoPlaybackResponseDto;
import com.msg.fillmap.video.dto.VideoReplaceRequestDto;
import com.msg.fillmap.video.dto.VideoReplaceResponseDto;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.dto.VideoUploadResponseDto;
import com.msg.fillmap.video.dto.VideoVisibilityRequestDto;
import com.msg.fillmap.video.dto.VideoVisibilityResponseDto;
import com.msg.fillmap.video.service.VideoService;

@Tag(name = "영상 (Video)", description = "영상 업로드·교체·삭제 API. 업로드는 presigned URL 발급 → S3 직접 업로드 → 메타데이터 저장 순서다.")
@RestController
@RequestMapping("/api/videos")
@RequiredArgsConstructor
public class VideoController {

	private final VideoService videoService;

	@Operation(
		summary = "영상 메타데이터 저장 (업로드 확정)",
		description = "S3 업로드 완료 후 영상 메타데이터를 저장하고 좌표로 격자를 매핑한다. "
			+ "해당 격자에 내 첫 영상이면 점령(occupied=true)된다."
	)
	@PostMapping
	public SuccessResponse<VideoUploadResponseDto> upload(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Valid @RequestBody VideoUploadRequestDto request
	) {
		return SuccessResponse.of(videoService.saveVideo(principal.userId(), request));
	}

	@Operation(
		summary = "업로드용 presigned URL 발급",
		description = "영상 파일을 S3에 직접 올릴 presigned URL을 발급한다. 이 URL로 PUT 업로드한 뒤 메타데이터 저장을 호출한다."
	)
	@PostMapping("/presigned-url")
	public SuccessResponse<PresignedUrlResponseDto> issuePresignedUrl(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Valid @RequestBody PresignedUrlRequestDto request
	) {
		return SuccessResponse.of(videoService.issuePresignedUrl(principal.userId(), request));
	}

	@Operation(
		summary = "단건 영상 재생 조회",
		description = "영상 하나의 표시용 메타와 재생본 presigned GET URL을 발급한다. 소유자·타인 모두 조회할 수 있으나 "
			+ "삭제·블라인드(타인)는 404, 비공개(타인)·친구만 공개(비친구)는 403이다. READY가 아니면 playbackUrl은 null이다."
	)
	@GetMapping("/{videoId}")
	public SuccessResponse<VideoPlaybackResponseDto> getPlayback(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "재생할 영상 ID", example = "1042") @PathVariable Long videoId
	) {
		return SuccessResponse.of(videoService.getVideoPlayback(principal.userId(), videoId));
	}

	// 명시 HEAD 매핑 — 없으면 Spring이 GET 핸들러로 폴백해 view_count가 오른다(Codex R2).
	// 접근 제어 없이 200만 반환(전 id 동일 응답이라 존재 오라클 아님).
	// 브라우저 cross-origin HEAD는 CORS allowedMethods에 HEAD가 없어 선차단된다 — 의도된 이중 방어,
	// FE에 HEAD 사용처 없음(Codex R3 수용).
	@Hidden // API 표면이 아닌 내부 심 — OpenAPI 스펙 노출 제외 (MSG-208)
	@RequestMapping(value = "/{videoId}", method = RequestMethod.HEAD)
	public SuccessResponse<Void> headPlayback() {
		return new SuccessResponse<>(null);   // data 없는 성공 — delete 핸들러와 같은 방식
	}

	@Operation(
		summary = "영상 교체",
		description = "기존 영상을 새 파일로 교체한다. 좌표를 생략하면 격자를 유지하고 파일만 교체하며, "
			+ "좌표를 보내면 기존과 같은 격자여야 한다(다르면 거부). 교체 직후 상태는 UPLOADED다."
	)
	@PutMapping("/{videoId}")
	public SuccessResponse<VideoReplaceResponseDto> replace(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "교체할 영상 ID", example = "1001") @PathVariable Long videoId,
		@Valid @RequestBody VideoReplaceRequestDto request
	) {
		return SuccessResponse.of(videoService.replaceVideo(principal.userId(), videoId, request));
	}

	@Operation(
		summary = "영상 공개 범위 전환",
		description = "본인 영상의 공개 범위를 PUBLIC·PRIVATE·FRIENDS 간 전환한다. 전환된 상태를 반환하며, "
			+ "같은 값 재전환은 멱등하게 성공한다."
	)
	@PatchMapping("/{videoId}/visibility")
	public SuccessResponse<VideoVisibilityResponseDto> setVisibility(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "공개 범위를 전환할 영상 ID", example = "1042") @PathVariable Long videoId,
		@Valid @RequestBody VideoVisibilityRequestDto request
	) {
		return SuccessResponse.of(videoService.setVisibility(principal.userId(), videoId, request));
	}

	@Operation(
		summary = "영상 삭제",
		description = "영상을 삭제한다. 해당 격자의 내 영상이 모두 사라지면 점령이 롤백(색칠 해제)된다."
	)
	@DeleteMapping("/{videoId}")
	public SuccessResponse<Void> delete(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "삭제할 영상 ID", example = "1001") @PathVariable Long videoId
	) {
		videoService.deleteVideo(principal.userId(), videoId);
		return new SuccessResponse<>(null);   // data 없는 성공 — AuthController.logout 과 같은 방식
	}
}
