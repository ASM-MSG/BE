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

import com.msg.fillmap.global.config.AwsProperties;
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
 * 격리(공유 로컬 DB): 합성 이름(MSG224-it-*)·서해 먼바다 합성 좌표만 쓰고 @Transactional 롤백. 롤백 검증만
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
	// 서해 먼바다 합성 좌표 (MSG-384 D6) — 남반구 좌표를 쓰던 자리다. 리더가 KoreaCoordinates 범위 밖 행을
	// 건너뛰게 되면서 남반구 픽스처가 전부 스킵돼, 프로덕션 입력 검증을 포기하는 대신 좌표를 옮겼다.
	// 오프셋(+0.1~+1.5)을 더해도 35.5라 범위(33~39N) 안이고, 축제·팝업·코스 데이터가 전부 육상이라
	// 바다 셀과 격자를 공유하지 않는다 (팝업 합성 좌표는 36.35/127.38).
	private static final double 합성_LAT = 34.0;
	private static final double 합성_LON = 125.0;

	@Autowired
	private MissionRepository missionRepository;

	@Autowired
	private MissionGridRepository missionGridRepository;

	@Autowired
	private FestivalJsonlReader reader;

	@Autowired
	private AwsProperties awsProperties;

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
		FestivalMissionSeeder seeder = new FestivalMissionSeeder(missionRepository, missionGridRepository, reader,
			awsProperties);
		ReflectionTestUtils.setField(seeder, "enabled", enabled);
		ReflectionTestUtils.setField(seeder, "jsonlPath", path);
		return seeder;
	}

	private FestivalMissionSeeder seeder() {
		return newSeeder(true, "unused");
	}

	// 검증: FR-MISSION-11
	@Test
	@DisplayName("플래그 off(기본)면 러너는 아무것도 하지 않는다 — 평시 기동 무영향")
	void 플래그_off면_아무것도_하지_않는다() {
		// 파일이 없는 경로 — 게이트가 새면 파일 부재 예외로 즉시 드러난다.
		Path missing = tempDir.resolve("absent.jsonl");
		long before = festivalCount();

		newSeeder(false, missing.toString()).run(emptyArgs());

		assertThat(festivalCount()).isEqualTo(before);
	}

	// 검증: FR-MISSION-08
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

	// 검증: FR-MISSION-08
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

	// 검증: FR-MISSION-04
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

	// 검증: FR-MISSION-08
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

	// 검증: FR-MISSION-16
	@Test
	@DisplayName("축제 미션에 설명과 장소와 원문 링크가 적재된다 — V31 메타데이터 (MSG-383 D3)")
	void 축제_미션에_설명과_장소와_원문_링크가_적재된다() throws IOException {
		String name = unique("메타데이터 축제");
		Path file = writeJsonl("meta.jsonl", metadataRow(name, 합성_LAT + 0.4, 합성_LON,
			"불꽃놀이와 야시장", "여의도 한강공원 일원", "https://festival.example.kr"));

		seeder().seed(file);

		em.flush();
		em.clear();
		Mission mission = findByTitle(name);
		assertThat(mission.getDescription()).isEqualTo("불꽃놀이와 야시장");
		assertThat(mission.getPlaceName()).isEqualTo("여의도 한강공원 일원");
		assertThat(mission.getSourceUrl()).isEqualTo("https://festival.example.kr");
		// 축제에 없는 개념 — 운영시간·코스 지표는 null 이다 (D3).
		assertThat(mission.getOperationTime()).isNull();
		assertThat(mission.getDistanceMeters()).isNull();
		assertThat(mission.getDurationMinutes()).isNull();
		assertThat(mission.getDifficulty()).isNull();
	}

	// 검증: FR-MISSION-08 (이미지 미러링은 SRS 등재로 NFR DATA 07)
	@Test
	@DisplayName("축제 미션에 대표 이미지 주소가 적재된다 — 버킷 상대 키를 공개 주소로 조립 (MSG-384 D2)")
	void 축제_미션에_대표_이미지_주소가_적재된다() throws IOException {
		String name = unique("이미지 있는 축제");
		String imageKey = "missions/festival/2732106-a1b2c3d4.jpg";
		Path file = writeJsonl("image-url.jsonl", imageRow(name, 합성_LAT + 1.0, 합성_LON, "설명", imageKey));

		seeder().seed(file);

		em.flush();
		em.clear();
		// 파일은 키만 담고 주소는 시더가 자기 환경의 버킷·리전으로 조립한다 — 외부 도메인이 저장될 경로가 없다.
		assertThat(findByTitle(name).getImageUrl()).isEqualTo("https://%s.s3.%s.amazonaws.com/%s"
			.formatted(awsProperties.s3().bucket(), awsProperties.region(), imageKey));
	}

	// 검증: FR-MISSION-08
	@Test
	@DisplayName("대표 이미지가 없는 축제도 정상 적재된다 — image_url NULL (실측 461건 중 5건)")
	void 대표_이미지가_없는_축제도_정상_적재된다() throws IOException {
		String name = unique("이미지 없는 축제");
		Path file = writeJsonl("image.jsonl", metadataRow(name, 합성_LAT + 0.5, 합성_LON,
			"설명", "장소", "https://festival.example.kr"));

		FestivalMissionSeeder.SeedResult result = seeder().seed(file);

		assertThat(result.loaded()).isEqualTo(1);
		em.flush();
		em.clear();
		assertThat(findByTitle(name).getImageUrl()).isNull();
	}

	// 검증: FR-MISSION-08 (이미지 미러링은 SRS 등재로 NFR DATA 07)
	@Test
	@DisplayName("새 스냅샷에 이미지가 없어도 이미 채운 이미지는 유지된다 — 이미지 한 필드 예외 (MSG-384 D2)")
	void 새_스냅샷에_이미지가_없어도_이미_채운_이미지는_유지된다() throws IOException {
		// Given: 이미지가 채워진 미션. 원본이 내려가 imageKey 가 빈 스냅샷이 와도 우리 버킷 사본은 살아 있다.
		String name = unique("이미지 보존 축제");
		double lat = 합성_LAT + 1.1;
		String imageKey = "missions/festival/2732107-deadbeef.jpg";
		seeder().seed(writeJsonl("keep-first.jsonl", imageRow(name, lat, 합성_LON, "첫 설명", imageKey)));
		em.flush();
		em.clear();
		String imageUrlBefore = findByTitle(name).getImageUrl();
		assertThat(imageUrlBefore).isNotNull();

		// When: 같은 키(중심 격자+기간)에 imageKey 만 빠지고 소개문이 바뀐 파일로 갱신.
		seeder().seed(writeJsonl("keep-second.jsonl", imageRow(name, lat, 합성_LON, "바뀐 설명", null)));

		em.flush();
		em.clear();
		Mission after = findByTitle(name);
		assertThat(after.getImageUrl()).isEqualTo(imageUrlBefore);
		// 예외는 이미지에만 걸린다 — 소개문은 외부 원본을 비추는 값이라 새 스냅샷으로 덮인다.
		assertThat(after.getDescription()).isEqualTo("바뀐 설명");
	}

	// 검증: FR-MISSION-16
	@Test
	@DisplayName("이미 적재된 미션에 재실행하면 메타데이터만 채워진다 — 재실행이 곧 백필 (D6)")
	void 이미_적재된_미션에_재실행하면_메타데이터만_채워진다() throws IOException {
		// Given: 메타데이터 없는 파일로 적재된 미션 (V31 이전 상태 재현).
		String name = unique("백필 대상 축제");
		double lat = 합성_LAT + 0.6;
		Path bare = writeJsonl("bare.jsonl", activeRow(name, lat, 합성_LON));
		seeder().seed(bare);
		em.flush();
		em.clear();
		Mission before = findByTitle(name);
		long missionId = before.getId();
		long gridsBefore = gridCountOf(missionId);
		assertThat(before.getDescription()).isNull();

		// When: 같은 키(중심 격자+기간)에 메타데이터가 붙은 파일로 재실행.
		Path filled = writeJsonl("filled.jsonl",
			metadataRow(name, lat, 합성_LON, "채워진 설명", "채워진 장소", "https://filled.example.kr"));
		FestivalMissionSeeder.SeedResult result = seeder().seed(filled);

		assertThat(result.loaded()).isZero();
		em.flush();
		em.clear();
		Mission after = missionRepository.findById(missionId).orElseThrow();
		assertThat(after.getDescription()).isEqualTo("채워진 설명");
		assertThat(after.getPlaceName()).isEqualTo("채워진 장소");
		assertThat(after.getSourceUrl()).isEqualTo("https://filled.example.kr");
		// Then: 미션 정체성은 한 글자도 바뀌지 않는다 — 제목·기간·격자 수·source (D6).
		assertThat(after.getTitle()).isEqualTo(before.getTitle());
		assertThat(after.getStartAt()).isEqualTo(before.getStartAt());
		assertThat(after.getEndAt()).isEqualTo(before.getEndAt());
		assertThat(after.getSource()).isEqualTo(before.getSource());
		assertThat(gridCountOf(missionId)).isEqualTo(gridsBefore);
	}

	// 검증: FR-MISSION-16
	@Test
	@DisplayName("재실행 결과에 갱신 건수가 집계된다 — 적재 0건만 보고 오해하지 않게 (D6)")
	void 재실행_결과에_갱신_건수가_집계된다() throws IOException {
		String name = unique("갱신 집계 축제");
		double lat = 합성_LAT + 0.7;
		Path bare = writeJsonl("count-bare.jsonl", activeRow(name, lat, 합성_LON));
		Path filled = writeJsonl("count-filled.jsonl",
			metadataRow(name, lat, 합성_LON, "설명", "장소", "https://festival.example.kr"));
		seeder().seed(bare);

		FestivalMissionSeeder.SeedResult filledRun = seeder().seed(filled);
		FestivalMissionSeeder.SeedResult sameRun = seeder().seed(filled);

		assertThat(filledRun.updated()).isEqualTo(1);
		// 값이 이미 같으면 갱신으로 세지 않는다 — 재실행이 같은 상태로 수렴한다(NFR-DATA-03).
		assertThat(sameRun.updated()).isZero();
		assertThat(sameRun.deduped()).isEqualTo(1);
	}

	// 검증: FR-MISSION-08, FR-MISSION-11
	@Test
	@DisplayName("실행 결과에 갱신된 미션 id 가 담긴다 — 전환 삭제 제외 목록 (MSG-384 D1)")
	void 실행_결과에_갱신된_미션_id가_담긴다() throws IOException {
		String name = unique("전환 갱신 축제");
		double lat = 합성_LAT + 1.2;
		Path bare = writeJsonl("kept-bare.jsonl", activeRow(name, lat, 합성_LON));
		Path filled = writeJsonl("kept-filled.jsonl",
			metadataRow(name, lat, 합성_LON, "설명", "장소", "https://festival.example.kr"));
		seeder().seed(bare);
		em.flush();
		em.clear();
		long missionId = findByTitle(name).getId();

		FestivalMissionSeeder.SeedResult filledRun = seeder().seed(filled);
		FestivalMissionSeeder.SeedResult sameRun = seeder().seed(filled);

		// 운영자가 이 id 를 삭제 조건의 NOT IN 으로 옮겨 적는다 — 제자리 갱신된 행은 대체 INSERT 가 없어서
		// 빠뜨리면 그 축제가 통째로 사라진다.
		assertThat(filledRun.updatedIds()).contains(missionId);
		// 값이 하나도 안 바뀐 실행에서도 목록에는 남는다 — 삭제 제외 기준은 "값 변화"가 아니라 "키가 맞았나"다.
		assertThat(sameRun.updated()).isZero();
		assertThat(sameRun.updatedIds()).contains(missionId);
	}

	// 검증: FR-MISSION-11
	@Test
	@DisplayName("필수 필드 오류 행이 있어도 나머지는 적재된다 — 관대한 skip (MSG-384 D6)")
	void 필수_필드_오류_행이_있어도_나머지는_적재된다() throws IOException {
		String name = unique("멀쩡한 축제");
		Path file = writeJsonl("required.jsonl",
			row("", 합성_LAT + 1.3, 합성_LON, 시작일.toString(), 종료일.toString()),
			row(unique("좌표 밖 축제"), 0, 0, 시작일.toString(), 종료일.toString()),
			activeRow(name, 합성_LAT + 1.3, 합성_LON));

		FestivalMissionSeeder.SeedResult result = seeder().seed(file);

		assertThat(result.loaded()).isEqualTo(1);
		assertThat(countByTitle(name)).isEqualTo(1);
	}

	// 검증: FR-MISSION-11
	@Test
	@DisplayName("실행 결과에 필수 필드 오류 건수가 집계된다 — 오적재 대신 건너뛴 행을 운영자가 센다")
	void 실행_결과에_필수_필드_오류_건수가_집계된다() throws IOException {
		Path file = writeJsonl("required-count.jsonl",
			row("", 합성_LAT + 1.4, 합성_LON, 시작일.toString(), 종료일.toString()),
			row(unique("좌표 밖 축제"), 0, 0, 시작일.toString(), 종료일.toString()),
			activeRow(unique("멀쩡한 축제"), 합성_LAT + 1.4, 합성_LON));

		FestivalMissionSeeder.SeedResult result = seeder().seed(file);

		assertThat(result.skippedRequiredField()).isEqualTo(2);
	}

	// 검증: FR-MISSION-04, FR-MISSION-16
	@Test
	@DisplayName("재실행이 스탬프를 건드리지 않는다 — user_missions 행 수 불변 (D6)")
	void 재실행이_스탬프를_건드리지_않는다() throws IOException {
		long userId = userRepository.save(
			User.createLocalUser("msg383-" + System.nanoTime() + "@example.com", "hash", "백필테스터")).getId();
		String name = unique("스탬프 걸린 축제");
		double lat = 합성_LAT + 0.8;
		Path bare = writeJsonl("stamp-bare.jsonl", activeRow(name, lat, 합성_LON));
		seeder().seed(bare);
		em.flush();
		em.clear();
		long missionId = findByTitle(name).getId();
		insertStamp(userId, missionId);

		seeder().seed(writeJsonl("stamp-filled.jsonl",
			metadataRow(name, lat, 합성_LON, "설명", "장소", "https://festival.example.kr")));

		// 갱신은 missions 만 건드린다 — user_missions 는 mission_id 만 참조하고 그 id 가 바뀌지 않는다.
		assertThat(userMissionCount(userId)).isEqualTo(1);
		em.flush();
		em.clear();
		assertThat(missionRepository.findById(missionId).orElseThrow().getDescription()).isEqualTo("설명");
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
	@DisplayName("유효 0건이면 예외로 조기 실패한다 — 전부 종료된 스냅샷 방어 (FR-5)")
	void 유효_0건이면_예외로_조기_실패한다() throws IOException {
		Path file = writeJsonl("stale.jsonl", row(unique("옛날 축제"), 합성_LAT, 합성_LON, "2020-01-18", "2020-01-27"));

		assertThatThrownBy(() -> seeder().seed(file))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("0건");
	}

	// 검증: FR-MISSION-11
	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DisplayName("중간 실패 시 전체 롤백으로 기존 데이터가 유지된다 — INSERT·DELETE 단일 트랜잭션 (FR-5)")
	void 중간_실패_시_전체_롤백으로_기존_데이터가_유지된다() throws IOException {
		String survivor = unique("먼저 적재됨");
		// 독약 행: 제목에 NUL 문자가 있어 PostgreSQL 이 INSERT 를 거부한다 → 두 번째 미션의 영속화 실패.
		// 위도 4.5e14(grid_id 길이 초과)를 쓰던 자리다 — 그 행은 이제 좌표 범위 검증에 걸려 파싱 단계에서
		// 건너뛰어지므로 예외를 못 만든다. 예외가 나는 자리를 예전과 같은 영속화 단계로 유지한다 (MSG-384 D6).
		Path file = writeJsonl("poison.jsonl",
			activeRow(survivor, 합성_LAT, 합성_LON),
			activeRow(unique("독약 행") + "\\u0000", 합성_LAT + 1.5, 합성_LON));
		TransactionTemplate tx = new TransactionTemplate(transactionManager);

		assertThatThrownBy(() -> tx.executeWithoutResult(status -> seeder().seed(file)))
			.isInstanceOf(RuntimeException.class);

		// 먼저 INSERT 된 정상 행까지 함께 롤백 — 부분 적재로 남지 않는다.
		assertThat(countByTitle(survivor)).isZero();
	}

	// 검증: FR-MISSION-21
	@Test
	@DisplayName("축제_81칸_미션은_대표_격자가_9x9_정중앙이다 — 리졸버 1단 (MSG-459)")
	void 축제_81칸_미션은_대표_격자가_9x9_정중앙이다() throws IOException {
		String name = unique("대표 격자 축제");
		double lat = 합성_LAT + 0.6;
		Path file = writeJsonl("rep-grid.jsonl", activeRow(name, lat, 합성_LON));

		seeder().seed(file);

		em.flush();
		em.clear();
		// 9×9 의 정중앙은 중심 좌표가 속한 셀 그 자체다 — 시더가 아는 값이지만 산출은 리졸버를 지난다.
		assertThat(findByTitle(name).getRepresentativeGridId()).isEqualTo(GridEncoder.encode(lat, 합성_LON));
	}

	// 검증: FR-MISSION-21
	@Test
	@DisplayName("같은_시드를_다시_적재해도_대표_격자가_바뀌지_않는다 — 산출이 결정적이라 재적재가 곧 멱등 (MSG-459)")
	void 같은_시드를_다시_적재해도_대표_격자가_바뀌지_않는다() throws IOException {
		String name = unique("멱등 대표 격자");
		Path file = writeJsonl("rep-idem.jsonl", activeRow(name, 합성_LAT + 0.7, 합성_LON));
		seeder().seed(file);
		em.flush();
		em.clear();
		String first = findByTitle(name).getRepresentativeGridId();

		seeder().seed(file);

		em.flush();
		em.clear();
		assertThat(findByTitle(name).getRepresentativeGridId()).isEqualTo(first);
	}

	// 검증: FR-MISSION-21
	@Test
	@DisplayName("대표_격자가_비어_있는_기존_미션은_시더_재실행이_채운다 — 백필에서 빠진 행의 회수 경로 (MSG-459)")
	void 대표_격자가_비어_있는_기존_미션은_시더_재실행이_채운다() throws IOException {
		String name = unique("대표 격자 미백필");
		double lat = 합성_LAT + 0.8;
		// V42 백필 조건을 통과하지 못해 NULL 로 남은 행을 모사한다 — 격자·기간은 시드 레코드와 같은 키다.
		long missionId = insertFestivalWithGrids(name, lat, 합성_LON, 1);

		seeder().seed(writeJsonl("rep-backfill.jsonl", activeRow(name, lat, 합성_LON)));

		em.flush();
		em.clear();
		assertThat(missionRepository.findById(missionId)).get()
			.extracting(Mission::getRepresentativeGridId)
			.isEqualTo(GridEncoder.encode(lat, 합성_LON));
	}

	// 검증: FR-MISSION-21
	@Test
	@DisplayName("목표_칸수가_2_이상인_미션은_시더가_대표_격자를_채우지_않는다 — CHECK 위반으로 시딩 전체가 롤백되는 걸 막는다")
	void 목표_칸수가_2_이상인_미션은_시더가_대표_격자를_채우지_않는다() throws IOException {
		String skipped = unique("목표 2 축제");
		double lat = 합성_LAT + 0.9;
		long missionId = insertFestivalWithGrids(skipped, lat, 합성_LON, 2);
		String loaded = unique("정상 적재 축제");

		FestivalMissionSeeder.SeedResult result = seeder().seed(writeJsonl("rep-target2.jsonl",
			activeRow(skipped, lat, 합성_LON),
			activeRow(loaded, 합성_LAT + 1.0, 합성_LON)));

		// 목표 2 행은 NULL 로 남고, 같은 실행의 나머지 적재는 정상 커밋된다(플러시가 통과하는 것이 그 증거).
		assertThat(result.loaded()).isEqualTo(1);
		em.flush();
		em.clear();
		assertThat(missionRepository.findById(missionId)).get()
			.extracting(Mission::getRepresentativeGridId)
			.isNull();
		assertThat(findByTitle(loaded).getRepresentativeGridId()).isNotNull();
	}

	/**
	 * 대표 격자가 비어 있는 기존 축제 픽스처 — 미션 1행 + 중심 기준 81칸. dedupe 키(중심 격자 + 기간)가
	 * {@code activeRow(name, lat, lon)} 과 같아 시더 재실행이 이 행을 갱신 경로로 잡는다.
	 */
	private long insertFestivalWithGrids(String title, double lat, double lon, int targetCount) {
		em.createNativeQuery("""
				INSERT INTO missions (type, title, start_at, end_at, target_count, source)
				VALUES ('EVENT', :title, :startAt, :endAt, :targetCount, :source)
				""")
			.setParameter("title", title)
			.setParameter("startAt", FestivalMissionSeeder.toUtcStart(시작일))
			.setParameter("endAt", FestivalMissionSeeder.toUtcEnd(종료일))
			.setParameter("targetCount", targetCount)
			.setParameter("source", FestivalMissionSeeder.SOURCE_FESTIVAL)
			.executeUpdate();
		long missionId = ((Number) em.createNativeQuery("SELECT id FROM missions WHERE title = :title")
			.setParameter("title", title)
			.getSingleResult()).longValue();
		FestivalMissionSeeder.expandGrids(GridEncoder.encode(lat, lon))
			.forEach(gridId -> insertMissionGrid(missionId, gridId));
		return missionId;
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

	/** 대표 이미지 키(MSG-384 D2)까지 채운 진행 중 축제 1행 — imageKey null 은 빈 문자열(= 결측)로 쓴다. */
	private static String imageRow(String name, double lat, double lon, String description, String imageKey) {
		return """
			{"name": "%s", "place": "행사장 일원", "startDate": "%s", "endDate": "%s", "description": "%s", \
			"latitude": %s, "longitude": %s, "imageKey": "%s"}"""
			.formatted(name, 시작일, 종료일, description, lat, lon, imageKey == null ? "" : imageKey);
	}

	/** 화면용 필드(MSG-383 D3)까지 채운 진행 중 축제 1행. */
	private static String metadataRow(String name, double lat, double lon, String description, String place,
		String homepage) {
		return """
			{"name": "%s", "place": "%s", "startDate": "%s", "endDate": "%s", "description": "%s", \
			"latitude": %s, "longitude": %s, "homepage": "%s", "sourceOrg": "합성_문화축제"}"""
			.formatted(name, place, 시작일, 종료일, description, lat, lon, homepage);
	}

	private long userMissionCount(long userId) {
		return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM user_missions WHERE user_id = :userId")
			.setParameter("userId", userId)
			.getSingleResult()).longValue();
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
