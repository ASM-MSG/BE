package com.msg.fillmap.video.service;

import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.dto.VideoUploadResponseDto;

public interface VideoService {

	/**
	 * 업로드 완료 메타데이터 저장. 좌표 → 격자 인코딩 → grids lazy insert → videos INSERT → 점령 UPSERT.
	 */
	VideoUploadResponseDto saveVideo(long userId, VideoUploadRequestDto request);
}
