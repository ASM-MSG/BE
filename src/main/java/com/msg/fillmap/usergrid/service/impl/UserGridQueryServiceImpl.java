package com.msg.fillmap.usergrid.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.usergrid.repository.CollectionSummaryProjection;
import com.msg.fillmap.usergrid.repository.UserGridRepository;
import com.msg.fillmap.usergrid.service.CollectionSummaryView;
import com.msg.fillmap.usergrid.service.UserGridQueryService;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserGridQueryServiceImpl implements UserGridQueryService {

	private final UserGridRepository userGridRepository;

	@Override
	public CollectionSummaryView getCollectionSummary(long userId) {
		CollectionSummaryProjection projection = userGridRepository.getCollectionSummary(userId);
		return new CollectionSummaryView(
			projection.getTotalGridCount(),
			projection.getTotalVideoCount(),
			projection.getVisitedRegionCount()
		);
	}
}
