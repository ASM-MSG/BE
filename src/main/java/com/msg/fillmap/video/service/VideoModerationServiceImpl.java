package com.msg.fillmap.video.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.notification.entity.NotificationCategory;
import com.msg.fillmap.notification.service.NotificationCommandService;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.entity.VideoStatus;
import com.msg.fillmap.video.exception.VideoErrorCode;
import com.msg.fillmap.video.repository.VideoRepository;

/**
 * 블라인드 전환 · 해제 (MSG-193). 블라인드는 삭제가 아니다 — soft delete 경로가 하는 파생 정리 중
 * 대표 재선정 하나만 공유하고 나머지는 전부 하지 않는다. 점령 row 와 video_count 는 그대로 두고
 * (점령 롤백은 삭제 전용이다), 수집률 캐시·핫스코어·스트릭·뱃지도 건드리지 않으며, 해제로 되돌릴 수
 * 있어야 하므로 S3 객체도 지우지 않는다 (FR-11).
 */
@Service
@RequiredArgsConstructor
public class VideoModerationServiceImpl implements VideoModerationService {

	private final VideoRepository videoRepository;
	// 소유자 통지 (MSG-417) — record 는 전파 REQUIRED 라 전이 트랜잭션과 같은 커밋 (FR-4). B-내부 배선.
	private final NotificationCommandService notificationCommandService;
	private final UserRepository userRepository;

	/**
	 * 잠금 로드는 삭제 경로(MSG-243)와 같은 규칙이다 (D4). 일반 로드로 짜면 동시 삭제 트랜잭션과 읽기 후
	 * 쓰기가 겹쳐, 이미 커밋된 삭제를 블라인드가 ACTIVE 축으로 되살리는 상태 클로버링이 생긴다.
	 */
	@Override
	@Transactional
	public void blind(long videoId) {
		lockOwnerFirst(videoId);
		Video video = videoRepository.findWithLockById(videoId)
			.orElseThrow(() -> new ApiException(VideoErrorCode.VIDEO_NOT_FOUND));
		if (video.isDeleted()) {
			// 삭제는 없음으로 취급한다 (D3) — 재생 경로가 지운 영상을 소유자에게도 404 로 숨기는 것과 같은 결이다.
			throw new ApiException(VideoErrorCode.VIDEO_NOT_FOUND);
		}
		if (video.getStatus() == VideoStatus.BLINDED) {
			throw new ApiException(VideoErrorCode.ALREADY_IN_TARGET_STATUS);
		}

		// markBlinded 가 아래 native 쿼리보다 먼저 와야 한다. Hibernate 가 native 쿼리 실행 전에 영속성
		// 컨텍스트를 자동 flush 하므로(FlushMode.AUTO), 재선정 서브쿼리의 status = 'ACTIVE' 후보 선정이
		// 방금 가린 영상을 다시 고르지 않는다. deleteVideo 의 markDeleted 순서와 같은 원리다.
		video.markBlinded();

		// 대표였다면 재선정 (FR-10). reselectCover 의 WHERE 가드(cover_video_id = 이 영상)가 대표가
		// 아니었으면 no-op 으로 만들어 주므로 호출 측 분기가 필요 없다 (D5).
		videoRepository.reselectCover(video.getUserId(), video.getGridId(), videoId);

		// 소유자 통지 (MSG-417 FR-1·FR-2) — 가드 뒤라 실패 경로(3404·3409)엔 알림이 없다. 신고 사유·신고자는
		// 이 서비스에 들어오지도 않는다(FR-6). 키 꼬리 무작위 UUID: 해제 후 재블라인드가 새 알림이 된다(FR-5).
		notificationCommandService.record(video.getUserId(), NotificationCategory.MODERATION,
			"MODERATION:BLIND:" + videoId + ":" + UUID.randomUUID(),
			"영상이 가려졌어요", "올린 영상이 운영 정책에 따라 가려졌어요. 지금은 다른 사람에게 보이지 않아요");
	}

	/** 해제는 상태 복귀만 한다 (D6) — 대표 원복도, 그 외 파생 갱신도 없다. */
	@Override
	@Transactional
	public void unblind(long videoId) {
		lockOwnerFirst(videoId);
		Video video = videoRepository.findWithLockById(videoId)
			.orElseThrow(() -> new ApiException(VideoErrorCode.VIDEO_NOT_FOUND));
		if (video.isDeleted()) {
			throw new ApiException(VideoErrorCode.VIDEO_NOT_FOUND);
		}
		if (video.getStatus() == VideoStatus.ACTIVE) {
			throw new ApiException(VideoErrorCode.ALREADY_IN_TARGET_STATUS);
		}
		video.markActive();

		// 복구 통지 (MSG-417 FR-3). "공개" 아닌 "원래 상태" — visibility 보존이라 PRIVATE 은 해제돼도 안 보인다.
		notificationCommandService.record(video.getUserId(), NotificationCategory.MODERATION,
			"MODERATION:UNBLIND:" + videoId + ":" + UUID.randomUUID(),
			"영상 숨김이 풀렸어요", "가려졌던 영상이 원래 상태로 돌아왔어요");
	}

	/**
	 * 소유자 users KEY SHARE 선취 (MSG-417 스펙 2절) — record 의 FK 검사가 나중에 잡을 잠금을 videos 행
	 * 잠금보다 먼저 확보해 탈퇴 CASCADE(users 배타 → videos)와 잠금 순서를 통일한다
	 * (VideoStatusWriter.lockUploaderFirst 선례). 배경 태스크인 선례의 조용한 스킵과 달리 관리자 API 라
	 * 빈 결과(영상 행 부재, 또는 유저 부재 = 탈퇴 진행)는 기존 3404 로 던진다 — D3 "삭제는 없음" 과 같은 결.
	 */
	private void lockOwnerFirst(long videoId) {
		Long userId = videoRepository.findUserIdById(videoId)   // 무잠금 선독 — user_id 불변
			.orElseThrow(() -> new ApiException(VideoErrorCode.VIDEO_NOT_FOUND));
		userRepository.findIdForKeyShare(userId)
			.orElseThrow(() -> new ApiException(VideoErrorCode.VIDEO_NOT_FOUND));
	}
}
