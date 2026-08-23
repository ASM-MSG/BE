package com.msg.fillmap.mission.service;

import com.msg.fillmap.mission.dto.MissionVideoUploadRequestDto;
import com.msg.fillmap.mission.dto.MissionVideoUploadResponseDto;

/**
 * 미션 경유 영상 업로드 (MSG-459). 사용자가 격자를 고르지 않고 미션을 지목하면 서버가 그 미션의 대표
 * 격자에 영상을 확정한다 — 같은 미션의 영상이 한 칸에 모여 미션 하나가 지도에서 하나의 자리로 읽힌다.
 * <p>
 * B 내부 서비스다(계약 인터페이스 아님). 컨트롤러는 video 패키지에 있고(D-11) 저장은 video 도메인의
 * 확정 코어에 위임하지만, 그 앞의 판정이 미션 유형·기간·대표 격자라는 미션 도메인 지식이라 여기 산다.
 */
public interface MissionVideoService {

	/**
	 * 미션 하나에 영상을 확정한다. 판정 순서가 계약이다(§업로드 확정 흐름) — 미션과 무관한 검증 →
	 * 미션 조회 → 멱등 재시도 판정 → 미션 판정 → 확정 → 스탬프·뱃지 → 보존 확인.
	 * <p>
	 * 실패는 셋으로 갈린다. 미래 촬영 시각은 3424, 키 형식·소유 접두어 위반은 3401,
	 * <b>그 밖의 모든 실패는 12409 하나로 수렴한다</b> — 응답으로 미션 존재를 구분할 수 없게 하려는
	 * 것이고(FR-10), 갈래와 근거는 스펙 D-10 에 있다.
	 */
	MissionVideoUploadResponseDto upload(long userId, long missionId, MissionVideoUploadRequestDto request);
}
