package com.msg.fillmap.grid.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridWkt;
import com.msg.fillmap.grid.dto.OccupationResult;
import com.msg.fillmap.grid.repository.GridRepository;
import com.msg.fillmap.grid.repository.UserGridRepository;
import com.msg.fillmap.grid.service.GridOccupationService;

@Service
@RequiredArgsConstructor
public class GridOccupationServiceImpl implements GridOccupationService {

	private final GridRepository gridRepository;
	private final UserGridRepository userGridRepository;

	@Override
	@Transactional
	public OccupationResult occupy(Long userId, double lat, double lon, Long videoId) {
		String gridId = GridEncoder.encode(lat, lon);
		GridEncoder.GridIndex index = GridEncoder.decode(gridId);
		GridEncoder.GridPoint center = GridEncoder.center(gridId);

		// grids FK가 있으므로 전역 격자 등록이 개인 점령보다 먼저여야 한다.
		gridRepository.registerIfAbsent(gridId, index.gridY(), index.gridX(),
			center.lat(), center.lon(), GridWkt.bboxPolygon(gridId));

		int videoCount = userGridRepository.upsertOccupation(userId, gridId);
		return new OccupationResult(gridId, videoCount == 1, videoCount);
	}
}
