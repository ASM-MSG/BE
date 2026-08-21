package com.msg.fillmap.event.service;

import com.msg.fillmap.event.dto.EventLocationVideoPageResponseDto;
import com.msg.fillmap.event.dto.EventVideoDetailResponseDto;
import com.msg.fillmap.event.dto.EventVideoUploadRequestDto;
import com.msg.fillmap.event.dto.EventVideoUploadResponseDto;

/**
 * 행사 영상 쓰기·읽기 경로 (MSG-440). event 도메인 내부 계약이라 다른 도메인이 소비하지 않는다.
 * 세 경로가 공유하는 규칙 둘: ① 미노출 예정 회차(UPCOMING 인데 visibleFrom 이 아직 미래)는 없는 회차와
 * 같은 404(13404)다 — 다른 응답을 주면 순차 id 대입으로 노출 전 행사가 드러난다(MSG-439 공통 규칙).
 * ② 노출 술어(ACTIVE·PUBLIC·READY)는 피드·상세·위치별 영상 수(MSG-439)가 같은 정의 하나를 쓴다 —
 * 표현이 갈라지면 "숫자엔 있는데 목록엔 없는" 불일치가 곧 존재 노출이다.
 */
public interface EventVideoService {

	/**
	 * 행사 영상 업로드 확정 (API 1). 격자는 서버가 위치의 대표 격자로 정하고 공개범위는 PUBLIC 고정이며,
	 * 점령·뱃지·스트릭·인코딩은 일반 업로드와 같고 미션 판정만 타지 않는다.
	 * 같은 s3Key 재시도는 중복을 만들지 않고 저장 행 기준의 성공을 되돌려준다(멱등, 창 판정보다 앞선다).
	 */
	EventVideoUploadResponseDto upload(long userId, long occurrenceId, long locationId,
		EventVideoUploadRequestDto request);

	/**
	 * 위치별 영상 피드 (API 2). 최신 업로드순이며 cursor 는 직전 응답의 nextCursor(opaque) 다 — null 이면
	 * 첫 페이지, 무효거나 다른 위치에서 발급된 것이면 13402 다. size 는 [1, 50] 밖이면 클램프한다
	 * (0 이하·미지정은 기본 20). 아카이브를 포함한 모든 상태에서 조회되고, 영상이 없으면 빈 페이지다.
	 */
	EventLocationVideoPageResponseDto getLocationVideos(long occurrenceId, long locationId, String cursor, int size);

	/**
	 * 행사 영상 상세 (API 3). userId 는 로그인 사용자, 비로그인이면 null 이다 — 소유자 판정(조회수 증가
	 * 제외) 재료로만 쓴다. 행사 영상이 아니거나 노출 술어 밖이면 소유자 본인에게도 13406 이다.
	 */
	EventVideoDetailResponseDto getVideoDetail(long videoId, Long userId);
}
