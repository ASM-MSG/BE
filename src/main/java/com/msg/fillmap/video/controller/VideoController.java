package com.msg.fillmap.video.controller;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.response.SuccessResponse;
import com.msg.fillmap.video.dto.PresignedUrlRequestDto;
import com.msg.fillmap.video.dto.PresignedUrlResponseDto;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.dto.VideoUploadResponseDto;
import com.msg.fillmap.video.service.VideoService;

@RestController
@RequestMapping("/api/videos")
@RequiredArgsConstructor
public class VideoController {

	private final VideoService videoService;

	@PostMapping
	public SuccessResponse<VideoUploadResponseDto> upload(
		@AuthenticationPrincipal AuthPrincipal principal,
		@Valid @RequestBody VideoUploadRequestDto request
	) {
		return SuccessResponse.of(videoService.saveVideo(principal.userId(), request));
	}

	@PostMapping("/presigned-url")
	public SuccessResponse<PresignedUrlResponseDto> issuePresignedUrl(
		@AuthenticationPrincipal AuthPrincipal principal,
		@Valid @RequestBody PresignedUrlRequestDto request
	) {
		return SuccessResponse.of(videoService.issuePresignedUrl(principal.userId(), request));
	}

	@DeleteMapping("/{videoId}")
	public SuccessResponse<Void> delete(
		@AuthenticationPrincipal AuthPrincipal principal,
		@PathVariable Long videoId
	) {
		videoService.deleteVideo(principal.userId(), videoId);
		return new SuccessResponse<>(null);   // body 없는 성공 — AuthController.logout 과 같은 방식
	}
}
