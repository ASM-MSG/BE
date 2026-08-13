package com.msg.fillmap.grid.service;

import java.util.List;

public record GridAggregationView(CurrentRegionView currentRegion, List<RegionAggregateView> items) {
}
