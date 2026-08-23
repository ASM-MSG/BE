package com.msg.fillmap.mission.service.impl;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.mission.dto.MissionAwardResult;
import com.msg.fillmap.mission.dto.MissionVideoUploadRequestDto;
import com.msg.fillmap.mission.dto.MissionVideoUploadResponseDto;
import com.msg.fillmap.mission.entity.Mission;
import com.msg.fillmap.mission.entity.MissionType;
import com.msg.fillmap.mission.entity.UserMissionId;
import com.msg.fillmap.mission.exception.MissionErrorCode;
import com.msg.fillmap.mission.repository.MissionRepository;
import com.msg.fillmap.mission.repository.UserMissionRepository;
import com.msg.fillmap.mission.service.MissionAwardService;
import com.msg.fillmap.mission.service.MissionVideoService;
import com.msg.fillmap.video.entity.Video;
import com.msg.fillmap.video.exception.VideoErrorCode;
import com.msg.fillmap.video.service.ConfirmedVideo;
import com.msg.fillmap.video.service.VideoService;

/**
 * 미션 경유 업로드 구현 (MSG-459). 저장 경로를 새로 만들지 않는다 — MSG-440 이 추출해 둔 확정 코어를
 * 좌표 대신 대표 격자로 태우고(confirmAtGrid), 그 코어가 타지 않는 미션 판정 훅을 확정 직후에 직접
 * 부른다(awardOnUpload). 일반 업로드(saveVideo)가 하는 것과 같은 모양이다.
 */
@Service
public class MissionVideoServiceImpl implements MissionVideoService {

	/** 대표 격자를 갖는 유형 — chk_missions_rep_grid_type 과 같은 집합이다. 코스와 칩 없는 세 유형은 밖이다. */
	private static final Set<MissionType> UPLOADABLE_TYPES = Set.of(MissionType.EVENT, MissionType.POPUP);

	private final MissionRepository missionRepository;
	private final UserMissionRepository userMissionRepository;
	private final VideoService videoService;
	private final MissionAwardService missionAwardService;
	private final Clock clock;

	/**
	 * 프로덕션 생성자 — clock 을 Clock.systemUTC() 로 고정해 전체 생성자로 위임한다(MissionQueryServiceImpl
	 * 선례). UTC 인 이유는 start_at·end_at 이 UTC 로 저장되기 때문이다 — 기본존(로컬 KST)이면 활성 기간
	 * 판정이 9시간 어긋난다. 전체 생성자는 기간 경계 검증용 고정 클럭 주입에 쓴다.
	 */
	@Autowired
	public MissionVideoServiceImpl(
		MissionRepository missionRepository,
		UserMissionRepository userMissionRepository,
		VideoService videoService,
		MissionAwardService missionAwardService
	) {
		this(missionRepository, userMissionRepository, videoService, missionAwardService, Clock.systemUTC());
	}

	public MissionVideoServiceImpl(
		MissionRepository missionRepository,
		UserMissionRepository userMissionRepository,
		VideoService videoService,
		MissionAwardService missionAwardService,
		Clock clock
	) {
		this.missionRepository = missionRepository;
		this.userMissionRepository = userMissionRepository;
		this.videoService = videoService;
		this.missionAwardService = missionAwardService;
		this.clock = clock;
	}

	/**
	 * 판정 순서가 계약이다 (§업로드 확정 흐름). 1 미션과 무관한 검증 → 2 미션 조회(던지지 않는다) →
	 * 3 멱등 재시도 판정 → 4 미션 판정 다섯 → 5 확정 → 6 스탬프·뱃지 → 7 보존 확인.
	 * <p>
	 * 1번이 2번보다 앞선 것은 미션 존재 은닉 때문이다(D-10) — 미션과 무관한 결함을 가진 요청이 미션
	 * 존재에 따라 다른 코드를 받으면 요청 두 벌로 존재를 알아낼 수 있다. 3번이 4번보다 앞선 것은 행사
	 * 업로드의 순서 그대로다 — 마감 직전에 커밋된 업로드의 응답이 유실되고 재시도가 마감 뒤에 도착했을
	 * 때, 이미 성공한 요청이 기간 위반으로 거절되면 안 된다.
	 * <p>
	 * <b>전체가 한 트랜잭션이다.</b> 없으면 confirmAtGrid 가 제 트랜잭션으로 독립 커밋해, 뒤이은 스탬프
	 * 발급이 실패했을 때 영상만 남는다 — 그 상태는 "영상이 있으면 스탬프도 있다"를 정확히 깨뜨리고
	 * 그 미션은 종료 정리에 지워진다(D-3·D-9).
	 */
	@Override
	@Transactional
	public MissionVideoUploadResponseDto upload(long userId, long missionId, MissionVideoUploadRequestDto request) {
		// 1. 문자열과 시각만 보는 검증 — S3 도 DB 도 건드리지 않고, 미션을 아직 조회하지 않았다.
		videoService.validateUploadRequest(userId, request.s3Key(), request.recordedAt());
		// 2. 미션 조회 — 여기서는 던지지 않고 결과를 들고만 있는다.
		Optional<Mission> found = missionRepository.findById(missionId);
		// 3. 멱등 재시도 판정 — 저장 컬럼을 새로 만들지 않고 pending 키 클레임(videos.original_s3_key)을 쓴다.
		Optional<Video> alreadyConfirmed = videoService.findConfirmedByPendingKey(request.s3Key());
		if (alreadyConfirmed.isPresent()) {
			return replay(userId, found, alreadyConfirmed.get());
		}

		// 4. 미션 판정 다섯 — 없음·유형·대표 격자 없음·활성 기간 밖·신고된 촬영 시각이 기간 밖.
		Mission mission = requireUploadable(found, request.recordedAt());
		String gridId = mission.getRepresentativeGridId();

		// 5. 확정. 영상 도메인 실패(S3 에 없는 파일·크기 초과·경합으로 뒤늦게 잡힌 이중 확정)는 여기서
		// 12409 로 수렴시킨다 — 지어낸 키 하나로 미션 존재를 알아내는 오라클을 다시 열지 않기 위해서다(D-10).
		ConfirmedVideo confirmed = confirm(userId, gridId, request);
		// 6. 판정 — 확정 코어 밖의 훅이라 여기서 직접 부른다(일반 업로드와 같은 모양).
		MissionAwardResult award = missionAwardService.awardOnUpload(userId, gridId);
		// 7. 보존 확인.
		requireStamped(userId, missionId, award);

		Video video = confirmed.video();
		return new MissionVideoUploadResponseDto(video.getId(), video.getGridId(),
			video.getProcessingStatus().name(), confirmed.occupied(), confirmed.newBadges(),
			award.completedMissions());
	}

	private ConfirmedVideo confirm(long userId, String gridId, MissionVideoUploadRequestDto request) {
		try {
			return videoService.confirmAtGrid(userId, gridId, request.s3Key(), request.durationSec(),
				request.recordedAt());
		} catch (ApiException e) {
			throw new ApiException(MissionErrorCode.MISSION_UPLOAD_UNAVAILABLE, e);
		}
	}

	/**
	 * 미션 판정 다섯 (§업로드 확정 흐름 4번). 다섯째(신고된 촬영 시각이 미션 기간 밖)가 D-9 의 핵심이다 —
	 * 업로드 게이트와 스탬프 판정이 <b>같은 필드의 같은 값</b>을 같은 구간(양끝 포함)으로 봐야
	 * "업로드가 통과했다 = 그 영상이 판정 조인에 걸린다"가 성립하고, 그래야 영상이 올라온 미션이 종료
	 * 정리에서 살아남는다(FR-17). 한쪽만 어긋나면 보존 논증이 끊긴다.
	 */
	private Mission requireUploadable(Optional<Mission> found, LocalDateTime recordedAt) {
		LocalDateTime now = LocalDateTime.now(clock);
		return found
			.filter(mission -> UPLOADABLE_TYPES.contains(mission.getType()))
			.filter(mission -> mission.getRepresentativeGridId() != null)
			.filter(mission -> within(mission, now))
			.filter(mission -> within(mission, recordedAt))
			.orElseThrow(() -> new ApiException(MissionErrorCode.MISSION_UPLOAD_UNAVAILABLE));
	}

	/**
	 * 보존 확인 (§업로드 확정 흐름 7번, D-9). 게이트를 전부 통과한 업로드가 스탬프 없이 끝나는 경로는
	 * 둘뿐이다 — 활성 판정과 판정 쿼리 사이에 종료 정각이 끼는 경우, 그리고 판정 직전에 팝업 재시딩이
	 * 판정 격자 집합을 갈아 끼우는 경우. 어느 쪽이든 영상만 남고 스탬프가 없어 종료 정리가 그 미션을
	 * 지우므로, 12409 를 던져 업로드 전체를 되돌린다.
	 * <p>
	 * 첫 완료는 6번 결과로 단락되어 추가 쿼리가 0이고, 조회가 붙는 것은 이미 스탬프가 있는 재업로드뿐이며
	 * 그것도 PK 단건이다.
	 */
	private void requireStamped(long userId, long missionId, MissionAwardResult award) {
		boolean stampedNow = award.completedMissions().stream()
			.anyMatch(completed -> completed.missionId() == missionId);
		if (stampedNow || userMissionRepository.existsById(new UserMissionId(userId, missionId))) {
			return;
		}
		throw new ApiException(MissionErrorCode.MISSION_UPLOAD_UNAVAILABLE);
	}

	/**
	 * 응답이 유실된 첫 시도의 재전송 (§API 1 멱등 키). 미션에는 행사방의 event_videos 같은 연결 테이블이
	 * 없으므로(D-8), 소유자가 같고 저장된 grid_id 가 이 미션의 대표 격자와 같고 <b>저장된 recorded_at 이
	 * 미션 기간 안</b>인지로 "내 요청과 같은 자리인가"를 가른다. 촬영 시각 조건이 없으면 일반 좌표
	 * 업로드나 행사 업로드로 확정된 키를 이 엔드포인트에 재전송했을 때, 격자만 맞으면 FR-18 의 가드를
	 * 지난 적 없는 영상에 성공을 돌려주게 된다(D-3).
	 * <p>
	 * 소유자가 다르면 미션과 무관한 도용이라 3401 이고, 격자가 다르거나 저장된 촬영 시각이 기간 밖이면
	 * 12409 다 — 두 판정 모두 미션을 알아야 성립해 응답을 수렴시킨다.
	 */
	private MissionVideoUploadResponseDto replay(long userId, Optional<Mission> found, Video video) {
		if (video.getUserId() != userId) {
			throw new ApiException(VideoErrorCode.INVALID_S3_KEY);
		}
		found
			.filter(mission -> video.getGridId().equals(mission.getRepresentativeGridId()))
			.filter(mission -> within(mission, video.getRecordedAt()))
			.orElseThrow(() -> new ApiException(MissionErrorCode.MISSION_UPLOAD_UNAVAILABLE));
		// 첫 응답 전용 필드는 재시도에서 비운다 — 복원할 저장 컬럼이 없다(행사 업로드와 같은 규칙).
		return new MissionVideoUploadResponseDto(video.getId(), video.getGridId(),
			video.getProcessingStatus().name(), false, List.of(), List.of());
	}

	/** 미션 기간 포함 판정 — 양끝 포함이고 NULL 은 무기간이다(findActive·findCompleted 와 같은 형태). */
	private static boolean within(Mission mission, LocalDateTime moment) {
		return (mission.getStartAt() == null || !mission.getStartAt().isAfter(moment))
			&& (mission.getEndAt() == null || !mission.getEndAt().isBefore(moment));
	}
}
