package com.msg.fillmap.video.service;

import com.msg.fillmap.video.dto.PresignedUrlRequestDto;
import com.msg.fillmap.video.dto.PresignedUrlResponseDto;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.dto.VideoUploadResponseDto;

public interface VideoService {

	/**
	 * 업로드 완료 메타데이터 저장. 좌표 → 격자 인코딩 → grids lazy insert → videos INSERT → 점령 UPSERT.
	 */
	VideoUploadResponseDto saveVideo(long userId, VideoUploadRequestDto request);

	/**
	 * 클라이언트가 S3 에 직접 PUT 할 presigned URL 발급. 응답의 s3Key 는 이후 saveVideo 가 그대로 소비한다.
	 */
	PresignedUrlResponseDto issuePresignedUrl(long userId, PresignedUrlRequestDto request);
}
