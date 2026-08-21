package com.msg.fillmap.event;

import java.time.LocalDateTime;
import java.util.UUID;

import com.msg.fillmap.event.entity.EventLocation;
import com.msg.fillmap.event.entity.EventLocationGrid;
import com.msg.fillmap.event.entity.EventLocationType;
import com.msg.fillmap.event.entity.EventOccurrence;
import com.msg.fillmap.event.entity.EventSeries;
import com.msg.fillmap.event.repository.EventLocationGridRepository;
import com.msg.fillmap.event.repository.EventLocationRepository;
import com.msg.fillmap.event.repository.EventOccurrenceRepository;
import com.msg.fillmap.event.repository.EventSeriesRepository;

/**
 * 행사 시리즈·회차·위치 시드 헬퍼 (MSG-440 테스트 공용). 업로드·피드·상세·공개범위 차단 테스트가 전부 같은
 * 3층 데이터(시리즈 → 회차 → 위치 + 격자 영역)를 필요로 해 한 곳에 모았다.
 * <p>
 * 격리(공유 로컬 DB): 자연키에 UUID 를 섞어 재실행·병렬 실행에서 충돌하지 않게 하고, 호출자는 서해 먼바다
 * 격자를 넘겨 육상 실데이터와 겹치지 않게 한다. 정리는 호출 테스트의 {@code @Transactional} 롤백이 한다.
 */
public class EventTestFixtures {

	private final EventSeriesRepository seriesRepository;
	private final EventOccurrenceRepository occurrenceRepository;
	private final EventLocationRepository locationRepository;
	private final EventLocationGridRepository locationGridRepository;

	public EventTestFixtures(EventSeriesRepository seriesRepository,
		EventOccurrenceRepository occurrenceRepository,
		EventLocationRepository locationRepository,
		EventLocationGridRepository locationGridRepository) {
		this.seriesRepository = seriesRepository;
		this.occurrenceRepository = occurrenceRepository;
		this.locationRepository = locationRepository;
		this.locationGridRepository = locationGridRepository;
	}

	public String 키(String suffix) {
		return "msg440-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	public EventSeries 시리즈() {
		return seriesRepository.save(new EventSeries(키("series"), "테스트 시리즈"));
	}

	/** 노출 영역 사각형은 대표 격자 주변 한 칸이면 충분하다 — 이 티켓의 조회는 뷰포트 겹침을 타지 않는다. */
	public EventOccurrence 회차(EventSeries series, LocalDateTime startsAt, LocalDateTime endsAt, String gridId) {
		String[] index = gridId.split("_");
		int gridY = Integer.parseInt(index[0]);
		int gridX = Integer.parseInt(index[1]);
		EventOccurrence occurrence = new EventOccurrence(series, 키("occ"));
		occurrence.update(series, "테스트 행사", "부산", startsAt, endsAt, gridY, gridY + 1, gridX, gridX + 1);
		return occurrenceRepository.save(occurrence);
	}

	/** 첫 격자가 대표 격자다 — 대표 격자 결정 규칙(3단)은 시더 몫이고 여기서는 지정값을 그대로 쓴다. */
	public EventLocation 위치(EventOccurrence occurrence, String name, String... gridIds) {
		EventLocation location = new EventLocation(occurrence, 키("loc"));
		location.update(occurrence, name, EventLocationType.POPUP, "11:00 ~ 20:00", 0, gridIds[0]);
		locationRepository.save(location);
		for (String gridId : gridIds) {
			locationGridRepository.save(new EventLocationGrid(location.getId(), occurrence.getId(), gridId));
		}
		return location;
	}
}
