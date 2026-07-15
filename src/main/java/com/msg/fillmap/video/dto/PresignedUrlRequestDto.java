package com.msg.fillmap.video.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PresignedUrlRequestDto(
	@NotBlank String extension,
	@NotBlank String contentType,
	@NotNull @Positive Long contentLength
) {
}
