package com.msg.fillmap.video.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.video.dto.ExploreGridResponseDto;
import com.msg.fillmap.video.dto.ExploreSort;
import com.msg.fillmap.video.dto.RegionExploreResponseDto;
import com.msg.fillmap.video.dto.RegionGridCountResponseDto;
import com.msg.fillmap.video.repository.ExploreGridProjection;
import com.msg.fillmap.video.repository.VideoRepository;
import com.msg.fillmap.video.support.ThumbnailUrlPresigner;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RegionExploreServiceImpl implements RegionExploreService {

	private final VideoRepository videoRepository;
	private final ThumbnailUrlPresigner thumbnailUrlPresigner;

	/**
	 * limit 보정(§D2: null=전부, 1 미만=1) → sort 분기(native ORDER BY 분기 불가라 메서드 2개 — 90 선례) →
	 * 커버 key presign(null→null 통과) → 헤더 카운트와 합성. 카운트는 limit 무관 전체 기준(§D1)이라 패널이
	 * 카드 3장과 "격자 N·영상 M"을 같은 응답에서 얻는다. summary 0행(미존재 regionCode)이면 regionName null·
	 * 0·빈 배열로 합성한다(§D2 — 6404 재사용 안 함, 행정동 "없음"은 빈 결과이지 예외가 아니다 §D8).
	 * REPEATABLE_READ: 카드·summary가 별도 스테이트먼트라 READ COMMITTED에선 스냅샷이 갈려
	 * §D1의 "카운트 = 카드 집합" 일치가 동시 업로드/삭제/전환 중 깨질 수 있다 — PG는 이 레벨에서
	 * 트랜잭션 단일 스냅샷을 보장(읽기 전용이라 직렬화 실패 없음).
	 */
	@Override
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public RegionExploreResponseDto getRegionGrids(String regionCode, ExploreSort sort, Integer limit) {
		Integer effectiveLimit = limit == null ? null : Math.max(limit, 1);
		List<ExploreGridProjection> projections = sort == ExploreSort.LATEST
			? videoRepository.findExploreGridsLatest(regionCode, effectiveLimit)
			: videoRepository.findExploreGridsPopular(regionCode, effectiveLimit);
		List<ExploreGridResponseDto> grids = projections.stream()
			.map(projection -> ExploreGridResponseDto.of(
				projection, thumbnailUrlPresigner.presign(projection.getCoverThumbnailKey())))
			.toList();
		return videoRepository.getRegionExploreSummary(regionCode)
			.map(summary -> new RegionExploreResponseDto(
				regionCode, summary.getRegionName(), summary.getGridCount(), summary.getVideoCount(), grids))
			.orElseGet(() -> new RegionExploreResponseDto(regionCode, null, 0, 0L, grids));
	}

	@Override
	public List<RegionGridCountResponseDto> getExploreRegions() {
		return videoRepository.getRegionGridCounts().stream()
			.map(RegionGridCountResponseDto::of)
			.toList();
	}
}
