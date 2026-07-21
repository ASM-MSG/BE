package com.msg.fillmap.video.service;

import java.util.List;

import com.msg.fillmap.video.dto.GridVideoResponseDto;
import com.msg.fillmap.video.dto.PresignedUrlRequestDto;
import com.msg.fillmap.video.dto.PresignedUrlResponseDto;
import com.msg.fillmap.video.dto.VideoReplaceRequestDto;
import com.msg.fillmap.video.dto.VideoReplaceResponseDto;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.dto.VideoUploadResponseDto;

public interface VideoService {

	/**
	 * 격자별 내 영상 리스트 조회 (MSG-127). 로그인 사용자가 그 격자에 올린 ACTIVE 영상만 created_at DESC 로
	 * 돌려준다. 썸네일 S3 key 는 presigned GET URL 로 변환하고, READY 이전이면 null 이다. 미점령·타인 격자·
	 * 존재하지 않는 gridId 는 빈 리스트다(예외 아님).
	 */
	List<GridVideoResponseDto> getGridVideos(long userId, String gridId);

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

	/**
	 * 본인 영상의 파일 교체. row 를 유지한 채 파일 참조만 갈아끼우므로 도감(점령·video_count·cover)은
	 * 변하지 않는다. 같은 격자 안에서만 가능하고, 교체 후 재인코딩이 돈다.
	 */
	VideoReplaceResponseDto replaceVideo(long userId, long videoId, VideoReplaceRequestDto request);
}
