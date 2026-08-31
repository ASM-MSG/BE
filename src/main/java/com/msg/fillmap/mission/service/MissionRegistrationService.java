package com.msg.fillmap.mission.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.msg.fillmap.mission.entity.MissionType;

/**
 * 미션 등재 (MSG-500 D-2) — 요청 경로에서 미션을 만드는 유일한 문이다. 지금까지 미션을 만드는 쪽은
 * 기동 1회 시더뿐이었고, 관리자 승인이 처음으로 <b>사용자 요청 중에</b> 미션을 만든다.
 *
 * <p>승인 서비스(event 도메인)가 mission 리포지토리를 직접 쓰지 않고 이 인터페이스를 거치는 것은
 * "타 도메인 쓰기는 소유 도메인 서비스를 거친다"는 원칙 때문이다 — event 와 mission 이 둘 다 Owner B
 * 라도 같다. 그래서 KST 날짜 → UTC 순간 변환, 대표 격자 산출, source 값, 스냅숏 무효화처럼 <b>미션의
 * 규칙</b>은 전부 이 구현 안에 있고 호출자는 신청에서 읽은 값만 넘긴다.
 */
public interface MissionRegistrationService {

	/**
	 * 승인 미션 1건 등재 (신청 1건 = 미션 1건). 호출자의 트랜잭션 안에서 실행되므로 승인 전이·이력과 함께
	 * 커밋되거나 함께 롤백된다. 스냅숏 무효화는 그 커밋 이후로 예약된다(D-12).
	 *
	 * @return 만들어진 미션 id — 호출자가 신청 행에 산출물 링크로 기록한다
	 */
	long register(MissionRegistration registration);

	/**
	 * 노출 중지 (MSG-500 D-3) — 승인 행사가 내려갈 때 그 산출물 미션을 숨긴다. 호출자의 트랜잭션 안에서
	 * 실행되고, 스냅숏 무효화는 register 와 대칭으로 커밋 이후다(무효화가 없으면 중지된 미션이 최대 1시간
	 * 계속 그려진다). 이미 숨겨진 미션은 아무 일도 하지 않는다 — 첫 중지 시각이 보존된다.
	 *
	 * @param now 중지 시각(UTC) — 시각의 출처는 심사 트랜잭션이라 호출자가 정한다
	 */
	void hide(long missionId, LocalDateTime now);

	/**
	 * 등재 입력 (신청에서 읽은 값 그대로). 기간은 <b>KST 날짜 라벨</b>이다 — UTC 순간으로 올리는 규칙은
	 * 축제 시더와 한 벌이어야 해서 구현이 갖는다. imageKey 는 버킷 상대 키이고 공개 주소 조립도 구현 몫이다
	 * (미션 시더가 이미 그렇게 한다 — 앱은 주소가 아니라 키를 입력으로 받는다).
	 *
	 * @param gridIds 판정 대상 격자 전량(전 위치 셀 합집합). 대표 격자는 이 집합에서 산출한다
	 */
	record MissionRegistration(
		MissionType type,
		String title,
		LocalDate startsOn,
		LocalDate endsOn,
		String sourceKey,
		String description,
		String operationTime,
		String imageKey,
		List<String> gridIds
	) {
	}
}
