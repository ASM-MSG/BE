package com.msg.fillmap.mission.seed;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
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
import com.msg.fillmap.mission.entity.Mission;
import com.msg.fillmap.mission.entity.MissionGrid;
import com.msg.fillmap.mission.entity.MissionType;
import com.msg.fillmap.mission.repository.MissionGridRepository;
import com.msg.fillmap.mission.repository.MissionRepository;

/**
 * 팝업 미션 시드·주 1회 수동 갱신 러너 (MSG-235, FestivalMissionSeeder/MSG-224 미러). 기본 off —
 * {@code fillmap.mission.popup.seed.enabled=true} 로 앱 1회 기동할 때만 실행한다(상시 스케줄러 금지, FR-4).
 * 시드와 갱신은 같은 코드 경로다: ① 파싱·전량 검증(D6) → ② 종료 팝업 정리 → ③ 기존 source_key 로드(D3)
 * → ④ dedupe 통과분 INSERT(D1·D2). 빈 DB 면 ②가 0건인 시드가 된다.
 *
 * 축제(적재→정리)와 순서가 반대인 이유(D4): 팝업 키는 팝가 id 뿐이라, DB상 종료(연장 전 closeDate 경과)
 * 인데 소스에서 연장으로 살아있는 팝업이 적재-선행이면 skip(키 잔존) 후 정리돼 다음 주까지 노출 공백이
 * 난다. 정리-선행이면 같은 실행에서 구 행 삭제 → 신 날짜로 재적재돼 공백이 없다.
 * {@code @Order(50)}: Region 10 · Zone 20 · Festival 30 · Course 40 이후 — 의존은 없지만 결정적 순서.
 */
@Slf4j
@Component
@Order(50)
public class PopupMissionSeeder implements ApplicationRunner {

	/** 적재 출처 값 (V13 소유권) — 이 러너 산출물 식별자. 정리·dedupe 대조가 이 값만 본다(FR-8). */
	static final String SOURCE_POPGA = "POPGA";
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final MissionRepository missionRepository;
	private final MissionGridRepository missionGridRepository;
	private final PopupJsonlReader reader;
	private final Clock clock;

	@Value("${fillmap.mission.popup.seed.enabled:false}")
	private boolean enabled;

	@Value("${fillmap.mission.popup.seed.path:data/mission/popups.jsonl}")
	private String jsonlPath;

	@Autowired
	public PopupMissionSeeder(
		MissionRepository missionRepository,
		MissionGridRepository missionGridRepository,
		PopupJsonlReader reader
	) {
		// KST 클럭: 종료 필터(D6)가 KST 달력 날짜 기준이라서다 — UTC 저장 경계는 toUtcStart/End 가 변환한다.
		this(missionRepository, missionGridRepository, reader, Clock.system(KST));
	}

	/** 클럭 주입 (테스트용 — FestivalMissionSeeder 이중 생성자 미러). */
	public PopupMissionSeeder(
		MissionRepository missionRepository,
		MissionGridRepository missionGridRepository,
		PopupJsonlReader reader,
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
		log.info("팝업 미션 갱신 완료 — 적재 {} 건, dedupe 건너뜀 {} 건, 종료 제외 {} 건, 정리 {} 건",
			result.loaded(), result.deduped(), result.skippedEnded(), result.removed());
	}

	/**
	 * jsonl 을 파싱·전량 검증한 뒤 종료 팝업을 정리하고 dedupe 통과분을 INSERT 한다. 파일 부재·유효 0건은
	 * 명확한 예외로 조기 실패 — 잘못된 파일이 조용히 no-op 으로 넘어가지 않게(FR-5, RegionSeeder 선례).
	 * 트랜잭션은 호출자(run 또는 테스트)가 연다 — 검증 예외·중간 실패 시 전체 롤백, 기존 데이터 무변경.
	 */
	public SeedResult seed(Path path) {
		if (!Files.isReadable(path)) {
			throw new IllegalStateException("팝업 jsonl 파일을 읽을 수 없습니다: " + path.toAbsolutePath()
				+ " (fillmap-data 의 popups.jsonl 을 복사하세요 — D8 절차 2)");
		}

		PopupJsonlReader.Result parsed;
		try (InputStream in = Files.newInputStream(path)) {
			parsed = reader.read(in, LocalDate.now(clock));
		} catch (IOException | UncheckedIOException e) {
			throw new IllegalStateException("팝업 jsonl 읽기에 실패했습니다: " + path.toAbsolutePath(), e);
		}

		if (parsed.records().isEmpty()) {
			throw new IllegalStateException("팝업 jsonl 에서 유효한 행을 0건 파싱했습니다 (종료 제외 "
				+ parsed.skippedEnded() + " 건): " + path.toAbsolutePath()
				+ " — 파일 내용과 수집 스냅샷(전부 종료된 팝업인지)을 확인하세요");
		}

		// 정리가 키 로드보다 먼저다 (D4) — 연장 팝업의 구 행(종료)을 지운 뒤 로드해야 구 키가 대조 집합에
		// 안 남아 같은 실행에서 신 날짜로 재적재된다.
		int removed = missionRepository.deleteEndedBySourceWithoutStamps(SOURCE_POPGA);

		Set<String> existingKeys = missionRepository.findBySource(SOURCE_POPGA).stream()
			.map(Mission::getSourceKey)
			.collect(Collectors.toSet());
		int loaded = 0;
		for (PopupRecord record : parsed.records()) {
			if (existingKeys.contains(String.valueOf(record.id()))) {
				continue;
			}
			insertMission(record);
			loaded++;
		}
		return new SeedResult(loaded, parsed.records().size() - loaded, parsed.skippedEnded(), removed);
	}

	/**
	 * 미션 1건 + mission_grids 81행(중심±4, seq NULL) INSERT — target_count=1(관대함으로만 작용),
	 * source_key=팝가 id 문자열화 (FR-1·2·7, D3). 격자 전개·시간 변환은 축제 static 헬퍼 호출(D2, 복제 금지).
	 */
	private void insertMission(PopupRecord record) {
		Mission mission = missionRepository.save(Mission.builder()
			.type(MissionType.POPUP)
			.title(record.name())
			.startAt(FestivalMissionSeeder.toUtcStart(record.openDate()))
			.endAt(FestivalMissionSeeder.toUtcEnd(record.closeDate()))
			.targetCount(1)
			.source(SOURCE_POPGA)
			.sourceKey(String.valueOf(record.id()))
			.build());
		missionGridRepository.saveAll(
			FestivalMissionSeeder.expandGrids(GridEncoder.encode(record.latitude(), record.longitude())).stream()
				.map(gridId -> new MissionGrid(mission.getId(), gridId))
				.toList());
	}

	public record SeedResult(int loaded, int deduped, int skippedEnded, int removed) {
	}
}
