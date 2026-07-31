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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.msg.fillmap.grid.GridEncoder;
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

	@Autowired
	private MissionRepository missionRepository;

	@Autowired
	private MissionGridRepository missionGridRepository;

	@Autowired
	private PopupJsonlReader reader;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@TempDir
	private Path tempDir;

	private PopupMissionSeeder newSeeder(boolean enabled, String path) {
		// 프로덕션 기본 생성자(KST 시스템 클럭) 그대로 — 종료 필터와 정리 SQL 이 같은 실시간을 본다.
		PopupMissionSeeder seeder = new PopupMissionSeeder(missionRepository, missionGridRepository, reader);
		ReflectionTestUtils.setField(seeder, "enabled", enabled);
		ReflectionTestUtils.setField(seeder, "jsonlPath", path);
		return seeder;
	}

	private PopupMissionSeeder seeder() {
		return newSeeder(true, "unused");
	}

	@Test
	@DisplayName("플래그 off(기본)면 러너는 아무것도 하지 않는다 — 평시 기동 무영향")
	void 플래그_off면_아무것도_하지_않는다() {
		// 파일이 없는 경로 — 게이트가 새면 파일 부재 예외로 즉시 드러난다.
		Path missing = tempDir.resolve("absent.jsonl");
		long before = popgaCount();

		newSeeder(false, missing.toString()).run(emptyArgs());

		assertThat(popgaCount()).isEqualTo(before);
	}

	@Test
	@DisplayName("시드 실행이 POPUP 미션과 격자 81행을 적재한다 — source_key=팝가 id·target_count=1·seq NULL")
	void 시드_실행이_POPUP_미션과_격자_81행을_적재한다() throws IOException {
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

		List<MissionGrid> grids = missionGridRepository.findByMissionIds(List.of(mission.getId()));
		assertThat(grids).hasSize(81).allSatisfy(grid -> assertThat(grid.getSeq()).isNull());
		assertThat(grids).extracting(MissionGrid::getGridId).contains(GridEncoder.encode(합성_LAT, 합성_LON));
	}

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

	@Test
	@DisplayName("같은 source·source_key 중복 INSERT 는 DB 가 거부한다 — 부분 유니크 인덱스 백스톱 (D3)")
	void 같은_source와_source_key_중복_INSERT는_DB가_거부한다() {
		long id = uniqueId();
		insertPopga(id, unique("원본"), nowUtc().minusDays(1), nowUtc().plusDays(1));

		assertThatThrownBy(() -> insertPopga(id, unique("중복"), nowUtc().minusDays(1), nowUtc().plusDays(1)))
			.hasStackTraceContaining("duplicate key value");
	}

	@Test
	@DisplayName("파일이 없으면 예외로 조기 실패한다 — 조용한 no-op 금지 (FR-5)")
	void 파일이_없으면_예외로_조기_실패한다() {
		Path missing = tempDir.resolve("does-not-exist.jsonl");

		assertThatThrownBy(() -> seeder().seed(missing))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("jsonl");
	}

	@Test
	@DisplayName("종료 필터 후 유효 0건이면 예외로 조기 실패한다 — 전부 종료된 스냅샷 방어 (FR-5)")
	void 종료_필터_후_유효_0건이면_예외로_조기_실패한다() throws IOException {
		Path file = writeJsonl("stale.jsonl",
			row(uniqueId(), unique("옛날 팝업"), 합성_LAT, 합성_LON, "2020-01-18", "2020-01-27"));

		assertThatThrownBy(() -> seeder().seed(file))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("0건");
	}

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

	/** 실측 스키마(D1) 형태의 jsonl 1행 — 미적재 필드(operationTime·address 등)도 원본처럼 포함한다. */
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
