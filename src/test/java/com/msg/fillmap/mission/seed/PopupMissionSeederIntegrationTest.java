package com.msg.fillmap.mission.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.grid.GridConstants;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.mission.entity.Mission;
import com.msg.fillmap.mission.entity.MissionGrid;
import com.msg.fillmap.mission.entity.MissionType;
import com.msg.fillmap.mission.repository.MissionGridRepository;
import com.msg.fillmap.mission.repository.MissionRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 팝업 시더 러너 통합 (MSG-235 모듈 2, 실 PostgreSQL — FestivalMissionSeederIntegrationTest 선례).
 * 플래그 게이트 · 적재 shape(FR-1·2·7, V14 CHECK 'POPUP' 겸증) · source_key 멱등(FR-3) · 정리-선행
 * 순서와 보호 술어(FR-4·8, D4) · 부분 유니크 백스톱(D3) · 조기 실패와 원자성(FR-5·6)을 본다.
 *
 * 격리(공유 로컬 DB): source_key 는 nanoTime 기반 합성 id, 제목은 MSG235-it-* 합성 이름만 쓰고
 * {@code @Transactional} 롤백. 좌표는 reader 의 한국 범위 검증(FR-6) 때문에 축제의 남반구 합성 좌표를 못
 * 쓴다 — 대신 멱등·정리가 좌표 무관(source_key·source 한정)이라 실데이터와 경합하지 않는다.
 */
@SpringBootTest
@Transactional
@DisplayName("PopupMissionSeeder 러너 통합 (실 PostgreSQL) — 합성 source_key·롤백 격리")
class PopupMissionSeederIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	// 진행 중 팝업 픽스처 날짜 — 종료 정리 SQL 이 statement_timestamp()(실제 현재)를 쓰므로 실시간 기준
	// 상대 날짜여야 한다. 날짜 경계의 결정적 검증은 모듈 1 순수 테스트(주입 todayKst·고정 값) 담당.
	private static final LocalDate 시작일 = LocalDate.now(KST).minusDays(5);
	private static final LocalDate 종료일 = LocalDate.now(KST).plusDays(5);
	// 한국 범위 내 합성 좌표 (reader FR-6 검증 통과 필요) — 격리는 좌표가 아니라 source_key 가 담당한다.
	private static final double 합성_LAT = 36.35;
	private static final double 합성_LON = 127.38;
	// 테스트 점을 셀 안 특정 위치(경계에서 몇 m)에 놓기 위한 역방향 변환 (MSG-385 테스트 시나리오 —
	// GridEncoder 의 미터 변환은 비공개라 grid-epsg5179-samples.json 생성과 같은 방식으로 직접 만든다).
	private static final CRSFactory CRS_FACTORY = new CRSFactory();
	private static final CoordinateTransform TO_DEGREES = new CoordinateTransformFactory().createTransform(
		CRS_FACTORY.createFromParameters("EPSG:5179", GridConstants.CRS_DEF_EPSG5179),
		CRS_FACTORY.createFromParameters("WGS84", "+proj=longlat +datum=WGS84 +no_defs"));
	/** 합성 좌표가 속한 기준 셀 — cellPoint 의 원점(남서 모서리)이 되는 셀. */
	private static final GridIndex 기준_셀 = GridEncoder.decode(GridEncoder.encode(합성_LAT, 합성_LON));

	@Autowired
	private MissionRepository missionRepository;

	@Autowired
	private MissionGridRepository missionGridRepository;

	@Autowired
	private PopupJsonlReader reader;

	@Autowired
	private AwsProperties awsProperties;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@TempDir
	private Path tempDir;

	private PopupMissionSeeder newSeeder(boolean enabled, String path) {
		// 프로덕션 기본 생성자(KST 시스템 클럭) 그대로 — 종료 필터와 정리 SQL 이 같은 실시간을 본다.
		PopupMissionSeeder seeder = new PopupMissionSeeder(missionRepository, missionGridRepository, reader,
			awsProperties);
		ReflectionTestUtils.setField(seeder, "enabled", enabled);
		ReflectionTestUtils.setField(seeder, "jsonlPath", path);
		return seeder;
	}

	private PopupMissionSeeder seeder() {
		return newSeeder(true, "unused");
	}

	// 검증: FR-MISSION-11
	@Test
	@DisplayName("플래그 off(기본)면 러너는 아무것도 하지 않는다 — 평시 기동 무영향")
	void 플래그_off면_아무것도_하지_않는다() {
		// 파일이 없는 경로 — 게이트가 새면 파일 부재 예외로 즉시 드러난다.
		Path missing = tempDir.resolve("absent.jsonl");
		long before = popgaCount();

		newSeeder(false, missing.toString()).run(emptyArgs());

		assertThat(popgaCount()).isEqualTo(before);
	}

	// 검증: FR-MISSION-10
	@Test
	@DisplayName("시드 실행이 POPUP 미션과 40m 판정 격자를 적재한다 — source_key=팝가 id·target_count=1·seq NULL")
	void 시드_실행이_POPUP_미션과_40m_판정_격자를_적재한다() throws IOException {
		long id = uniqueId();
		Path file = writeJsonl("seed.jsonl", activeRow(id, unique("성수 합성 팝업"), 합성_LAT, 합성_LON));

		PopupMissionSeeder.SeedResult result = seeder().seed(file);

		assertThat(result.loaded()).isEqualTo(1);
		em.flush();
		em.clear();
		// V14 CHECK 'POPUP' 반영도 이 INSERT 성공이 겸증한다 (성공 기준 2).
		Mission mission = findByKey(id);
		assertThat(mission.getType()).isEqualTo(MissionType.POPUP);
		assertThat(mission.getTargetCount()).isEqualTo(1);
		assertThat(mission.getRegionCode()).isNull();
		assertThat(mission.getPath()).isNull();
		assertThat(mission.getSource()).isEqualTo(PopupMissionSeeder.SOURCE_POPGA);
		assertThat(mission.getSourceKey()).isEqualTo(String.valueOf(id));
		// KST 00:00:00 / 23:59:59 → UTC 순간 (D2) — 축제 static 헬퍼 재사용의 저장값 검증.
		assertThat(mission.getStartAt()).isEqualTo(FestivalMissionSeeder.toUtcStart(시작일));
		assertThat(mission.getEndAt()).isEqualTo(FestivalMissionSeeder.toUtcEnd(종료일));
		assertThat(mission.getCreatedAt()).isNotNull();

		// 격자는 40m 산출과 정확히 일치한다 (MSG-385 완료 조건 ①) — 자기 셀은 거리 0이라 항상 포함.
		List<MissionGrid> grids = missionGridRepository.findByMissionIds(List.of(mission.getId()));
		assertThat(grids).allSatisfy(grid -> assertThat(grid.getSeq()).isNull());
		assertThat(grids).extracting(MissionGrid::getGridId)
			.containsExactlyInAnyOrderElementsOf(PopupMissionSeeder.judgeGrids(합성_LAT, 합성_LON))
			.contains(GridEncoder.encode(합성_LAT, 합성_LON));
	}

	// 검증: FR-MISSION-10
	@Test
	@DisplayName("팝업은 좌표 사방 40m가 걸치는 격자만 적재한다 — 셀 가운데면 자기 셀 1칸 (MSG-385 D1)")
	void 팝업은_좌표_사방_40m가_걸치는_격자만_적재한다() throws IOException {
		// 셀 가운데(경계에서 50m)는 사방 40m 가 어느 경계에도 안 걸친다.
		long id = uniqueId();
		double[] point = cellPoint(50, 50);
		Path file = writeJsonl("cell-one.jsonl", activeRow(id, unique("가운데 팝업"), point[0], point[1]));

		seeder().seed(file);

		assertThat(gridIdsOf(id)).containsExactly(gridId(기준_셀.gridY(), 기준_셀.gridX()));
	}

	// 검증: FR-MISSION-10
	@Test
	@DisplayName("경계에 걸친 팝업은 두 칸을 적재한다 — 동쪽 경계 20m 앞이면 이웃 셀 포함 (MSG-385 D1)")
	void 경계에_걸친_팝업은_두_칸을_적재한다() throws IOException {
		long id = uniqueId();
		double[] point = cellPoint(80, 50);
		Path file = writeJsonl("cell-two.jsonl", activeRow(id, unique("경계 팝업"), point[0], point[1]));

		seeder().seed(file);

		assertThat(gridIdsOf(id)).containsExactlyInAnyOrder(
			gridId(기준_셀.gridY(), 기준_셀.gridX()),
			gridId(기준_셀.gridY(), 기준_셀.gridX() + 1));
	}

	// 검증: FR-MISSION-10
	@Test
	@DisplayName("모서리에 걸친 팝업은 네 칸을 적재한다 — 북동 모서리 20m 앞이면 2×2 (MSG-385 D1)")
	void 모서리에_걸친_팝업은_네_칸을_적재한다() throws IOException {
		long id = uniqueId();
		double[] point = cellPoint(80, 80);
		Path file = writeJsonl("cell-four.jsonl", activeRow(id, unique("모서리 팝업"), point[0], point[1]));

		seeder().seed(file);

		assertThat(gridIdsOf(id)).containsExactlyInAnyOrder(
			gridId(기준_셀.gridY(), 기준_셀.gridX()),
			gridId(기준_셀.gridY(), 기준_셀.gridX() + 1),
			gridId(기준_셀.gridY() + 1, 기준_셀.gridX()),
			gridId(기준_셀.gridY() + 1, 기준_셀.gridX() + 1));
	}

	// 검증: FR-MISSION-10
	@Test
	@DisplayName("재실행하면 기존 81칸 팝업의 격자가 40m 산출로 교체된다 — 갱신 경로 차등 교체 (MSG-385 D3, 완료 조건 ②)")
	void 재실행하면_기존_81칸_팝업의_격자가_40m_산출로_교체된다() throws IOException {
		long id = uniqueId();
		long missionId = insertPopga(id, unique("레거시 블록 팝업"),
			FestivalMissionSeeder.toUtcStart(시작일), FestivalMissionSeeder.toUtcEnd(종료일));
		insertLegacyBlock(missionId);

		PopupMissionSeeder.SeedResult result = seeder()
			.seed(writeJsonl("regrid.jsonl", activeRow(id, unique("레거시 블록 갱신"), 합성_LAT, 합성_LON)));

		assertThat(result.regridded()).isEqualTo(1);
		assertThat(gridIdsOf(id))
			.containsExactlyInAnyOrderElementsOf(PopupMissionSeeder.judgeGrids(합성_LAT, 합성_LON));
	}

	// 검증: FR-MISSION-10
	@Test
	@DisplayName("격자가 이미 산출과 같으면 재실행이 아무 행도 쓰지 않는다 — 차집합 둘 다 공집합 = 멱등 (MSG-385 D3)")
	void 격자가_이미_산출과_같으면_재실행이_아무_행도_쓰지_않는다() throws IOException {
		long id = uniqueId();
		Path file = writeJsonl("regrid-idem.jsonl", activeRow(id, unique("멱등 재산출 팝업"), 합성_LAT, 합성_LON));
		seeder().seed(file);
		em.flush();
		em.clear();

		PopupMissionSeeder.SeedResult second = seeder().seed(file);

		assertThat(second.regridded()).isZero();
		assertThat(gridIdsOf(id))
			.containsExactlyInAnyOrderElementsOf(PopupMissionSeeder.judgeGrids(합성_LAT, 합성_LON));
	}

	// 검증: FR-MISSION-04, FR-MISSION-10
	@Test
	@DisplayName("격자 교체는 스탬프를 건드리지 않는다 — user_missions 행 보존 (MSG-385 완료 조건 ③)")
	void 격자_교체는_스탬프를_건드리지_않는다() throws IOException {
		long userId = userRepository.save(
			User.createLocalUser("msg385-" + System.nanoTime() + "@example.com", "hash", "재산출테스터")).getId();
		long id = uniqueId();
		long missionId = insertPopga(id, unique("스탬프 보존 팝업"),
			FestivalMissionSeeder.toUtcStart(시작일), FestivalMissionSeeder.toUtcEnd(종료일));
		insertLegacyBlock(missionId);
		insertStamp(userId, missionId);

		PopupMissionSeeder.SeedResult result = seeder()
			.seed(writeJsonl("regrid-stamp.jsonl", activeRow(id, unique("스탬프 보존 갱신"), 합성_LAT, 합성_LON)));

		assertThat(result.regridded()).isEqualTo(1);
		em.flush();
		assertThat(stampCount(userId, missionId)).isEqualTo(1);
	}

	// 검증: FR-MISSION-10
	@Test
	@DisplayName("격자 교체 후에도 target_count는 1이다 — 목표 칸 수 불변 (MSG-385 완료 조건 ④)")
	void 격자_교체_후에도_target_count는_1이다() throws IOException {
		long id = uniqueId();
		long missionId = insertPopga(id, unique("목표 불변 팝업"),
			FestivalMissionSeeder.toUtcStart(시작일), FestivalMissionSeeder.toUtcEnd(종료일));
		insertLegacyBlock(missionId);

		seeder().seed(writeJsonl("regrid-target.jsonl", activeRow(id, unique("목표 불변 갱신"), 합성_LAT, 합성_LON)));

		em.flush();
		em.clear();
		assertThat(missionRepository.findById(missionId).orElseThrow().getTargetCount()).isEqualTo(1);
	}

	// 검증: FR-MISSION-10
	@Test
	@DisplayName("좌표가 바뀐 팝업은 새 좌표 기준으로 격자가 옮겨진다 — 구 자리 잔존 잠복 결함 수리 (MSG-385 D3)")
	void 좌표가_바뀐_팝업은_새_좌표_기준으로_격자가_옮겨진다() throws IOException {
		long id = uniqueId();
		String name = unique("이사 간 팝업");
		seeder().seed(writeJsonl("move-first.jsonl", activeRow(id, name, 합성_LAT, 합성_LON)));
		em.flush();
		em.clear();
		// 약 1.1km 북쪽 — 구 산출과 셀이 전혀 겹치지 않는 거리다.
		double movedLat = 합성_LAT + 0.01;

		PopupMissionSeeder.SeedResult result = seeder()
			.seed(writeJsonl("move-second.jsonl", activeRow(id, name, movedLat, 합성_LON)));

		assertThat(result.regridded()).isEqualTo(1);
		List<String> after = gridIdsOf(id);
		assertThat(after).containsExactlyInAnyOrderElementsOf(PopupMissionSeeder.judgeGrids(movedLat, 합성_LON));
		assertThat(after).doesNotContainAnyElementsOf(PopupMissionSeeder.judgeGrids(합성_LAT, 합성_LON));
	}

	// 검증: FR-MISSION-10
	@Test
	@DisplayName("같은 파일 재실행은 신규 0건이다 — source_key(팝가 id) 멱등")
	void 같은_파일_재실행은_신규_0건이다() throws IOException {
		long id = uniqueId();
		Path file = writeJsonl("idem.jsonl", activeRow(id, unique("멱등 팝업"), 합성_LAT, 합성_LON));

		seeder().seed(file);
		PopupMissionSeeder.SeedResult second = seeder().seed(file);

		assertThat(second.loaded()).isZero();
		assertThat(second.deduped()).isEqualTo(1);
		assertThat(countByKey(id)).isEqualTo(1);
	}

	// 검증: FR-MISSION-10
	@Test
	@DisplayName("종료 팝업이 정리되고 mission_grids 가 CASCADE 로 사라진다")
	void 종료_팝업이_정리되고_mission_grids가_CASCADE로_사라진다() throws IOException {
		long endedId = insertPopga(uniqueId(), unique("끝난 팝업"), nowUtc().minusDays(10), nowUtc().minusDays(1));
		insertMissionGrid(endedId, "999901_500000");
		Path file = writeJsonl("refresh.jsonl", activeRow(uniqueId(), unique("진행 팝업"), 합성_LAT, 합성_LON));

		PopupMissionSeeder.SeedResult result = seeder().seed(file);

		assertThat(result.removed()).isGreaterThanOrEqualTo(1);
		assertThat(missionRepository.findById(endedId)).isEmpty();
		assertThat(gridCountOf(endedId)).isZero();
	}

	// 검증: FR-MISSION-10
	@Test
	@DisplayName("정리가 적재보다 먼저라 연장 팝업이 같은 실행에서 재적재된다 — 노출 공백 없음 (D4 순서)")
	void 정리가_적재보다_먼저라_연장_팝업이_같은_실행에서_재적재된다() throws IOException {
		// DB상 종료(연장 전 closeDate 경과)인데 소스에선 같은 id 가 연장 날짜로 살아있다 — 적재-선행이면
		// 구 키 잔존으로 skip 후 정리돼 다음 주까지 공백, 정리-선행이면 이 실행에서 신 날짜로 재적재된다.
		long id = uniqueId();
		long staleId = insertPopga(id, unique("연장 전 팝업"), nowUtc().minusDays(10), nowUtc().minusDays(1));
		Path file = writeJsonl("extend.jsonl", activeRow(id, unique("연장 팝업"), 합성_LAT, 합성_LON));

		PopupMissionSeeder.SeedResult result = seeder().seed(file);

		assertThat(result.loaded()).isEqualTo(1);
		assertThat(missionRepository.findById(staleId)).isEmpty();
		em.flush();
		em.clear();
		assertThat(countByKey(id)).isEqualTo(1);
		assertThat(findByKey(id).getEndAt()).isEqualTo(FestivalMissionSeeder.toUtcEnd(종료일));
	}

	// 검증: FR-MISSION-04, FR-MISSION-10
	@Test
	@DisplayName("스탬프 걸린 종료 미션은 삭제되지 않는다 — V6 FK(NO ACTION) 보호")
	void 스탬프_걸린_종료_미션은_삭제되지_않는다() {
		long userId = userRepository.save(
			User.createLocalUser("msg235-" + System.nanoTime() + "@example.com", "hash", "시더테스터")).getId();
		long endedId = insertPopga(uniqueId(), unique("스탬프 종료"), nowUtc().minusDays(10), nowUtc().minusDays(1));
		insertStamp(userId, endedId);

		missionRepository.deleteEndedBySourceWithoutStamps(PopupMissionSeeder.SOURCE_POPGA);

		assertThat(missionRepository.findById(endedId)).isPresent();
	}

	// 검증: FR-MISSION-10
	@Test
	@DisplayName("타 소스와 수동 미션은 정리에서 불가침이다 — 종료된 FESTIVAL·DURUNUBI·source NULL 잔존 (FR-8)")
	void 타_소스와_수동_미션은_정리에서_불가침이다() {
		long festival = insertMission("EVENT", unique("끝난 축제"), nowUtc().minusDays(10), nowUtc().minusDays(1),
			"FESTIVAL");
		long course = insertMission("COURSE", unique("끝난 코스"), nowUtc().minusDays(10), nowUtc().minusDays(1),
			"DURUNUBI");
		long manual = insertMission("EVENT", unique("끝난 수동"), nowUtc().minusDays(10), nowUtc().minusDays(1), null);

		missionRepository.deleteEndedBySourceWithoutStamps(PopupMissionSeeder.SOURCE_POPGA);

		assertThat(missionRepository.findById(festival)).isPresent();
		assertThat(missionRepository.findById(course)).isPresent();
		assertThat(missionRepository.findById(manual)).isPresent();
	}

	// 검증: FR-MISSION-10
	@Test
	@DisplayName("종료 판정이 KST 세션에서도 스큐 없이 동작한다 — AT TIME ZONE 'UTC' (MSG-223 §D2 규칙)")
	void 종료_판정이_KST_세션에서도_스큐없이_동작한다() {
		// 세션 타임존을 KST 로 강제 — 나이브 비교면 statement_timestamp() 가 UTC+9 로 캐스트돼
		// 1시간 뒤 종료 예정 미션이 "9시간 전 종료"로 오판·오삭제된다 (SET LOCAL 은 이 트랜잭션 한정).
		em.createNativeQuery("SET LOCAL TIME ZONE 'Asia/Seoul'").executeUpdate();
		long stillActive = insertPopga(uniqueId(), unique("한시간 뒤 종료"), nowUtc().minusDays(1), nowUtc().plusHours(1));
		long ended = insertPopga(uniqueId(), unique("한시간 전 종료"), nowUtc().minusDays(1), nowUtc().minusHours(1));

		missionRepository.deleteEndedBySourceWithoutStamps(PopupMissionSeeder.SOURCE_POPGA);

		assertThat(missionRepository.findById(stillActive)).isPresent();
		assertThat(missionRepository.findById(ended)).isEmpty();
	}

	// 검증: FR-MISSION-10
	@Test
	@DisplayName("같은 source·source_key 중복 INSERT 는 DB 가 거부한다 — 부분 유니크 인덱스 백스톱 (D3)")
	void 같은_source와_source_key_중복_INSERT는_DB가_거부한다() {
		long id = uniqueId();
		insertPopga(id, unique("원본"), nowUtc().minusDays(1), nowUtc().plusDays(1));

		assertThatThrownBy(() -> insertPopga(id, unique("중복"), nowUtc().minusDays(1), nowUtc().plusDays(1)))
			.hasStackTraceContaining("duplicate key value");
	}

	// 검증: FR-MISSION-16
	@Test
	@DisplayName("팝업 미션에 운영시간과 주소가 적재된다 — V31 메타데이터 (MSG-383 D3)")
	void 팝업_미션에_운영시간과_주소가_적재된다() throws IOException {
		long id = uniqueId();
		Path file = writeJsonl("meta.jsonl", activeRow(id, unique("메타데이터 팝업"), 합성_LAT, 합성_LON));

		seeder().seed(file);

		em.flush();
		em.clear();
		Mission mission = findByKey(id);
		// row() 픽스처의 운영시간 배열 1원소·도로명+상세 조합 그대로.
		assertThat(mission.getOperationTime()).isEqualTo("매일 11:00 ~ 20:00");
		assertThat(mission.getPlaceName()).isEqualTo("합성 도로명 합성 상세");
		assertThat(mission.getSourceUrl()).isEqualTo("https://popga.co.kr/popup/" + id);
		// 보강 전 스냅샷 행이라 소개문이 없고, 코스 지표는 팝업에 개념이 없다 (D3).
		assertThat(mission.getDescription()).isNull();
		assertThat(mission.getDistanceMeters()).isNull();
	}

	// 검증: FR-MISSION-08 (이미지 미러링은 SRS 등재로 NFR DATA 07), FR-MISSION-16
	@Test
	@DisplayName("팝업 미션에 대표 이미지 주소와 소개문이 적재된다 — 버킷 상대 키를 공개 주소로 조립 (MSG-394 D3)")
	void 팝업_미션에_대표_이미지_주소와_소개문이_적재된다() throws IOException {
		long id = uniqueId();
		String imageKey = "missions/popup/" + id + "-a1b2c3d4.jpg";
		Path file = writeJsonl("image-url.jsonl",
			enrichedRow(id, unique("포스터 있는 팝업"), "한여름 밤의 세일", imageKey));

		seeder().seed(file);

		em.flush();
		em.clear();
		Mission mission = findByKey(id);
		// 파일은 키만 담고 주소는 시더가 자기 환경의 버킷·리전으로 조립한다 — 외부 도메인이 저장될 경로가 없다.
		assertThat(mission.getImageUrl())
			.isEqualTo("https://%s.s3.%s.amazonaws.com/%s"
				.formatted(awsProperties.s3().bucket(), awsProperties.region(), imageKey))
			.endsWith("/" + imageKey);
		assertThat(mission.getDescription()).isEqualTo("한여름 밤의 세일");
	}

	// 검증: FR-MISSION-08
	@Test
	@DisplayName("대표 이미지가 없는 팝업도 정상 적재된다 — image_url NULL")
	void 대표_이미지가_없는_팝업도_정상_적재된다() throws IOException {
		long id = uniqueId();
		Path file = writeJsonl("image.jsonl", activeRow(id, unique("이미지 없는 팝업"), 합성_LAT, 합성_LON));

		PopupMissionSeeder.SeedResult result = seeder().seed(file);

		assertThat(result.loaded()).isEqualTo(1);
		em.flush();
		em.clear();
		assertThat(findByKey(id).getImageUrl()).isNull();
	}

	// 검증: FR-MISSION-08
	@Test
	@DisplayName("소개문이 없고 이미지만 있는 팝업이 정상 적재된다 — 필드 단위 판정의 DB 쪽 귀결 (MSG-394 D1)")
	void 소개문이_없고_이미지만_있는_팝업이_정상_적재된다() throws IOException {
		// 포스터는 대개 있고 소개문은 못 얻는 것이 정상이라, 둘을 한 덩어리로 묶으면 멀쩡히 받은 포스터가
		// 소개문 결측 때문에 버려진다. 수집이 필드 단위로 판정한 결과가 여기까지 살아 와야 한다.
		long id = uniqueId();
		String imageKey = "missions/popup/" + id + "-deadbeef.jpg";
		Path file = writeJsonl("image-only.jsonl",
			enrichedRow(id, unique("소개문 없는 팝업"), null, imageKey));

		PopupMissionSeeder.SeedResult result = seeder().seed(file);

		assertThat(result.loaded()).isEqualTo(1);
		em.flush();
		em.clear();
		Mission mission = findByKey(id);
		assertThat(mission.getImageUrl()).endsWith("/" + imageKey);
		assertThat(mission.getDescription()).isNull();
	}

	// 검증: FR-MISSION-08 (이미지 미러링은 SRS 등재로 NFR DATA 07)
	@Test
	@DisplayName("새 스냅샷에 이미지가 없어도 이미 채운 팝업 이미지는 유지된다 — 이미지 한 필드 예외 (MSG-394 D4)")
	void 새_스냅샷에_이미지가_없어도_이미_채운_팝업_이미지는_유지된다() throws IOException {
		// Given: 포스터가 채워진 미션. 팝업이 끝나면 상세 페이지가 사라져 다음 스냅샷에 키가 없어도
		// 우리 버킷 사본은 살아 있다.
		long id = uniqueId();
		String name = unique("이미지 보존 팝업");
		seeder().seed(writeJsonl("keep-first.jsonl",
			enrichedRow(id, name, "첫 소개문", "missions/popup/" + id + "-cafebabe.jpg")));
		em.flush();
		em.clear();
		String imageUrlBefore = findByKey(id).getImageUrl();
		assertThat(imageUrlBefore).isNotNull();

		// When: 같은 팝가 id 에 imageKey 만 빠지고 소개문이 바뀐 파일로 갱신.
		PopupMissionSeeder.SeedResult result = seeder()
			.seed(writeJsonl("keep-second.jsonl", enrichedRow(id, name, "바뀐 소개문", null)));

		assertThat(result.updated()).isEqualTo(1);
		em.flush();
		em.clear();
		Mission after = findByKey(id);
		assertThat(after.getImageUrl()).isEqualTo(imageUrlBefore);
		// 예외는 이미지에만 걸린다 — 소개문은 외부 원본을 비추는 값이라 새 스냅샷으로 덮인다.
		assertThat(after.getDescription()).isEqualTo("바뀐 소개문");
	}

	// 검증: FR-MISSION-10
	@Test
	@DisplayName("이미지가 실려도 팝업 판정 격자는 40m 산출이고 목표는 1칸이다 — 반경 축소 (MSG-385 D1, MSG-394 D8 대체)")
	void 이미지가_실려도_팝업_판정_격자는_40m_산출이고_목표는_1칸이다() throws IOException {
		long id = uniqueId();
		Path file = writeJsonl("radius.jsonl",
			enrichedRow(id, unique("이미지 있는 팝업"), "소개문", "missions/popup/" + id + "-feedface.jpg"));

		seeder().seed(file);

		em.flush();
		em.clear();
		Mission mission = findByKey(id);
		assertThat(mission.getTargetCount()).isEqualTo(1);
		assertThat(missionGridRepository.findByMissionIds(List.of(mission.getId())))
			.extracting(MissionGrid::getGridId)
			.containsExactlyInAnyOrderElementsOf(PopupMissionSeeder.judgeGrids(합성_LAT, 합성_LON));
	}

	// 검증: FR-MISSION-08
	@Test
	@DisplayName("같은 파일로 재실행하면 적재 0건이고 갱신 0건이다 — 이미지·소개문이 실려도 멱등")
	void 같은_파일로_재실행하면_적재_0건이고_갱신_0건이다() throws IOException {
		long id = uniqueId();
		Path file = writeJsonl("idem-image.jsonl",
			enrichedRow(id, unique("멱등 이미지 팝업"), "소개문", "missions/popup/" + id + "-0badf00d.jpg"));

		seeder().seed(file);
		em.flush();
		em.clear();
		PopupMissionSeeder.SeedResult second = seeder().seed(file);

		assertThat(second.loaded()).isZero();
		assertThat(second.updated()).isZero();
	}

	// 검증: FR-MISSION-08
	@Test
	@DisplayName("잘못된 이미지 키가 한 행이라도 있으면 전체가 롤백된다 — 형식 위반은 결측이 아니라 보안 결함")
	void 잘못된_이미지_키가_한_행이라도_있으면_전체가_롤백된다() throws IOException {
		long survivorId = uniqueId();
		Path file = writeJsonl("bad-image.jsonl",
			enrichedRow(survivorId, unique("정상 행"), "소개문", "missions/popup/" + survivorId + "-a1b2c3d4.jpg"),
			enrichedRow(uniqueId(), unique("경로 밖 행"), "소개문", "profiles/original/1/stolen.jpg"));

		assertThatThrownBy(() -> seeder().seed(file))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("missions/popup/");

		assertThat(countByKey(survivorId)).isZero();
	}

	// 검증: FR-MISSION-16
	@Test
	@DisplayName("이미 적재된 미션에 재실행하면 메타데이터만 채워진다 — 재실행이 곧 백필 (D6)")
	void 이미_적재된_미션에_재실행하면_메타데이터만_채워진다() throws IOException {
		// Given: 메타데이터 없이(V31 이전 형태) 적재된 팝업 미션 — source_key 가 멱등 키다.
		long id = uniqueId();
		String title = unique("백필 대상 팝업");
		long missionId = insertPopga(id, title,
			FestivalMissionSeeder.toUtcStart(시작일), FestivalMissionSeeder.toUtcEnd(종료일));
		insertMissionGrid(missionId, "999902_500000");

		// When: 같은 source_key 로 재실행.
		PopupMissionSeeder.SeedResult result = seeder()
			.seed(writeJsonl("backfill.jsonl", activeRow(id, unique("소스 쪽 새 이름"), 합성_LAT, 합성_LON)));

		assertThat(result.loaded()).isZero();
		assertThat(result.updated()).isEqualTo(1);
		em.flush();
		em.clear();
		Mission after = missionRepository.findById(missionId).orElseThrow();
		assertThat(after.getOperationTime()).isEqualTo("매일 11:00 ~ 20:00");
		assertThat(after.getPlaceName()).isEqualTo("합성 도로명 합성 상세");
		// Then: 제목·기간·source_key 는 그대로다 — 소스 쪽 이름이 바뀌어도 미션은 흔들리지 않는다(D6).
		// 격자만은 스냅샷 좌표의 40m 산출로 교체된다 (MSG-385 D3 — "격자 불변" 단정 대체).
		assertThat(after.getTitle()).isEqualTo(title);
		assertThat(after.getSourceKey()).isEqualTo(String.valueOf(id));
		assertThat(after.getStartAt()).isEqualTo(FestivalMissionSeeder.toUtcStart(시작일));
		assertThat(after.getEndAt()).isEqualTo(FestivalMissionSeeder.toUtcEnd(종료일));
		assertThat(gridIdsOf(id))
			.containsExactlyInAnyOrderElementsOf(PopupMissionSeeder.judgeGrids(합성_LAT, 합성_LON));
	}

	// 검증: FR-MISSION-11
	@Test
	@DisplayName("파일이 없으면 예외로 조기 실패한다 — 조용한 no-op 금지 (FR-5)")
	void 파일이_없으면_예외로_조기_실패한다() {
		Path missing = tempDir.resolve("does-not-exist.jsonl");

		assertThatThrownBy(() -> seeder().seed(missing))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("jsonl");
	}

	// 검증: FR-MISSION-11
	@Test
	@DisplayName("종료 필터 후 유효 0건이면 예외로 조기 실패한다 — 전부 종료된 스냅샷 방어 (FR-5)")
	void 종료_필터_후_유효_0건이면_예외로_조기_실패한다() throws IOException {
		Path file = writeJsonl("stale.jsonl",
			row(uniqueId(), unique("옛날 팝업"), 합성_LAT, 합성_LON, "2020-01-18", "2020-01-27"));

		assertThatThrownBy(() -> seeder().seed(file))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("0건");
	}

	// 검증: FR-MISSION-11
	@Test
	@DisplayName("검증 위반 시 전체 롤백으로 기존 데이터가 유지된다 — 검증이 정리·적재보다 먼저 (FR-5·6)")
	void 검증_위반_시_전체_롤백으로_기존_데이터가_유지된다() throws IOException {
		// 전량 검증(①)이 정리(②)·적재(④)보다 먼저다 — 결함 파일이면 종료 정리조차 실행되지 않아
		// 기존 미션 데이터가 그대로 남는다 (부분 삭제로 노출 공백을 만들지 않는다).
		long endedId = insertPopga(uniqueId(), unique("결함 파일 생존"), nowUtc().minusDays(10), nowUtc().minusDays(1));
		long survivorId = uniqueId();
		Path file = writeJsonl("poison.jsonl",
			activeRow(survivorId, unique("정상 행"), 합성_LAT, 합성_LON),
			activeRow(uniqueId(), unique("범위 밖 행"), -37.5665, 합성_LON));

		assertThatThrownBy(() -> seeder().seed(file))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("한국 범위");

		assertThat(missionRepository.findById(endedId)).isPresent();
		assertThat(countByKey(survivorId)).isZero();
	}

	private String unique(String tag) {
		return "MSG235-it-" + tag + "-" + System.nanoTime();
	}

	/** 기준 셀 남서 모서리에서 동쪽 dxMeters, 북쪽 dyMeters 지점의 위경도 {lat, lon}. */
	private static double[] cellPoint(double dxMeters, double dyMeters) {
		ProjCoordinate degrees = TO_DEGREES.transform(
			new ProjCoordinate(
				기준_셀.gridX() * (double) GridConstants.CELL_SIZE_METERS + dxMeters,
				기준_셀.gridY() * (double) GridConstants.CELL_SIZE_METERS + dyMeters),
			new ProjCoordinate());
		return new double[] {degrees.y, degrees.x};
	}

	private static String gridId(long gridY, long gridX) {
		return gridY + "_" + gridX;
	}

	/** 적재된 판정 격자 id 목록 — flush·clear 후 DB 값으로 읽는다. */
	private List<String> gridIdsOf(long id) {
		em.flush();
		em.clear();
		Mission mission = findByKey(id);
		return missionGridRepository.findByMissionIds(List.of(mission.getId())).stream()
			.map(MissionGrid::getGridId)
			.toList();
	}

	/** 합성 팝가 id — nanoTime 기반이라 실데이터·타 테스트의 source_key 와 못 겹친다 (VARCHAR(30) 이내). */
	private long uniqueId() {
		return System.nanoTime();
	}

	private LocalDateTime nowUtc() {
		return LocalDateTime.now(ZoneOffset.UTC);
	}

	private long popgaCount() {
		return missionRepository.findBySource(PopupMissionSeeder.SOURCE_POPGA).size();
	}

	private Mission findByKey(long id) {
		return missionRepository.findBySource(PopupMissionSeeder.SOURCE_POPGA).stream()
			.filter(mission -> String.valueOf(id).equals(mission.getSourceKey()))
			.findFirst()
			.orElseThrow();
	}

	private long countByKey(long id) {
		return missionRepository.findBySource(PopupMissionSeeder.SOURCE_POPGA).stream()
			.filter(mission -> String.valueOf(id).equals(mission.getSourceKey()))
			.count();
	}

	private Path writeJsonl(String filename, String... rows) throws IOException {
		Path file = tempDir.resolve(filename);
		Files.writeString(file, String.join("\n", rows));
		return file;
	}

	/** 진행 중(오늘 ±5일) 팝업 1행. */
	private static String activeRow(long id, String name, double lat, double lon) {
		return row(id, name, lat, lon, 시작일.toString(), 종료일.toString());
	}

	/**
	 * 보강 수집(MSG-394 D1) 뒤 형태의 진행 중 1행 — 소개문·포스터 키는 null 이면 키 자체를 넣지 않는다
	 * (산출물 계약: 못 얻은 필드는 키가 없다).
	 */
	private static String enrichedRow(long id, String name, String description, String imageKey) {
		StringBuilder row = new StringBuilder(activeRow(id, name, 합성_LAT, 합성_LON));
		row.setLength(row.length() - 1);
		if (description != null) {
			row.append(", \"description\": \"").append(description).append("\"");
		}
		if (imageKey != null) {
			row.append(", \"imageKey\": \"").append(imageKey).append("\"");
		}
		return row.append("}").toString();
	}

	/** 실측 스키마(D1) 형태의 jsonl 1행 — 미적재 필드(periodType)도 원본처럼 포함한다. */
	private static String row(long id, String name, double lat, double lon, String openDate, String closeDate) {
		return """
			{"id": %d, "periodType": "IN_PROGRESS", "openDate": "%s", "closeDate": "%s", \
			"operationTime": ["매일 11:00 ~ 20:00"], "latitude": %s, "longitude": %s, \
			"address": "합성 주소", "addressDetail": "합성 상세", "roadAddress": "합성 도로명", \
			"name": "%s", "sourceUrl": "https://popga.co.kr/popup/%d"}"""
			.formatted(id, openDate, closeDate, lat, lon, name, id);
	}

	/** 팝업 러너 산출물 모사 — source='POPGA'·source_key 로 정리·멱등 대상이 되는 픽스처 (D3·D4). */
	private long insertPopga(long sourceKey, String title, LocalDateTime startAt, LocalDateTime endAt) {
		return insertMissionRow("POPUP", title, startAt, endAt, PopupMissionSeeder.SOURCE_POPGA,
			String.valueOf(sourceKey));
	}

	/** 타 소스·수동(NULL) 미션 — 팝업 정리의 불가침 대상 픽스처 (FR-8). */
	private long insertMission(String type, String title, LocalDateTime startAt, LocalDateTime endAt, String source) {
		return insertMissionRow(type, title, startAt, endAt, source, null);
	}

	private long insertMissionRow(String type, String title, LocalDateTime startAt, LocalDateTime endAt,
		String source, String sourceKey) {
		em.createNativeQuery("""
				INSERT INTO missions (type, title, start_at, end_at, target_count, source, source_key)
				VALUES (:type, :title, :startAt, :endAt, 1, :source, :sourceKey)
				""")
			.setParameter("type", type)
			.setParameter("title", title)
			.setParameter("startAt", startAt)
			.setParameter("endAt", endAt)
			.setParameter("source", source)
			.setParameter("sourceKey", sourceKey)
			.executeUpdate();
		return ((Number) em.createNativeQuery("SELECT id FROM missions WHERE title = :title")
			.setParameter("title", title)
			.getSingleResult()).longValue();
	}

	private void insertMissionGrid(long missionId, String gridId) {
		em.createNativeQuery("INSERT INTO mission_grids (mission_id, grid_id) VALUES (:missionId, :gridId)")
			.setParameter("missionId", missionId)
			.setParameter("gridId", gridId)
			.executeUpdate();
	}

	/** 구 규칙(중심±4, 9×9=81칸) 레거시 블록 픽스처 — 기준 셀 중심, 재산출 대상 (MSG-385). */
	private void insertLegacyBlock(long missionId) {
		for (long dy = -4; dy <= 4; dy++) {
			for (long dx = -4; dx <= 4; dx++) {
				insertMissionGrid(missionId, gridId(기준_셀.gridY() + dy, 기준_셀.gridX() + dx));
			}
		}
	}

	private long stampCount(long userId, long missionId) {
		return ((Number) em.createNativeQuery(
				"SELECT COUNT(*) FROM user_missions WHERE user_id = :userId AND mission_id = :missionId")
			.setParameter("userId", userId)
			.setParameter("missionId", missionId)
			.getSingleResult()).longValue();
	}

	private void insertStamp(long userId, long missionId) {
		em.createNativeQuery("INSERT INTO user_missions (user_id, mission_id) VALUES (:userId, :missionId)")
			.setParameter("userId", userId)
			.setParameter("missionId", missionId)
			.executeUpdate();
	}

	private long gridCountOf(long missionId) {
		return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM mission_grids WHERE mission_id = :missionId")
			.setParameter("missionId", missionId)
			.getSingleResult()).longValue();
	}

	private static ApplicationArguments emptyArgs() {
		return new DefaultApplicationArguments();
	}
}
