package com.msg.fillmap.event.submission.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.event.submission.dto.EventSubmissionAreaRectDto;
import com.msg.fillmap.event.submission.dto.EventSubmissionLocationResponseDto;
import com.msg.fillmap.event.submission.entity.EventSubmissionAreaRect;
import com.msg.fillmap.event.submission.entity.EventSubmissionLocation;
import com.msg.fillmap.global.geo.AreaCell;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.service.GridQueryService;
import com.msg.fillmap.zone.service.ZoneCellName;
import com.msg.fillmap.zone.service.ZoneNameQueryService;
import com.msg.fillmap.zone.service.ZoneNameResolver;

/**
 * 저장된 신청 위치를 응답 형태로 옮기는 한 곳 (MSG-498 §API 4 · MSG-500 §API 2). 행사 운영자 상세와 관리자
 * 심사 상세가 <b>같은 위치 표현</b>을 줘야 해서(심사자가 보는 영역과 신청자가 보는 영역이 다르면 반려 사유가
 * 서로 다른 그림을 가리킨다) 조립을 두 벌로 두지 않는다.
 * <p>
 * 표시명 재료는 대표 격자 기준으로 서버가 계산해 동봉하고 FE 는 조립만 한다 — 구역은 요청당 리졸버 1회로
 * 순수 계산하고(MSG-341 계약), 행정동은 대표 격자를 한 번에 넘겨 받는다(위치마다 묻지 않는다).
 */
@Component
@RequiredArgsConstructor
public class EventSubmissionLocationView {

	private final ZoneNameQueryService zoneNameQueryService;
	private final GridQueryService gridQueryService;

	public List<EventSubmissionLocationResponseDto> describe(List<EventSubmissionLocation> locations) {
		List<String> gridIds = locations.stream()
			.map(EventSubmissionLocation::getRepresentativeGridId)
			.distinct()
			.toList();
		ZoneNameResolver resolver = zoneNameQueryService.resolver();
		Map<String, String> regionNames = gridQueryService.resolveRegionNames(gridIds);

		List<EventSubmissionLocationResponseDto> dtos = new ArrayList<>();
		for (EventSubmissionLocation location : locations) {
			String gridId = location.getRepresentativeGridId();
			GridIndex index = GridEncoder.decode(gridId);
			ZoneCellName zone = resolver.name(index.gridY(), index.gridX());
			dtos.add(new EventSubmissionLocationResponseDto(
				location.getDisplayOrder(),
				gridId,
				zone.zoneName(),
				zone.zoneCell(),
				regionNames.get(gridId),
				cellCount(location.getRects()),
				location.getRects().stream().map(EventSubmissionAreaRectDto::from).toList()));
		}
		return dtos;
	}

	/**
	 * 합집합 칸 수 (D-7) — 겹치는 사각형을 두 번 세지 않는다. 저장된 사각형은 접수 검증을 이미 통과했으므로
	 * (위치당 81칸 상한·인덱스 범위) 여기서 다시 검증하지 않고 세기만 한다.
	 */
	private int cellCount(List<EventSubmissionAreaRect> rects) {
		Set<AreaCell> cells = new LinkedHashSet<>();
		for (EventSubmissionAreaRect rect : rects) {
			for (int gridY = rect.getMinGridY(); gridY <= rect.getMaxGridY(); gridY++) {
				for (int gridX = rect.getMinGridX(); gridX <= rect.getMaxGridX(); gridX++) {
					cells.add(new AreaCell(gridY, gridX));
				}
			}
		}
		return cells.size();
	}
}
