package com.msg.fillmap.mission.seed;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.mission.entity.Mission;
import com.msg.fillmap.mission.entity.MissionGrid;
import com.msg.fillmap.mission.entity.MissionType;
import com.msg.fillmap.mission.repository.MissionGridRepository;
import com.msg.fillmap.mission.repository.MissionRepository;

/**
 * 축제 미션 시드·격주 수동 갱신 러너 (MSG-224, RegionSeeder/MSG-154 패턴). 기본 off —
 * {@code fillmap.mission.festival.seed.enabled=true} 로 앱 1회 기동할 때만 실행한다(상시 스케줄러 금지).
 * 시드와 갱신은 같은 코드 경로다: 파싱·필터(D1) → dedupe 통과분 INSERT(D2·D3) → 종료 축제 정리(D4).
 * 빈 DB 에서 돌리면 정리 0건인 시드가 된다. {@code @Order(30)}: RegionSeeder(10)·ZoneSeeder(20) 이후 —
 * 의존은 없지만 결정적 순서.
 */
@Slf4j
@Component
@Order(30)
public class FestivalMissionSeeder implements ApplicationRunner {

	/** 적재 출처 값 (D7) — 이 러너 산출물 식별자. 정리·dedupe 대조가 이 값만 본다(공유 EVENT 타입과 무관). */
	static final String SOURCE_FESTIVAL = "FESTIVAL";
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	/** 중심±4 → 9×9 = 81격자 (FR-2). */
	private static final int RADIUS = 4;
	/** missions.title VARCHAR(200) 방어 절단 — 실측상 미발생 (D1). */
	private static final int TITLE_MAX_LENGTH = 200;

	private final MissionRepository missionRepository;
	private final MissionGridRepository missionGridRepository;
	private final FestivalJsonlReader reader;
	private final Clock clock;

	@Value("${fillmap.mission.festival.seed.enabled:false}")
	private boolean enabled;

	@Value("${fillmap.mission.festival.seed.path:data/mission/festivals.jsonl}")
	private String jsonlPath;

	@Autowired
	public FestivalMissionSeeder(
		MissionRepository missionRepository,
		MissionGridRepository missionGridRepository,
		FestivalJsonlReader reader
	) {
		// KST 클럭: "오늘" 판정(D1)이 KST 달력 날짜 기준이라서다 — UTC 저장 경계는 toUtcStart/End 가 변환한다.
		this(missionRepository, missionGridRepository, reader, Clock.system(KST));
	}

	/** 클럭 주입 (테스트용 — MissionQueryServiceImpl 이중 생성자 선례). */
	public FestivalMissionSeeder(
		MissionRepository missionRepository,
		MissionGridRepository missionGridRepository,
		FestivalJsonlReader reader,
		Clock clock
	) {
		this.missionRepository = missionRepository;
		this.missionGridRepository = missionGridRepository;
		this.reader = reader;
		this.clock = clock;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (!enabled) {
			return;
		}
		SeedResult result = seed(Path.of(jsonlPath));
		log.info("축제 미션 갱신 완료 — 적재 {} 건, dedupe 건너뜀 {} 건, 행 제외(날짜 {} · 종료 {} · 파싱 {}) 건, 정리 {} 건",
			result.loaded(), result.deduped(),
			result.skippedInvalidDate(), result.skippedEnded(), result.skippedMalformed(), result.removed());
	}

	/**
	 * jsonl 을 파싱해 dedupe 통과분을 INSERT 하고 종료 축제를 정리한다. 파일 부재·유효 0건은 명확한 예외로
	 * 조기 실패 — 잘못된 파일이 조용히 no-op 으로 넘어가지 않게(FR-5, RegionSeeder 선례). 트랜잭션은
	 * 호출자(run 또는 테스트)가 연다 — INSERT·DELETE 가 한 트랜잭션이라 실패 시 전체 롤백된다.
	 */
	public SeedResult seed(Path path) {
		if (!Files.isReadable(path)) {
			throw new IllegalStateException("축제 jsonl 파일을 읽을 수 없습니다: " + path.toAbsolutePath()
				+ " (fillmap-data 의 festivals.jsonl 을 복사하세요 — D6 절차 2)");
		}

		FestivalJsonlReader.Result parsed;
		try (InputStream in = Files.newInputStream(path)) {
			parsed = reader.read(in, LocalDate.now(clock));
		} catch (IOException | UncheckedIOException e) {
			throw new IllegalStateException("축제 jsonl 읽기에 실패했습니다: " + path.toAbsolutePath(), e);
		}

		if (parsed.records().isEmpty()) {
			throw new IllegalStateException("축제 jsonl 에서 유효한 행을 0건 파싱했습니다 (제외: 날짜 "
				+ parsed.skippedInvalidDate() + " · 종료 " + parsed.skippedEnded() + " · 파싱 "
				+ parsed.skippedMalformed() + " 건): " + path.toAbsolutePath()
				+ " — 파일 내용과 수집 스냅샷(전부 종료된 축제인지)을 확인하세요");
		}

		Set<DedupeKey> existingKeys = existingFestivalKeys();
		int loaded = 0;
		for (FestivalRecord record : dedupeSource(parsed.records())) {
			DedupeKey key = keyOf(record);
			if (existingKeys.contains(key)) {
				continue;
			}
			insertMission(record, key);
			loaded++;
		}
		int removed = missionRepository.deleteEndedBySourceWithoutStamps(SOURCE_FESTIVAL);
		return new SeedResult(loaded, parsed.records().size() - loaded,
			parsed.skippedInvalidDate(), parsed.skippedEnded(), parsed.skippedMalformed(), removed);
	}

	/**
	 * 기존 축제(source='FESTIVAL') 미션의 dedupe 키 집합 — 중심 격자는 81행에서 복원한다(재실행 멱등, D3).
	 * 이 러너 산출물만 조회하므로 9×9 블록(중심±4)이 보장돼 min+4 복원이 결정적이다 — type 조회는
	 * 공유 EVENT(팝업 1격자)의 가짜 중심 키 때문에 금지(D7).
	 */
	private Set<DedupeKey> existingFestivalKeys() {
		List<Mission> festivals = missionRepository.findBySource(SOURCE_FESTIVAL);
		if (festivals.isEmpty()) {
			return Set.of();
		}
		Map<Long, List<MissionGrid>> gridsByMission = missionGridRepository
			.findByMissionIds(festivals.stream().map(Mission::getId).toList()).stream()
			.collect(Collectors.groupingBy(MissionGrid::getMissionId));

		Set<DedupeKey> keys = new HashSet<>();
		for (Mission mission : festivals) {
			List<MissionGrid> grids = gridsByMission.get(mission.getId());
			if (grids == null) {
				// 격자 없는 행은 이 시더 산출물 형태가 아니다 — 중심 복원이 불가하니 대조에서 제외.
				continue;
			}
			keys.add(new DedupeKey(restoreCenter(grids), mission.getStartAt(), mission.getEndAt()));
		}
		return keys;
	}

	/** 미션 1건 + mission_grids 81행 INSERT — target_count=1(관대함으로만 작용), seq NULL, source 기록 (FR-2, D7). */
	private void insertMission(FestivalRecord record, DedupeKey key) {
		Mission mission = missionRepository.save(Mission.builder()
			.type(MissionType.EVENT)
			.title(truncateTitle(record.name()))
			.startAt(key.startAt())
			.endAt(key.endAt())
			.targetCount(1)
			.source(SOURCE_FESTIVAL)
			.build());
		missionGridRepository.saveAll(expandGrids(key.centerGridId()).stream()
			.map(gridId -> new MissionGrid(mission.getId(), gridId))
			.toList());
	}

	private static String truncateTitle(String name) {
		return name.length() <= TITLE_MAX_LENGTH ? name : name.substring(0, TITLE_MAX_LENGTH);
	}

	/**
	 * 중심 격자에서 dy, dx ∈ [-4, 4] 로 81개 grid_id 를 전개한다 (D2). 인덱스 → id 조립은 논리 식별자
	 * 포맷("{grid_y}_{grid_x}") 직결 — 좌표에 step 을 더해 재 encode 하면 셀 경계 부동소수점
	 * 오프바이원이 나므로 배제한다.
	 */
	static List<String> expandGrids(String centerGridId) {
		GridIndex center = GridEncoder.decode(centerGridId);
		List<String> gridIds = new ArrayList<>(81);
		for (long dy = -RADIUS; dy <= RADIUS; dy++) {
			for (long dx = -RADIUS; dx <= RADIUS; dx++) {
				gridIds.add(gridId(center.gridY() + dy, center.gridX() + dx));
			}
		}
		return gridIds;
	}

	/** start_at = KST 00:00:00 의 UTC 순간 (D5). */
	static LocalDateTime toUtcStart(LocalDate kstDate) {
		return kstDate.atStartOfDay(KST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
	}

	/** end_at = KST 23:59:59 의 UTC 순간 — 판정이 양끝 포함이라 종료일 자정 직전까지 인정된다 (D5). */
	static LocalDateTime toUtcEnd(LocalDate kstDate) {
		return kstDate.atTime(23, 59, 59).atZone(KST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
	}

	/** 소스 내 dedupe — 같은 키(중심 격자+기간)의 행은 첫 행만 남긴다. 이름은 키가 아니다(이름 변형 중복, D3). */
	static List<FestivalRecord> dedupeSource(List<FestivalRecord> records) {
		Map<DedupeKey, FestivalRecord> byKey = new LinkedHashMap<>();
		for (FestivalRecord record : records) {
			byKey.putIfAbsent(keyOf(record), record);
		}
		return List.copyOf(byKey.values());
	}

	/** dedupe 키 — 좌표는 격자 양자화(≈100m 허용 오차)·정확 일치, 기간은 UTC 변환값으로 DB 저장값과 직접 비교(D3). */
	static DedupeKey keyOf(FestivalRecord record) {
		return new DedupeKey(
			GridEncoder.encode(record.latitude(), record.longitude()),
			toUtcStart(record.startDate()),
			toUtcEnd(record.endDate()));
	}

	/** 기존 미션 81행에서 중심 격자 복원 — 9×9 블록(중심±4)이라 min(gridY)+4, min(gridX)+4 가 결정적이다 (D3). */
	static String restoreCenter(List<MissionGrid> grids) {
		long minY = Long.MAX_VALUE;
		long minX = Long.MAX_VALUE;
		for (MissionGrid grid : grids) {
			GridIndex index = GridEncoder.decode(grid.getGridId());
			minY = Math.min(minY, index.gridY());
			minX = Math.min(minX, index.gridX());
		}
		return gridId(minY + RADIUS, minX + RADIUS);
	}

	private static String gridId(long gridY, long gridX) {
		return gridY + "_" + gridX;
	}

	record DedupeKey(String centerGridId, LocalDateTime startAt, LocalDateTime endAt) {
	}

	public record SeedResult(int loaded, int deduped, int skippedInvalidDate, int skippedEnded, int skippedMalformed,
		int removed) {
	}
}
