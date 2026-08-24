package com.msg.fillmap.event.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.msg.fillmap.event.dto.EventLocationResponseDto;
import com.msg.fillmap.event.dto.EventOccurrenceChipResponseDto;
import com.msg.fillmap.event.dto.EventOccurrenceDetailResponseDto;
import com.msg.fillmap.event.dto.GridEventLocationResponseDto;
import com.msg.fillmap.grid.dto.ViewportBounds;

/**
 * 행사 읽기 경로 (MSG-439). API 1~4 네 조회는 event 도메인 내부용이고, 일괄 위치 조회
 * ({@link #getLocationsBulk})만 경로 추천(MSG-457, route)이 후보 수집 계약으로 소비한다.
 * 네 조회가 공유하는 규칙 둘: 상태는 {@code EventOccurrence.statusAt} 파생값 하나고, 미노출 예정 회차
 * (UPCOMING 인데 visibleFrom 이 아직 미래)는 목록·역조회에서 빠지고 id 직접 조회에서는 없는 회차와 같은
 * 404(13404)다 — 다른 응답을 주면 순차 id 대입만으로 미공개 행사의 존재가 드러난다.
 */
public interface EventQueryService {

	/** 뷰포트에 걸친 예정·진행 중 회차 (API 1). 겹치는 행사가 없으면 실패가 아니라 빈 목록이다. */
	List<EventOccurrenceChipResponseDto> getOccurrencesInViewport(ViewportBounds bounds);

	/**
	 * 행사방 헤더 (API 2). userId 는 로그인 사용자, 비로그인이면 null 이다 —
	 * notificationOn 은 구독 저장소(MSG-442) 전까지 항상 false 고 그때 이 값이 조회 키가 된다.
	 */
	EventOccurrenceDetailResponseDto getOccurrenceDetail(long occurrenceId, Long userId);

	/** 회차의 위치 목록 (API 3). 위치가 없으면 빈 배열, 영상 수는 조회 시점 실측이다. */
	List<EventLocationResponseDto> getLocations(long occurrenceId);

	/** 격자가 속한 행사 위치들 (API 4). 첫 항목이 진입 기본값이라 정렬이 서버 계약이다. */
	List<GridEventLocationResponseDto> getLocationsByGrid(String gridId);

	/**
	 * 여러 회차의 위치 지점 일괄 조회 (MSG-457 경로 추천 소비). {@link #getLocations} 의 경량판 —
	 * 회차 수만큼 단건 조회를 반복하면 N+1 이라 신설했고, route 에 불필요한 영상 수 실측·표시명 조회가 없다.
	 * 회차 안 순서는 단건 조회와 같은 정렬 계약(display_order → id)이라 첫 항목이 진입 기본값이다.
	 * 키가 없는 회차는 위치가 없거나 미노출(존재 은닉 — 단건 조회의 13404 와 같은 결) 회차다.
	 */
	Map<Long, List<LocationPoint>> getLocationsBulk(Collection<Long> occurrenceIds);

	/** 위치 지점 경량 표현 — 대표 좌표는 소비처가 대표 격자에서 산출한다 (GridEncoder.center). */
	record LocationPoint(String name, String representativeGridId) {
	}
}
