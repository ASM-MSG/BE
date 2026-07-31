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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.mission.entity.Mission;
import com.msg.fillmap.mission.entity.MissionGrid;
import com.msg.fillmap.mission.entity.MissionType;
import com.msg.fillmap.mission.repository.MissionGridRepository;
import com.msg.fillmap.mission.repository.MissionRepository;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;

/**
 * 축제 시더 러너 통합 (MSG-224 모듈 3, 실 PostgreSQL — RegionSeederTest 선례). 플래그 게이트 · 적재
 * shape(FR-1·2) · 재실행 멱등(FR-3) · 종료 정리와 보호 술어 3종(FR-4, D4) · 조기 실패와 원자성(FR-5)을 본다.
 *
 * 격리(공유 로컬 DB): 합성 이름(MSG224-it-*)·남반구 합성 좌표만 쓰고 @Transactional 롤백. 롤백 검증만
 * NOT_SUPPORTED + TransactionTemplate 로 실제 트랜잭션 경계를 돌린다 — 예외 시 전체 롤백이라 잔재가 없다.
 */
@SpringBootTest
@Transactional
@DisplayName("FestivalMissionSeeder 러너 통합 (실 PostgreSQL) — 합성 좌표·롤백 격리")
class FestivalMissionSeederIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	// 진행 중 축제 픽스처 날짜 — 종료 정리 SQL 이 statement_timestamp()(실제 현재)를 쓰므로 실시간 기준
	// 상대 날짜여야 한다(고정 클럭 픽스처는 시더 자신의 정리 단계에 지워진다). 날짜 경계의 결정적 검증은
	// 모듈 1·2 순수 테스트(주입 todayKst·고정 값) 담당.
	private static final LocalDate 시작일 = LocalDate.now(KST).minusDays(5);
	private static final LocalDate 종료일 = LocalDate.now(KST).plusDays(5);
	// 남반구 합성 좌표 — 원본이 한국 범위(33~39N)만 보장하므로 실데이터·타 테스트와 못 겹친다.
	private static final double 합성_LAT = -37.5665;
	private static final double 합성_LON = 126.978;

	@Autowired
	private MissionRepository missionRepository;

	@Autowired
	private MissionGridRepository missionGridRepository;

	@Autowired
	private FestivalJsonlReader reader;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private EntityManager em;

	@TempDir
	private Path tempDir;

	private FestivalMissionSeeder newSeeder(boolean enabled, String path) {
		// 프로덕션 기본 생성자(KST 시스템 클럭) 그대로 — "오늘" 필터와 정리 SQL 이 같은 실시간을 본다.
		FestivalMissionSeeder seeder = new FestivalMissionSeeder(missionRepository, missionGridRepository, reader);
		ReflectionTestUtils.setField(seeder, "enabled", enabled);
		ReflectionTestUtils.setField(seeder, "jsonlPath", path);
		return seeder;
	}

	private FestivalMissionSeeder seeder() {
		return newSeeder(true, "unused");
	}

	@Test
	@DisplayName("플래그 off(기본)면 러너는 아무것도 하지 않는다 — 평시 기동 무영향")
	void 플래그_off면_아무것도_하지_않는다() {
		// 파일이 없는 경로 — 게이트가 새면 파일 부재 예외로 즉시 드러난다.
		Path missing = tempDir.resolve("absent.jsonl");
		long before = festivalCount();

		newSeeder(false, missing.toString()).run(emptyArgs());

		assertThat(festivalCount()).isEqualTo(before);
	}

	@Test
	@DisplayName("시드 실행이 EVENT 미션과 격자 81행을 적재한다 — target_count=1·seq NULL·region_code NULL")
	void 시드_실행이_EVENT_미션과_격자_81행을_적재한다() throws IOException {
		String name = unique("서울 합성 축제");
		Path file = writeJsonl("seed.jsonl", activeRow(name, 합성_LAT, 합성_LON));

		FestivalMissionSeeder.SeedResult result = seeder().seed(file);

		assertThat(result.loaded()).isEqualTo(1);
		em.flush();
		em.clear();
		Mission mission = findByTitle(name);
		assertThat(mission.getType()).isEqualTo(MissionType.EVENT);
		assertThat(mission.getTargetCount()).isEqualTo(1);
		assertThat(mission.getRegionCode()).isNull();
		assertThat(mission.getPath()).isNull();
		// 적재 출처 기록 (D7) — 정리·dedupe 가 이 값으로 자기 산출물을 식별한다.
		assertThat(mission.getSource()).isEqualTo(FestivalMissionSeeder.SOURCE_FESTIVAL);
		// KST 00:00:00 / 23:59:59 → UTC 순간으로 저장 (D5) — 변환 계약의 고정 값 검증은 모듈 2 순수 테스트 담당.
		assertThat(mission.getStartAt()).isEqualTo(FestivalMissionSeeder.toUtcStart(시작일));
		assertThat(mission.getEndAt()).isEqualTo(FestivalMissionSeeder.toUtcEnd(종료일));
		// created_at 은 insertable=false 로 DB DEFAULT 위임 — 재조회에서 채워져 있어야 한다.
		assertThat(mission.getCreatedAt()).isNotNull();

		List<MissionGrid> grids = missionGridRepository.findByMissionIds(List.of(mission.getId()));
		assertThat(grids).hasSize(81).allSatisfy(grid -> assertThat(grid.getSeq()).isNull());
		assertThat(grids).extracting(MissionGrid::getGridId).contains(GridEncoder.encode(합성_LAT, 합성_LON));
	}

	@Test
	@DisplayName("같은 파일 재실행은 신규 0건이다 — 중심 격자+기간 dedupe 멱등")
	void 같은_파일_재실행은_신규_0건이다() throws IOException {
		String name = unique("멱등 축제");
		Path file = writeJsonl("idem.jsonl", activeRow(name, 합성_LAT + 0.1, 합성_LON));

		seeder().seed(file);
		FestivalMissionSeeder.SeedResult second = seeder().seed(file);

		assertThat(second.loaded()).isZero();
		assertThat(second.deduped()).isEqualTo(1);
		assertThat(countByTitle(name)).isEqualTo(1);
	}

	@Test
	@DisplayName("종료 축제가 정리되고 mission_grids 가 CASCADE 로 사라진다")
	void 종료_축제가_정리되고_mission_grids가_CASCADE로_사라진다() throws IOException {
		long endedId = insertFestival(unique("끝난 축제"), nowUtc().minusDays(10), nowUtc().minusDays(1));
		insertMissionGrid(endedId, "-41741_110415");
		Path file = writeJsonl("refresh.jsonl", activeRow(unique("진행 축제"), 합성_LAT + 0.2, 합성_LON));

		FestivalMissionSeeder.SeedResult result = seeder().seed(file);

		assertThat(result.removed()).isGreaterThanOrEqualTo(1);
		assertThat(missionRepository.findById(endedId)).isEmpty();
		assertThat(gridCountOf(endedId)).isZero();
	}

	@Test
	@DisplayName("스탬프 걸린 종료 미션은 삭제되지 않는다 — V6 FK(NO ACTION) 보호")
	void 스탬프_걸린_종료_미션은_삭제되지_않는다() {
		long userId = userRepository.save(
			User.createLocalUser("msg224-" + System.nanoTime() + "@example.com", "hash", "시더테스터")).getId();
		long endedId = insertFestival(unique("스탬프 종료"), nowUtc().minusDays(10), nowUtc().minusDays(1));
		insertStamp(userId, endedId);

		missionRepository.deleteEndedBySourceWithoutStamps(FestivalMissionSeeder.SOURCE_FESTIVAL);

		assertThat(missionRepository.findById(endedId)).isPresent();
	}

	@Test
	@DisplayName("타 소스와 수동 미션은 정리에서 불가침이다 — 종료 1격자 EVENT(source NULL)·무기간 코스 잔존")
	void 타_소스와_수동_미션은_정리에서_불가침이다() {
		// 팝업 모사: 종료된 1격자 EVENT, source NULL — 공유 EVENT 타입이라도 축제 정리가 못 건드린다 (D7).
		long popup = insertMission("EVENT", unique("끝난 팝업 모사"), nowUtc().minusDays(10), nowUtc().minusDays(1));
		insertMissionGrid(popup, "-41742_110415");
		long course = insertMission("COURSE", unique("무기간 코스"), null, null);

		missionRepository.deleteEndedBySourceWithoutStamps(FestivalMissionSeeder.SOURCE_FESTIVAL);

		assertThat(missionRepository.findById(popup)).isPresent();
		assertThat(missionRepository.findById(course)).isPresent();
	}

	@Test
	@DisplayName("1격자 EVENT 는 dedupe 대조에 포함되지 않는다 — 가짜 중심 키가 실축제를 오스킵하지 않는다")
	void 일격자_EVENT는_dedupe_대조에_포함되지_않는다() throws IOException {
		// 팝업 모사(1격자 EVENT, source NULL)를 축제 중심의 (-4, -4) 격자·같은 기간에 둔다 — type 대조라면
		// min+4 복원이 정확히 축제 중심을 가리키는 가짜 키가 돼 실축제를 오스킵한다 (D3·D7, Codex 리뷰 파생).
		double lat = 합성_LAT + 0.3;
		GridEncoder.GridIndex center = GridEncoder.decode(GridEncoder.encode(lat, 합성_LON));
		long popup = insertMission("EVENT", unique("팝업 모사"),
			FestivalMissionSeeder.toUtcStart(시작일), FestivalMissionSeeder.toUtcEnd(종료일));
		insertMissionGrid(popup, (center.gridY() - 4) + "_" + (center.gridX() - 4));
		String name = unique("같은 자리 축제");
		Path file = writeJsonl("popup.jsonl", activeRow(name, lat, 합성_LON));

		FestivalMissionSeeder.SeedResult result = seeder().seed(file);

		assertThat(result.loaded()).isEqualTo(1);
		assertThat(countByTitle(name)).isEqualTo(1);
	}

	@Test
	@DisplayName("종료 판정이 KST 세션에서도 스큐 없이 동작한다 — AT TIME ZONE 'UTC' (MSG-223 §D2 규칙)")
	void 종료_판정이_KST_세션에서도_스큐없이_동작한다() {
		// 세션 타임존을 KST 로 강제 — 나이브 비교면 statement_timestamp() 가 UTC+9 로 캐스트돼
		// 1시간 뒤 종료 예정 미션이 "9시간 전 종료"로 오판·오삭제된다 (SET LOCAL 은 이 트랜잭션 한정).
		em.createNativeQuery("SET LOCAL TIME ZONE 'Asia/Seoul'").executeUpdate();
		long stillActive = insertFestival(unique("한시간 뒤 종료"), nowUtc().minusDays(1), nowUtc().plusHours(1));
		long ended = insertFestival(unique("한시간 전 종료"), nowUtc().minusDays(1), nowUtc().minusHours(1));

		missionRepository.deleteEndedBySourceWithoutStamps(FestivalMissionSeeder.SOURCE_FESTIVAL);

		assertThat(missionRepository.findById(stillActive)).isPresent();
		assertThat(missionRepository.findById(ended)).isEmpty();
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
	@DisplayName("유효 0건이면 예외로 조기 실패한다 — 전부 종료된 스냅샷 방어 (FR-5)")
	void 유효_0건이면_예외로_조기_실패한다() throws IOException {
		Path file = writeJsonl("stale.jsonl", row(unique("옛날 축제"), 합성_LAT, 합성_LON, "2020-01-18", "2020-01-27"));

		assertThatThrownBy(() -> seeder().seed(file))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("0건");
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DisplayName("중간 실패 시 전체 롤백으로 기존 데이터가 유지된다 — INSERT·DELETE 단일 트랜잭션 (FR-5)")
	void 중간_실패_시_전체_롤백으로_기존_데이터가_유지된다() throws IOException {
		String survivor = unique("먼저 적재됨");
		// 독약 행: 위도가 비정상적으로 커서 grid_id 가 VARCHAR(20) 을 넘는다 → 두 번째 미션의 격자 INSERT 실패.
		Path file = writeJsonl("poison.jsonl",
			activeRow(survivor, 합성_LAT, 합성_LON),
			activeRow(unique("독약 행"), 4.5e14, 합성_LON));
		TransactionTemplate tx = new TransactionTemplate(transactionManager);

		assertThatThrownBy(() -> tx.executeWithoutResult(status -> seeder().seed(file)))
			.isInstanceOf(RuntimeException.class);

		// 먼저 INSERT 된 정상 행까지 함께 롤백 — 부분 적재로 남지 않는다.
		assertThat(countByTitle(survivor)).isZero();
	}

	private String unique(String tag) {
		return "MSG224-it-" + tag + "-" + System.nanoTime();
	}

	private LocalDateTime nowUtc() {
		return LocalDateTime.now(ZoneOffset.UTC);
	}

	private long festivalCount() {
		return missionRepository.findBySource(FestivalMissionSeeder.SOURCE_FESTIVAL).size();
	}

	private Mission findByTitle(String title) {
		return missionRepository.findBySource(FestivalMissionSeeder.SOURCE_FESTIVAL).stream()
			.filter(mission -> mission.getTitle().equals(title))
			.findFirst()
			.orElseThrow();
	}

	private long countByTitle(String title) {
		return missionRepository.findBySource(FestivalMissionSeeder.SOURCE_FESTIVAL).stream()
			.filter(mission -> mission.getTitle().equals(title))
			.count();
	}

	private Path writeJsonl(String filename, String... rows) throws IOException {
		Path file = tempDir.resolve(filename);
		Files.writeString(file, String.join("\n", rows));
		return file;
	}

	/** 진행 중(오늘 ±5일) 축제 1행. */
	private static String activeRow(String name, double lat, double lon) {
		return row(name, lat, lon, 시작일.toString(), 종료일.toString());
	}

	/** 실측 스키마(D1) 형태의 jsonl 1행. */
	private static String row(String name, double lat, double lon, String startDate, String endDate) {
		return """
			{"name": "%s", "place": "행사장 일원", "startDate": "%s", "endDate": "%s", \
			"latitude": %s, "longitude": %s, "sourceOrg": "합성_문화축제"}"""
			.formatted(name, startDate, endDate, lat, lon);
	}

	/** source NULL(수동/타 러너 모사) 미션 — 축제 정리·dedupe 의 불가침 대상 픽스처 (D7). */
	private long insertMission(String type, String title, LocalDateTime startAt, LocalDateTime endAt) {
		return insertMissionRow(type, title, startAt, endAt, null);
	}

	/** 축제 러너 산출물 모사 — source='FESTIVAL' 로 정리 대상이 되는 픽스처 (D7). */
	private long insertFestival(String title, LocalDateTime startAt, LocalDateTime endAt) {
		return insertMissionRow("EVENT", title, startAt, endAt, FestivalMissionSeeder.SOURCE_FESTIVAL);
	}

	private long insertMissionRow(String type, String title, LocalDateTime startAt, LocalDateTime endAt,
		String source) {
		em.createNativeQuery("""
				INSERT INTO missions (type, title, start_at, end_at, target_count, source)
				VALUES (:type, :title, :startAt, :endAt, 1, :source)
				""")
			.setParameter("type", type)
			.setParameter("title", title)
			.setParameter("startAt", startAt)
			.setParameter("endAt", endAt)
			.setParameter("source", source)
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
