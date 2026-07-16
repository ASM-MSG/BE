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

	/**
	 * 본인 영상 soft delete + 점령 롤백. 그 격자의 내 영상이 모두 사라지면 도감에서 격자를 제거한다.
	 * 이미 삭제된 영상이면 멱등하게 성공 처리한다.
	 */
	void deleteVideo(long userId, long videoId);
}
