package com.msg.fillmap.video.service;

import com.msg.fillmap.video.dto.HighlightPreviewRequestDto;
import com.msg.fillmap.video.dto.HighlightPreviewResponseDto;

/**
 * 업로드 확정 전 하이라이트 선분석 (MSG-351). 읽기 전용·멱등 — pending 키를 클레임하지 않아
 * 같은 키로 이후 업로드 확정(POST /api/videos)이 가능하고, 재호출해도 부작용이 없다.
 */
public interface HighlightPreviewService {

	HighlightPreviewResponseDto analyze(long userId, HighlightPreviewRequestDto request);
}
