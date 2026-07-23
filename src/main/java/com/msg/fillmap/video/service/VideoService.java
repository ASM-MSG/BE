package com.msg.fillmap.video.service;

import java.util.List;

import com.msg.fillmap.video.dto.GridCoverVideoResponseDto;
import com.msg.fillmap.video.dto.GridVideoResponseDto;
import com.msg.fillmap.video.dto.PresignedUrlRequestDto;
import com.msg.fillmap.video.dto.PresignedUrlResponseDto;
import com.msg.fillmap.video.dto.VideoPlaybackResponseDto;
import com.msg.fillmap.video.dto.VideoReplaceRequestDto;
import com.msg.fillmap.video.dto.VideoReplaceResponseDto;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.dto.VideoUploadResponseDto;
import com.msg.fillmap.video.dto.VideoVisibilityRequestDto;
import com.msg.fillmap.video.dto.VideoVisibilityResponseDto;

public interface VideoService {

	/**
	 * 격자별 내 영상 리스트 조회 (MSG-127). 로그인 사용자가 그 격자에 올린 ACTIVE 영상만 created_at DESC 로
	 * 돌려준다. 썸네일 S3 key 는 presigned GET URL 로 변환하고, READY 이전이면 null 이다. 미점령·타인 격자·
	 * 존재하지 않는 gridId 는 빈 리스트다(예외 아님).
	 */
	List<GridVideoResponseDto> getGridVideos(long userId, String gridId);

	/**
	 * 격자 전역 대표 영상 조회 (MSG-87). 그 격자의 공개(PUBLIC)·READY 영상 중 조회수 → 최신 순 1건을
	 * 전역(본인 포함)에서 뽑아 썸네일 presigned GET URL 과 함께 돌려준다. 후보가 없으면(비공개만·인코딩
	 * 중만·타인 없음·존재하지 않는 gridId) null 이다(예외 아님). user_grids.cover_video_id 와 무관하다.
	 */
	GridCoverVideoResponseDto getGridCover(String gridId);

	/**
	 * 단건 영상 재생 조회 (MSG-206). 접근 제어를 존재/DELETED → BLINDED → visibility → processing_status
	 * 순서로 판정한다(first-match, 순서가 곧 정보 노출 정책이다). DELETED·BLINDED(타인)는 VIDEO_NOT_FOUND 로
	 * 존재를 숨기고, PRIVATE(타인)는 VIDEO_FORBIDDEN 으로 존재는 노출하되 접근만 막는다. 허용된 조회는
	 * READY 면 재생본(blurred 우선, 없으면 encoded) presigned GET URL 을, 아니면 playbackUrl=null 을 반환한다.
	 * 재생 URL 을 실제로 발급했고 소유자가 아닐 때만 view_count 를 원자적으로 +1 하며, 응답 viewCount 는
	 * 증가 전 스냅샷이다.
	 */
	VideoPlaybackResponseDto getVideoPlayback(long userId, long videoId);

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

	/**
	 * 본인 영상의 공개 범위(visibility) 를 PUBLIC↔PRIVATE 로 전환한다 (MSG-162). 소유권은 replace·delete 와
	 * 같은 경로로 검증하고, 삭제된 영상은 되살리지 않는다(VIDEO_NOT_FOUND). processing_status 와 무관하게
	 * 전환을 허용하며, 실제 노출은 read 경로가 READY 로 게이트한다. 같은 값 재전환은 멱등하게 성공한다.
	 */
	VideoVisibilityResponseDto setVisibility(long userId, long videoId, VideoVisibilityRequestDto request);
}
