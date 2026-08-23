package com.msg.fillmap.video.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.msg.fillmap.video.dto.FriendGridVideoResponseDto;
import com.msg.fillmap.video.dto.GridCoverVideoResponseDto;
import com.msg.fillmap.video.dto.GridHourlyUploadResponseDto;
import com.msg.fillmap.video.dto.GridVideoPageResponseDto;
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
import com.msg.fillmap.video.entity.Video;

public interface VideoService {

	/**
	 * 격자별 내 영상 리스트 조회 (MSG-127). 로그인 사용자가 그 격자에 올린 ACTIVE 영상만 created_at DESC 로
	 * 돌려준다. 썸네일 S3 key 는 presigned GET URL 로 변환하고, READY 이전이면 null 이다. 미점령·타인 격자·
	 * 존재하지 않는 gridId 는 빈 리스트다(예외 아님).
	 */
	List<GridVideoResponseDto> getGridVideos(long userId, String gridId);

	/**
	 * 친구 격자 영상 목록 (MSG-187 D5). ownerUserId 가 gridId 에 올린 영상 중 친구에게 허용된
	 * 공개범위(PUBLIC·FRIENDS)의 ACTIVE·READY 영상만 created_at DESC 로 돌려준다 — PRIVATE 은 제외다.
	 * <b>친구 판정은 호출자(friend 도메인) 선행 책임</b>이다 — 이 메서드는 관계를 확인하지 않는다.
	 * 미점령 격자·존재하지 않는 gridId 는 빈 리스트다(예외 아님).
	 */
	List<FriendGridVideoResponseDto> getFriendGridVideos(long ownerUserId, String gridId);

	/**
	 * 격자 전역 대표 영상 조회 (MSG-87). 그 격자의 공개(PUBLIC)·READY 영상 중 조회수 → 최신 순 1건을
	 * 전역(본인 포함)에서 뽑아 썸네일 presigned GET URL 과 함께 돌려준다. 후보가 없으면(비공개만·인코딩
	 * 중만·타인 없음·존재하지 않는 gridId) null 이다(예외 아님). user_grids.cover_video_id 와 무관하다.
	 */
	GridCoverVideoResponseDto getGridCover(String gridId);

	/**
	 * 격자 전역 영상 목록 조회 (MSG-237). 그 격자의 공개(PUBLIC)·READY·ACTIVE 영상을 전역(본인 포함)에서
	 * 인기순(view_count → created_at → id DESC)으로 한 페이지 돌려준다. 소유자 분기가 없어 내 PRIVATE 영상도
	 * 포함되지 않는다(개인 축은 getGridVideos — §D1). cursor 는 직전 응답의 nextCursor(opaque) — null 이면
	 * 첫 페이지, 무효면 INVALID_CURSOR(400). size 는 [1, 50] 밖이면 클램프(0 이하 → 기본 20, §D5). 후보가
	 * 없거나 존재하지 않는 gridId 는 빈 페이지다(예외 아님).
	 */
	GridVideoPageResponseDto getGridGlobalVideos(String gridId, String cursor, int size);

	/**
	 * 미션 영상 목록 조회 (MSG-390). 그 미션의 대상 격자(mission_grids)에서 미션 기간에 촬영된
	 * 전역 공개 게이트(ACTIVE, PUBLIC, READY) 통과 영상을 촬영 시각(recorded_at) 내림차순으로
	 * 페이지 조회한다. 무기간 미션은 기간 조건을 타지 않는다. userId 없음 - 결과가 호출자와 무관하다.
	 * 조건에 맞는 영상이 없거나 존재하지 않는 missionId 는 빈 페이지다(예외 아님).
	 */
	GridVideoPageResponseDto getMissionVideos(long missionId, String cursor, int size);

	/**
	 * 격자 전역 시간대 분포 조회 (MSG-372). 그 격자의 전역 공개 게이트(ACTIVE, PUBLIC, READY) 통과
	 * 영상을 업로드 시각(created_at)의 KST 시(0~23)로 접어 24구간 개수를 돌려준다. 집계 윈도우는
	 * 전체 누적이다. 공개 영상이 없거나 존재하지 않는 gridId 는 전 구간 0 인 정상 응답이다(예외 아님).
	 */
	GridHourlyUploadResponseDto getGridHourlyUploads(String gridId);

	/**
	 * 단건 영상 재생 조회 (MSG-206). 접근 제어를 존재/DELETED → BLINDED → visibility → processing_status
	 * 순서로 판정한다(first-match, 순서가 곧 정보 노출 정책이다). DELETED·BLINDED(타인)는 VIDEO_NOT_FOUND 로
	 * 존재를 숨기고, PRIVATE(타인)·FRIENDS(비친구)는 VIDEO_FORBIDDEN 으로 존재는 노출하되 접근만 막는다
	 * (MSG-285 §D1 — 두 실패 응답은 동일하다). 허용된 조회는 READY 면 재생본(blurred 우선, 없으면 encoded)
	 * presigned GET URL 을, 아니면 playbackUrl=null 을 반환한다.
	 * 재생 URL 을 실제로 발급했고 소유자가 아닐 때만 view_count 를 원자적으로 +1 하며, 응답 viewCount 는
	 * 증가 전 스냅샷이다.
	 */
	VideoPlaybackResponseDto getVideoPlayback(long userId, long videoId);

	/**
	 * 업로드 완료 메타데이터 저장. 좌표 → 격자 인코딩 → grids lazy insert → videos INSERT → 점령 UPSERT.
	 */
	VideoUploadResponseDto saveVideo(long userId, VideoUploadRequestDto request);

	/**
	 * 격자 지정 업로드 확정 (MSG-440) — 좌표 대신 호출자가 정한 격자에 영상을 확정한다. 행사 업로드가 쓰며
	 * geom 은 그 격자의 셀 중심점, 공개범위는 PUBLIC 고정이다. pending 키 검증·grids lazy insert·
	 * videos INSERT·S3 복사·점령 UPSERT·뱃지·스트릭·커밋 후 인코딩과 핫스코어는 saveVideo 와 같은 코어를
	 * 지나고, <b>미션 판정만 타지 않는다</b>(행사 업로드 미션 비연계, MSG-438 제외 계약).
	 * 호출자의 트랜잭션에 합류한다 — 영상 생성과 행사 연결이 한 트랜잭션이어야 하기 때문이다.
	 * video 도 event 도 Owner B 라 도메인 간 계약 인터페이스가 아니라 B 내부 확장이다.
	 */
	ConfirmedVideo confirmAtGrid(long userId, String gridId, String s3Key, Short durationSec,
		LocalDateTime recordedAt);

	/**
	 * 미션·행사와 무관한 업로드 요청 검증 (MSG-459 D-10) — 미래 촬영 시각(3424)과 pending 키 형식·소유
	 * 접두어(3401)만 본다. 문자열과 시각만 보는 순수 계산이라 S3 도 DB 도 건드리지 않는다.
	 * <p>
	 * 공개된 이유는 <b>호출자가 이 둘을 자기 도메인 조회보다 먼저 돌려야 하기 때문</b>이다. 미션 경유
	 * 업로드가 미션 조회 뒤에 검증하면 "있는 미션에 무효 키"와 "없는 미션에 무효 키"의 응답이 갈려
	 * 미션 존재 오라클이 열린다. 5분 허용 상수·Clock·pending 키 접두어 규칙이 이미 이 구현 안에 있어
	 * 미션 쪽으로 복제하지 않으려는 것이고, 확정 코어가 나중에 같은 검사를 다시 해도 무해하다.
	 * video 도 mission 도 Owner B 라 도메인 간 계약 인터페이스가 아니라 B 내부 확장이다.
	 */
	void validateUploadRequest(long userId, String s3Key, LocalDateTime recordedAt);

	/**
	 * pending 키에서 이미 확정된 영상 조회 (MSG-440 멱등 재시도 판정). 확정과 같은 advisory lock 을 먼저
	 * 잡으므로 앞 시도가 커밋됐다면 반드시 보인다 — 호출자는 찾은 영상의 소유자·연결이 자기 요청과 같은지
	 * 확인해 재시도(성공 재응답)와 도용(3401)을 가른다. 형식이 어긋난 키는 empty 다.
	 */
	Optional<Video> findConfirmedByPendingKey(String pendingKey);

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
	 * 본인 영상의 공개 범위(visibility) 를 PUBLIC·PRIVATE·FRIENDS 간 전환한다 (MSG-162, MSG-285). 소유권은 replace·delete 와
	 * 같은 경로로 검증하고, 삭제된 영상은 되살리지 않는다(VIDEO_NOT_FOUND). processing_status 와 무관하게
	 * 전환을 허용하며, 실제 노출은 read 경로가 READY 로 게이트한다. 같은 값 재전환은 멱등하게 성공한다.
	 */
	VideoVisibilityResponseDto setVisibility(long userId, long videoId, VideoVisibilityRequestDto request);
}
