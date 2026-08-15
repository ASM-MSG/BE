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
import java.util.Comparator;
import java.util.HashSet;
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

import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridRange;
import com.msg.fillmap.mission.entity.Mission;
import com.msg.fillmap.mission.entity.MissionGrid;
import com.msg.fillmap.mission.entity.MissionMetadata;
import com.msg.fillmap.mission.entity.MissionType;
import com.msg.fillmap.mission.repository.MissionGridRepository;
import com.msg.fillmap.mission.repository.MissionRepository;

/**
 * 팝업 미션 시드·주 1회 수동 갱신 러너 (MSG-235, FestivalMissionSeeder/MSG-224 미러). 기본 off —
 * {@code fillmap.mission.popup.seed.enabled=true} 로 앱 1회 기동할 때만 실행한다(상시 스케줄러 금지, FR-4).
 * 시드와 갱신은 같은 코드 경로다: ① 파싱·전량 검증(D6) → ② 종료 팝업 정리 → ③ 기존 source_key 로드(D3)
 * → ④ dedupe 통과분 INSERT · 기존분 메타데이터 갱신 + 판정 격자 차등 교체(D1·D2, MSG-383 D6 · MSG-385 D3).
 * 빈 DB 면 ②가 0건인 시드가 된다.
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
	/** 판정 반경(m). 40 미만이면 1칸 팝업이 15%를 넘어 위치 오차(10~30m)에 취약하다 (MSG-385, PRD §8). */
	static final int JUDGE_RADIUS_METERS = 40;
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final MissionRepository missionRepository;
	private final MissionGridRepository missionGridRepository;
	private final PopupJsonlReader reader;
	private final AwsProperties awsProperties;
	private final Clock clock;

	@Value("${fillmap.mission.popup.seed.enabled:false}")
	private boolean enabled;

	@Value("${fillmap.mission.popup.seed.path:data/mission/popups.jsonl}")
	private String jsonlPath;

	@Autowired
	public PopupMissionSeeder(
		MissionRepository missionRepository,
		MissionGridRepository missionGridRepository,
		PopupJsonlReader reader,
		AwsProperties awsProperties
	) {
		// KST 클럭: 종료 필터(D6)가 KST 달력 날짜 기준이라서다 — UTC 저장 경계는 toUtcStart/End 가 변환한다.
		this(missionRepository, missionGridRepository, reader, awsProperties, Clock.system(KST));
	}

	/** 클럭 주입 (테스트용 — FestivalMissionSeeder 이중 생성자 미러). */
	public PopupMissionSeeder(
		MissionRepository missionRepository,
		MissionGridRepository missionGridRepository,
		PopupJsonlReader reader,
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
		log.info("팝업 미션 갱신 완료 — 적재 {} 건, dedupe 건너뜀 {} 건(메타데이터 갱신 {} 건, 격자 재산출 {} 건, "
				+ "센트로이드 축소 {} 건), 종료 제외 {} 건, 정리 {} 건",
			result.loaded(), result.deduped(), result.updated(), result.regridded(), result.shrunk(),
			result.skippedEnded(), result.removed());
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

		// source_key → 미션 색인 (D3) — 키만 담지 않는 이유는 skip 경로에서 그 미션의 메타데이터를
		// 갱신하기 때문이다(MSG-383 D6). source_key NULL 행(수동 적재)은 멱등 키가 없어 제외한다.
		// 동키 중복은 부분 유니크 인덱스가 막지만, 막히기 전 데이터가 남아 있어도 갱신 대상이 실행마다
		// 바뀌지 않도록 id 가 가장 작은 쪽으로 고정한다 — findBySource 의 ORDER BY m.id 가 그 근거다.
		List<Mission> popups = missionRepository.findBySource(SOURCE_POPGA);
		Map<String, Mission> existing = popups.stream()
			.filter(mission -> mission.getSourceKey() != null)
			.collect(Collectors.toMap(Mission::getSourceKey, mission -> mission, (first, duplicate) -> first));
		// 기존 팝업들의 현재 격자 — 갱신 경로의 차등 교체(MSG-385 D3)가 대조할 집합. 루프 전 1회 IN 로드
		// (축제 existingFestivals 선례).
		Map<Long, List<MissionGrid>> gridsByMission = popups.isEmpty() ? Map.of()
			: missionGridRepository.findByMissionIds(popups.stream().map(Mission::getId).toList()).stream()
				.collect(Collectors.groupingBy(MissionGrid::getMissionId));
		int loaded = 0;
		int updated = 0;
		int regridded = 0;
		Set<Long> matchedIds = new HashSet<>();
		for (PopupRecord record : parsed.records()) {
			Mission existingMission = existing.get(String.valueOf(record.id()));
			if (existingMission != null) {
				matchedIds.add(existingMission.getId());
				// 이미지 보존은 갱신 경로에서만 명시적으로 고른다 (MSG-394 D4) — 팝업은 기간이 끝나면 상세
				// 페이지가 사라져, 새 스냅샷에 포스터가 없다는 이유로 우리 사본 주소를 잃으면 안 된다.
				if (existingMission.applyMetadata(
					metadataOf(record).withImageFallback(existingMission.getImageUrl()))) {
					updated++;
				}
				// 격자는 스냅샷 좌표의 40m 산출로 차등 교체한다 (MSG-385 D3, 완료 조건 ②) — 팝가에서
				// 좌표가 수정된 팝업도 이 경로가 새 자리로 옮겨 심는다.
				if (regrid(existingMission, judgeGrids(record.latitude(), record.longitude()),
					gridsByMission.getOrDefault(existingMission.getId(), List.of()))) {
					regridded++;
				}
				continue;
			}
			insertMission(record);
			loaded++;
		}
		int shrunk = shrinkOrphans(parsed.records().size(), popups, gridsByMission, matchedIds);
		return new SeedResult(loaded, parsed.records().size() - loaded, updated, regridded, shrunk,
			parsed.skippedEnded(), removed);
	}

	/**
	 * 스냅샷에 없는 미종료 행(미래 시작 포함)을 센트로이드 셀 1칸으로 축소한다 (MSG-385 D4, PRD §8
	 * 2026-08-15 확정). 좌표가 스냅샷에만 있어 40m 참값 산출이 불가능한 행의 처분이다 — 종료 행은 활성
	 * 조건(findAwardCandidateIds·목록 조회)이 이미 걸러 레거시 블록 잔존이 무해하므로 무수정 유지한다.
	 *
	 * 축소 전 완전성 가드: 축소의 트리거가 "스냅샷에 없음"이라는 부재 신호라, 문법상 유효하지만 잘린
	 * 스냅샷(크롤·복사 실패의 부분 결과)이면 빠진 미종료 팝업 전부가 의도적 제외로 오판돼 일괄 축소된다.
	 * 유효 레코드 수가 미종료 적재분의 절반 미만이면 단계를 통째로 건너뛰고 경고만 남긴다 — 오발동해도
	 * 축소가 다음 주기로 미뤄질 뿐이고, 없을 때의 피해는 미종료 전량의 오축소다(MSG-384 reset 완전성
	 * 가드와 같은 부류). INSERT·차등 교체(D3)는 스냅샷에 있는 행에만 작용해 가드 대상이 아니다.
	 *
	 * 미종료 판정 시각은 UTC 다 — 기본 시계가 KST(종료 필터의 달력 날짜용)라 그 벽시계로 UTC 저장값
	 * end_at 을 비교하면 9시간 이르게 종료로 오판해, 앞으로 9시간 안에 끝나는 팝업이 축소 대상에서
	 * 잘못 빠진다. 축소 대상 가드는 칸 수 &gt; 4 하나다 — V28 병합으로 깨진 블록(78~81칸)과 신 규칙 유효
	 * 집합(1~4칸)을 실측상 정확히 가른다(81칸 정사각형 가드는 실데이터가 반증했다).
	 */
	private int shrinkOrphans(int snapshotRecords, List<Mission> popups, Map<Long, List<MissionGrid>> gridsByMission,
		Set<Long> matchedIds) {
		LocalDateTime nowUtc = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
		List<Mission> notEnded = notEndedPopups(popups, nowUtc);
		if (snapshotRecords * 2 < notEnded.size()) {
			log.warn("팝업 축소 단계 건너뜀 — 스냅샷 유효 {} 건이 미종료 적재 {} 건의 절반 미만, 잘린 스냅샷으로 판단 "
				+ "(MSG-385 D4 완전성 가드) — 스냅샷 파일을 점검해 온전한 파일로 다시 실행하세요", snapshotRecords, notEnded.size());
			return 0;
		}
		int shrunk = 0;
		for (Mission mission : notEnded) {
			List<MissionGrid> grids = gridsByMission.getOrDefault(mission.getId(), List.of());
			if (matchedIds.contains(mission.getId()) || grids.size() <= 4) {
				continue;
			}
			if (regrid(mission, List.of(centroidGrid(grids)), grids)) {
				shrunk++;
			}
		}
		if (shrunk > 0) {
			log.warn("스냅샷에 없는 미종료 팝업 {} 건을 센트로이드 셀 1칸으로 축소 — 작업 로그 기록·보고 대상 (MSG-385 D4)",
				shrunk);
		}
		return shrunk;
	}

	/**
	 * 축소 단계의 대상 우주 — 미종료(end_at 이 nowUtc 이후이거나 없음) 적재 행 (MSG-385 D4). 완전성 가드의
	 * 분모와 축소 후보가 모두 여기서 나온다. 인스턴스 메서드인 이유(테스트 심): 가드가 DB 의 미종료 POPGA
	 * 행 수를 보므로, 공유 로컬 DB 의 실데이터 시드 위에서 작은 테스트 스냅샷을 쓰면 가드가 오발동해 축소
	 * 경로가 로컬에서만 스킵된다 — 테스트가 이 메서드를 재정의해 자기 픽스처로 우주를 제한한다(스펙 리뷰 잔여).
	 */
	List<Mission> notEndedPopups(List<Mission> popups, LocalDateTime nowUtc) {
		return popups.stream()
			.filter(mission -> mission.getEndAt() == null || !mission.getEndAt().isBefore(nowUtc))
			.toList();
	}

	/**
	 * 레거시 블록의 축소 목표 셀 (MSG-385 D4). 축별 인덱스 평균을 반올림한 셀이 집합 안에 있으면 그 셀,
	 * 없으면 집합에서 그 점에 가장 가까운 셀 — 거리는 D1 과 같은 체비쇼프 척도(축별 인덱스 차의 최댓값),
	 * 동률은 y 오름차순, x 오름차순의 첫 번째. 완전한 9×9 에서는 min+4 와 동치이고, V28 병합으로 깨진
	 * 블록에서는 참 중심과 최대 1셀의 근사 오차가 남는다(사방 450m 과대 판정 → 1칸 근사라 수용).
	 */
	static String centroidGrid(List<MissionGrid> grids) {
		List<GridIndex> indexes = grids.stream()
			.map(grid -> GridEncoder.decode(grid.getGridId()))
			.sorted(Comparator.comparingLong(GridIndex::gridY).thenComparingLong(GridIndex::gridX))
			.toList();
		long sumY = 0;
		long sumX = 0;
		for (GridIndex index : indexes) {
			sumY += index.gridY();
			sumX += index.gridX();
		}
		long centerY = Math.round((double) sumY / indexes.size());
		long centerX = Math.round((double) sumX / indexes.size());
		GridIndex best = null;
		long bestDistance = Long.MAX_VALUE;
		for (GridIndex index : indexes) {
			long distance = Math.max(Math.abs(index.gridY() - centerY), Math.abs(index.gridX() - centerX));
			if (distance < bestDistance) {
				best = index;
				bestDistance = distance;
			}
		}
		return best.gridY() + "_" + best.gridX();
	}

	/**
	 * 기존 격자를 산출 집합과 차집합으로 맞춘다 (MSG-385 D3). 유지는 교집합, 삭제·삽입은 차집합이라 두 집합의
	 * PK 가 겹치지 않는다 — 쓰기 지연이 한 flush 안에서 INSERT 를 DELETE 보다 먼저 내보내도 PK 충돌이 없다.
	 * 두 집합이 모두 비면 그 자체로 무변경이라 별도 비교 없이 멱등이다. 삭제가 bulk JPQL 이 아니라 행 단위
	 * deleteAll 인 이유: bulk 는 영속성 컨텍스트를 우회해, flush·clear 를 강제하면 같은 트랜잭션에 걸린
	 * 메타데이터 더티 체킹 갱신이 유실될 수 있다.
	 */
	private boolean regrid(Mission mission, List<String> desiredGridIds, List<MissionGrid> currentGrids) {
		Set<String> desired = Set.copyOf(desiredGridIds);
		Set<String> current = currentGrids.stream().map(MissionGrid::getGridId).collect(Collectors.toSet());
		List<MissionGrid> removals = currentGrids.stream()
			.filter(grid -> !desired.contains(grid.getGridId()))
			.toList();
		List<MissionGrid> additions = desired.stream()
			.filter(gridId -> !current.contains(gridId))
			.map(gridId -> new MissionGrid(mission.getId(), gridId))
			.toList();
		if (removals.isEmpty() && additions.isEmpty()) {
			return false;
		}
		missionGridRepository.deleteAll(removals);
		missionGridRepository.saveAll(additions);
		return true;
	}

	/**
	 * 미션 1건 + mission_grids 1~4행(40m 산출, seq NULL) INSERT — target_count=1(관대함으로만 작용),
	 * source_key=팝가 id 문자열화 (FR-1·2·7, D3). 시간 변환은 축제 static 헬퍼 호출(MSG-235 D2, 복제 금지).
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
			.metadata(metadataOf(record))
			.build());
		missionGridRepository.saveAll(judgeGrids(record.latitude(), record.longitude()).stream()
			.map(gridId -> new MissionGrid(mission.getId(), gridId))
			.toList());
	}

	/** 좌표 사방 40m가 걸치는 판정 격자 1, 2, 4칸 (MSG-385 D1·D2). y 오름차순, x 오름차순으로 결정적. */
	static List<String> judgeGrids(double latitude, double longitude) {
		GridRange range = GridEncoder.radiusRange(latitude, longitude, JUDGE_RADIUS_METERS);
		List<String> gridIds = new ArrayList<>(4);
		for (long gridY = range.minGridY(); gridY <= range.maxGridY(); gridY++) {
			for (long gridX = range.minGridX(); gridX <= range.maxGridX(); gridX++) {
				gridIds.add(gridY + "_" + gridX);
			}
		}
		return gridIds;
	}

	/**
	 * 팝업 원본 → 메타데이터 컬럼 매핑 (MSG-383 D3 · MSG-394 D3). 소개문과 포스터는 상세 페이지 보강
	 * 수집 산출이라 MSG-394 부터 실린다 — 코스 지표만 팝업에 개념이 없어 계속 null 이다.
	 * 포스터는 버킷 상대 키를 받아 자기 환경의 공개 주소로 조립한다.
	 */
	private MissionMetadata metadataOf(PopupRecord record) {
		return new MissionMetadata(record.description(), record.placeName(), record.sourceUrl(),
			record.operationTime(), awsProperties.publicUrl(record.imageKey()), null, null, null);
	}

	/**
	 * updated: 이미 있던 미션의 메타데이터를 채운 건수 (MSG-383 D6).
	 * regridded: 스냅샷 좌표로 격자를 재산출·교체한 미션 수 (MSG-385 D3).
	 * shrunk: 스냅샷에 없는 미종료 행을 센트로이드 셀 1칸으로 축소한 미션 수 (MSG-385 D4) — 0이 아니면 보고 대상.
	 */
	public record SeedResult(int loaded, int deduped, int updated, int regridded, int shrunk, int skippedEnded,
		int removed) {
	}
}
