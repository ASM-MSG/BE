package com.msg.fillmap.video.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.response.SuccessResponse;
import com.msg.fillmap.video.dto.PresignedUrlRequestDto;
import com.msg.fillmap.video.dto.PresignedUrlResponseDto;
import com.msg.fillmap.video.dto.VideoReplaceRequestDto;
import com.msg.fillmap.video.dto.VideoReplaceResponseDto;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.dto.VideoUploadResponseDto;
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
		summary = "영상 삭제",
		description = "영상을 삭제한다. 해당 격자의 내 영상이 모두 사라지면 점령이 롤백(색칠 해제)된다."
	)
	@DeleteMapping("/{videoId}")
	public SuccessResponse<Void> delete(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "삭제할 영상 ID", example = "1001") @PathVariable Long videoId
	) {
		videoService.deleteVideo(principal.userId(), videoId);
		return new SuccessResponse<>(null);   // body 없는 성공 — AuthController.logout 과 같은 방식
	}
}
