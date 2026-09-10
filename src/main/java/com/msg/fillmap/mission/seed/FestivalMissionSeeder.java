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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.mission.entity.Mission;
import com.msg.fillmap.mission.entity.MissionGrid;
import com.msg.fillmap.mission.entity.MissionMetadata;
import com.msg.fillmap.mission.entity.MissionType;
import com.msg.fillmap.mission.repository.MissionGridRepository;
import com.msg.fillmap.mission.repository.MissionRepository;

/**
 * 축제 미션 시드·격주 수동 갱신 러너 (MSG-224, RegionSeeder/MSG-154 패턴). 기본 off —
 * {@code fillmap.mission.festival.seed.enabled=true} 로 앱 1회 기동할 때만 실행한다(상시 스케줄러 금지).
 * 시드와 갱신은 같은 코드 경로다: 파싱·필터(D1) → dedupe 통과분 INSERT · 기존분 메타데이터 갱신
 * (D2·D3, MSG-383 D6) → 종료 축제 정리(D4).
 * MSG-384 가 더한 것은 셋이다: 대표 이미지 공개 주소 조립, 갱신 시 이미지 보존 병합, 소스 전환 삭제에서
 * 제외할 미션 id 목록(운영 절차 7번). <b>옛 소스 축제 삭제는 앱 코드에 없다</b> — 전환 당일 운영자가
 * SQL 한 번으로 하고, 그 순서가 적재 → 확인 → 삭제라 적재가 실패해도 옛 미션이 남는다(FR-MISSION-11).
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
	private final AwsProperties awsProperties;
	private final Clock clock;

	@Value("${fillmap.mission.festival.seed.enabled:false}")
	private boolean enabled;

	@Value("${fillmap.mission.festival.seed.path:data/mission/festivals.jsonl}")
	private String jsonlPath;

	@Autowired
	public FestivalMissionSeeder(
		MissionRepository missionRepository,
		MissionGridRepository missionGridRepository,
		FestivalJsonlReader reader,
		AwsProperties awsProperties
	) {
		// KST 클럭: "오늘" 판정(D1)이 KST 달력 날짜 기준이라서다 — UTC 저장 경계는 toUtcStart/End 가 변환한다.
		this(missionRepository, missionGridRepository, reader, awsProperties, Clock.system(KST));
	}

	/** 클럭 주입 (테스트용 — MissionQueryServiceImpl 이중 생성자 선례). */
	public FestivalMissionSeeder(
		MissionRepository missionRepository,
		MissionGridRepository missionGridRepository,
		FestivalJsonlReader reader,
		AwsProperties awsProperties,
		Clock clock
	) {
		this.missionRepository = missionRepository;
		this.missionGridRepository = missionGridRepository;
		this.reader = reader;
		this.awsProperties = awsProperties;
		this.clock = clock;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (!enabled) {
			return;
		}
		SeedResult result = seed(Path.of(jsonlPath));
		log.info("축제 미션 갱신 완료 — 적재 {} 건, dedupe 건너뜀 {} 건(메타데이터 갱신 {} 건), "
				+ "행 제외(날짜 {} · 종료 {} · 파싱 {} · 필수 필드 {}) 건, 정리 {} 건",
			result.loaded(), result.deduped(), result.updated(), result.skippedInvalidDate(), result.skippedEnded(),
			result.skippedMalformed(), result.skippedRequiredField(), result.removed());
		// 소스 전환(MSG-384 D1) 운영 절차 7번이 이 목록을 삭제 조건의 NOT IN 으로 옮겨 적는다 — 제자리에서
		// 갱신된 옛 행은 대체 INSERT 가 없어서, 빠뜨리면 그 축제가 통째로 사라진다. 줄을 나눈 것은 목록이
		// 길어질 수 있어서다(수백 건이면 임시 테이블에 넣어 쓴다).
		log.info("제자리 갱신된 축제 미션 id {} 건 (전환 삭제 제외 목록) — {}",
			result.updatedIds().size(), result.updatedIds());
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
				+ parsed.skippedMalformed() + " · 필수 필드 " + parsed.skippedRequiredField() + " 건): "
				+ path.toAbsolutePath()
				+ " — 파일 내용과 수집 스냅샷(전부 종료된 축제인지)을 확인하세요");
		}

		Map<DedupeKey, ExistingFestival> existing = existingFestivals();
		int loaded = 0;
		int updated = 0;
		List<Long> updatedIds = new ArrayList<>();
		for (FestivalRecord record : dedupeSource(parsed.records())) {
			DedupeKey key = keyOf(record);
			ExistingFestival found = existing.get(key);
			if (found != null) {
				// 재실행이 곧 백필 (MSG-383 D6) — 메타데이터만 더티 체킹으로 갱신하고 제목·기간·격자는 둔다.
				Mission existingMission = found.mission();
				updatedIds.add(existingMission.getId());
				// 이미지 보존은 갱신 경로에서만 명시적으로 고른다 (MSG-384 D2 · MSG-394 D4).
				if (existingMission.applyMetadata(
					metadataOf(record).withImageFallback(existingMission.getImageUrl()))) {
					updated++;
				}
				// 대표 격자 백필 (MSG-459 D-6) — 축제는 격자를 다시 심지 않으므로 비어 있는 행만 채워진다.
				// 저장된 격자를 그대로 넘기는 것이 중요하다: 중심에서 81칸을 다시 전개해 넘기면 V28 로
				// 깨진 블록에서 판정 집합에 없는 칸이 뽑혀 지연 FK 가 커밋 때 시딩 전체를 롤백시킨다.
				MissionRepresentativeGrids.reassignIfOutside(existingMission, found.gridIds());
				continue;
			}
			insertMission(record, key);
			loaded++;
		}
		int removed = missionRepository.deleteEndedBySourceWithoutStamps(SOURCE_FESTIVAL);
		return new SeedResult(loaded, parsed.records().size() - loaded, updated, List.copyOf(updatedIds),
			parsed.skippedInvalidDate(), parsed.skippedEnded(), parsed.skippedMalformed(),
			parsed.skippedRequiredField(), removed);
	}

	/**
	 * 기존 축제(source='FESTIVAL') 미션을 dedupe 키로 색인한다 — 중심 격자는 81행에서 복원한다(재실행
	 * 멱등, D3). 이 러너 산출물만 조회하므로 9×9 블록(중심±4)이 보장돼 min+4 복원이 결정적이다 —
	 * type 조회는 공유 EVENT(팝업 1격자)의 가짜 중심 키 때문에 금지(D7). 키가 아니라 미션 자체를 담는
	 * 이유는 skip 경로에서 그 미션의 메타데이터를 갱신하기 때문이다(MSG-383 D6) — 영속 상태 엔티티라
	 * 필드 변경이 커밋 시점 UPDATE 가 된다. 저장된 격자 id 를 함께 담는 것은 MSG-459 의 대표 격자
	 * 백필이 그 집합에서 산출하기 때문이다 — 중심에서 다시 전개한 81칸이 아니라 실제 저장분이어야 한다.
	 */
	private Map<DedupeKey, ExistingFestival> existingFestivals() {
		List<Mission> festivals = missionRepository.findBySource(SOURCE_FESTIVAL);
		if (festivals.isEmpty()) {
			return Map.of();
		}
		Map<Long, List<MissionGrid>> gridsByMission = missionGridRepository
			.findByMissionIds(festivals.stream().map(Mission::getId).toList()).stream()
			.collect(Collectors.groupingBy(MissionGrid::getMissionId));

		Map<DedupeKey, ExistingFestival> byKey = new HashMap<>();
		for (Mission mission : festivals) {
			List<MissionGrid> grids = gridsByMission.get(mission.getId());
			if (grids == null) {
				// 격자 없는 행은 이 시더 산출물 형태가 아니다 — 중심 복원이 불가하니 대조에서 제외.
				continue;
			}
			// 같은 키가 이미 있으면 id 가 가장 작은 미션을 남긴다 — 부분 유니크가 없는 축제는 이론상 동키
			// 중복이 가능하고, 그때 갱신 대상이 실행마다 바뀌면 안 된다. findBySource 의 ORDER BY m.id 가
			// 첫 원소를 최소 id 로 고정하는 것이 이 결정성의 근거다.
			byKey.putIfAbsent(new DedupeKey(restoreCenter(grids), mission.getStartAt(), mission.getEndAt()),
				new ExistingFestival(mission, grids.stream().map(MissionGrid::getGridId).toList()));
		}
		return byKey;
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
			.metadata(metadataOf(record))
			.build());
		List<String> gridIds = expandGrids(key.centerGridId());
		missionGridRepository.saveAll(gridIds.stream()
			.map(gridId -> new MissionGrid(mission.getId(), gridId))
			.toList());
		// 대표 격자 (MSG-459) — 9×9 홀수 직사각형이라 리졸버 1단이 정중앙, 곧 key.centerGridId() 를 낸다.
		// 그래도 중심을 그대로 쓰지 않고 리졸버를 지나는 것은 규칙을 유형별로 갈라 두지 않기 위해서다.
		MissionRepresentativeGrids.reassignIfOutside(mission, gridIds);
	}

	/**
	 * 축제 원본 → 메타데이터 컬럼 매핑 (MSG-383 D3 · MSG-384 D2). 축제에는 운영시간·코스 지표 개념이
	 * 없어 그 4종은 null 이다. 대표 이미지는 버킷 상대 키를 받아 자기 환경의 공개 주소로 조립한다.
	 */
	private MissionMetadata metadataOf(FestivalRecord record) {
		return new MissionMetadata(record.description(), record.place(), record.homepage(),
			null, awsProperties.publicUrl(record.imageKey()), null, null, null);
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

	/**
	 * start_at = KST 00:00:00 의 UTC 순간 (D5). public 인 것은 관리자 승인 등재
	 * (MissionRegistrationServiceImpl, MSG-500)가 같은 규칙을 써야 하기 때문이다 — 기간 변환 규칙이 두
	 * 벌이 되면 시더 미션과 승인 미션의 하루 경계가 서로 다르게 잘린다.
	 */
	public static LocalDateTime toUtcStart(LocalDate kstDate) {
		return kstDate.atStartOfDay(KST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
	}

	/** end_at = KST 23:59:59 의 UTC 순간 — 판정이 양끝 포함이라 종료일 자정 직전까지 인정된다 (D5, toUtcStart 와 같은 이유로 public). */
	public static LocalDateTime toUtcEnd(LocalDate kstDate) {
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

	/** 이미 적재된 축제와 그 판정 격자 id — 갱신 경로가 메타데이터와 대표 격자를 둘 다 봐야 해서 함께 든다. */
	private record ExistingFestival(Mission mission, List<String> gridIds) {
	}

	/**
	 * updated: 이미 있던 미션의 메타데이터를 채운 건수 (MSG-383 D6) — "적재 0건" 만 보고 오해하지 않게.
	 *
	 * updatedIds: 소스 전환 삭제(MSG-384 D1)에서 제외할 미션 id — <b>값이 안 바뀐 미션도 담는다.</b>
	 * 이 목록의 쓰임은 "무엇이 달라졌나"가 아니라 "새 소스와 키가 맞아 제자리에 남은 행이 무엇인가"라서,
	 * 갱신 경로를 지난 미션이면 값 변화와 무관하게 전부 들어가야 한다. 그래서 updated 건수와 목록 크기가
	 * 다를 수 있다 — 재실행이면 updated 0 에 목록은 그대로다.
	 */
	public record SeedResult(int loaded, int deduped, int updated, List<Long> updatedIds, int skippedInvalidDate,
		int skippedEnded, int skippedMalformed, int skippedRequiredField, int removed) {
	}
}
