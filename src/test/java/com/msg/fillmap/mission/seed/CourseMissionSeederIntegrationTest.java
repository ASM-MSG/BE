package com.msg.fillmap.mission.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
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

import tools.jackson.databind.ObjectMapper;

import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.mission.entity.Mission;
import com.msg.fillmap.mission.entity.MissionGrid;
import com.msg.fillmap.mission.entity.MissionType;
import com.msg.fillmap.mission.repository.MissionGridRepository;
import com.msg.fillmap.mission.repository.MissionRepository;

/**
 * 코스 시더 러너 통합 (MSG-225 모듈 3, 실 PostgreSQL — FestivalMissionSeederIntegrationTest 선례).
 * 플래그 게이트 · 무기간 COURSE 적재 shape(FR-6·9) · 제목 dedupe 멱등(D6) · 소유권 불가침 ·
 * 조기 실패/롤백을 본다. 검증은 reader 가 INSERT 이전에 던지므로 부분 적재 경로 자체가 없다.
 *
 * 격리(공유 로컬 DB): 합성 제목(MSG225-it-*)·합성 격자 인덱스만 쓰고 @Transactional 롤백.
 */
@SpringBootTest
@Transactional
@DisplayName("CourseMissionSeeder 러너 통합 (실 PostgreSQL) — 합성 제목·롤백 격리")
class CourseMissionSeederIntegrationTest {

	private static final String PATH_JSON = """
		{"type": "LineString", "coordinates": [[129.03597, 35.09656], [129.03642, 35.09721]]}""";

	@Autowired
	private com.msg.fillmap.zone.service.ZoneNameQueryService zoneNameQueryService;

	@Autowired
	private com.msg.fillmap.region.service.RegionQueryService regionQueryService;

	@Autowired
	private MissionRepository missionRepository;

	@Autowired
	private MissionGridRepository missionGridRepository;

	@Autowired
	private CourseSeedReader reader;

	@Autowired
	private AwsProperties awsProperties;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private EntityManager em;

	@TempDir
	private Path tempDir;

	private CourseMissionSeeder newSeeder(boolean enabled, String path) {
		CourseMissionSeeder seeder = new CourseMissionSeeder(missionRepository, missionGridRepository, reader,
			awsProperties, zoneNameQueryService, regionQueryService);
		ReflectionTestUtils.setField(seeder, "enabled", enabled);
		ReflectionTestUtils.setField(seeder, "seedPath", path);
		return seeder;
	}

	private CourseMissionSeeder seeder() {
		return newSeeder(true, "unused");
	}

	// 검증: FR-MISSION-11
	@Test
	@DisplayName("플래그 off(기본)면 러너는 아무것도 하지 않는다 — 평시 기동 무영향")
	void 플래그_off면_아무것도_하지_않는다() {
		// 파일이 없는 경로 — 게이트가 새면 파일 부재 예외로 즉시 드러난다.
		Path missing = tempDir.resolve("absent.json");
		long before = courseCount();

		newSeeder(false, missing.toString()).run(emptyArgs());

		assertThat(courseCount()).isEqualTo(before);
	}

	// 검증: FR-MISSION-09
	@Test
	@DisplayName("시드 실행이 무기간 COURSE 미션과 스팟 격자를 적재한다 — NULL 기간·target 3·source DURUNUBI·seq 1..N")
	void 시드_실행이_무기간_COURSE_미션과_스팟_격자를_적재한다() throws IOException {
		String title = unique("남파랑길 합성 코스");
		Path file = writeSeed("seed.json", course("T_IT_1", title, 5));

		CourseMissionSeeder.SeedResult result = seeder().seed(file);

		assertThat(result.loaded()).isEqualTo(1);
		em.flush();
		em.clear();
		Mission mission = findByTitle(title);
		assertThat(mission.getType()).isEqualTo(MissionType.COURSE);
		assertThat(mission.getStartAt()).isNull();
		assertThat(mission.getEndAt()).isNull();
		assertThat(mission.getTargetCount()).isEqualTo(3);
		assertThat(mission.getRegionCode()).isNull();
		assertThat(mission.getSource()).isEqualTo(CourseMissionSeeder.SOURCE_DURUNUBI);
		assertThat(mission.getCreatedAt()).isNotNull();
		// path 원문 저장 (D5) — jsonb 정규화가 있어 문자열이 아니라 JSON 동등성으로 비교한다.
		assertThat(objectMapper.readTree(mission.getPath())).isEqualTo(objectMapper.readTree(PATH_JSON));

		List<MissionGrid> spots = missionGridRepository.findByMissionIds(List.of(mission.getId())).stream()
			.sorted(Comparator.comparing(MissionGrid::getSeq))
			.toList();
		assertThat(spots).hasSize(5);
		assertThat(spots).extracting(MissionGrid::getSeq).containsExactly(1, 2, 3, 4, 5);
		assertThat(spots.get(0).getGridId()).isEqualTo("-39001_112198");
	}

	// 검증: FR-MISSION-11
	@Test
	@DisplayName("같은 산출물 재실행은 신규 0건이다 — 제목 dedupe 멱등")
	void 같은_산출물_재실행은_신규_0건이다() throws IOException {
		String title = unique("멱등 코스");
		Path file = writeSeed("idem.json", course("T_IT_2", title, 5));

		seeder().seed(file);
		CourseMissionSeeder.SeedResult second = seeder().seed(file);

		assertThat(second.loaded()).isZero();
		assertThat(second.deduped()).isEqualTo(1);
		assertThat(countByTitle(title)).isEqualTo(1);
	}

	@Test
	@DisplayName("타 소스와 수동 미션은 dedupe 대조에 포함되지 않는다 — 같은 제목의 source NULL 미션과 별건")
	void 타_소스와_수동_미션은_dedupe_대조에_포함되지_않는다() throws IOException {
		// 같은 제목의 수동(source NULL) 미션이 있어도 코스는 정상 적재된다 — 소유권 원칙(MSG-224 D7 동일).
		String title = unique("동명 수동 미션");
		insertManualMission(title);
		Path file = writeSeed("manual.json", course("T_IT_3", title, 5));

		CourseMissionSeeder.SeedResult result = seeder().seed(file);

		assertThat(result.loaded()).isEqualTo(1);
		assertThat(countByTitle(title)).isEqualTo(1);
	}

	// 검증: FR-MISSION-16
	@Test
	@DisplayName("코스 미션에 거리와 소요시간과 난이도가 적재된다 — V31 메타데이터 (MSG-383 D3)")
	void 코스_미션에_거리와_소요시간과_난이도가_적재된다() throws IOException {
		String title = unique("메타데이터 코스");
		Path file = writeSeed("meta.json", courseWithMetadata("T_IT_META", title,
			"바다를 따라 걷는다<br>전망대가 있다", "부산 영도구", "\"14\"", "\"330\"", "\"2\""));

		CourseMissionSeeder.SeedResult result = seeder().seed(file);

		assertThat(result.missingMetadata()).isZero();
		em.flush();
		em.clear();
		Mission mission = findByTitle(title);
		assertThat(mission.getDistanceMeters()).isEqualTo(14000);
		assertThat(mission.getDurationMinutes()).isEqualTo(330);
		assertThat(mission.getDifficulty()).isEqualTo(2);
		assertThat(mission.getPlaceName()).isEqualTo("부산 영도구");
		// <br> 이 개행으로 정규화돼 저장된다 — 저장값에 태그 문자열이 남지 않는다 (D5).
		assertThat(mission.getDescription()).isEqualTo("바다를 따라 걷는다\n전망대가 있다");
		// 코스에 개념이 없는 값 (D3).
		assertThat(mission.getSourceUrl()).isNull();
		assertThat(mission.getOperationTime()).isNull();
	}

	// 검증: FR-MISSION-08 (이미지 미러링은 SRS 등재로 NFR DATA 07)
	@Test
	@DisplayName("코스 미션에 대표 이미지 주소가 적재된다 — 버킷 상대 키를 공개 주소로 조립 (MSG-394 D3)")
	void 코스_미션에_대표_이미지_주소가_적재된다() throws IOException {
		String title = unique("이미지 있는 코스");
		String imageKey = "missions/course/1018702-745a845a9048.jpg";
		Path file = writeSeed("image-url.json", courseWithImageKey("T_IT_IMG_URL", title, imageKey));

		seeder().seed(file);

		em.flush();
		em.clear();
		// 파일은 키만 담고 주소는 시더가 자기 환경의 버킷·리전으로 조립한다 — 외부 도메인이 저장될 경로가 없다.
		assertThat(findByTitle(title).getImageUrl())
			.isEqualTo("https://%s.s3.%s.amazonaws.com/%s"
				.formatted(awsProperties.s3().bucket(), awsProperties.region(), imageKey))
			.endsWith("/" + imageKey);
	}

	// 검증: FR-MISSION-08
	@Test
	@DisplayName("대표 이미지가 없는 코스도 정상 적재된다 — 사진 있는 스팟이 없으면 image_url NULL")
	void 대표_이미지가_없는_코스도_정상_적재된다() throws IOException {
		String title = unique("이미지 없는 코스");
		Path file = writeSeed("image.json", courseWithMetadata("T_IT_IMG", title,
			"소개", "부산 영도구", "\"14\"", "\"330\"", "\"2\""));

		CourseMissionSeeder.SeedResult result = seeder().seed(file);

		assertThat(result.loaded()).isEqualTo(1);
		em.flush();
		em.clear();
		assertThat(findByTitle(title).getImageUrl()).isNull();
	}

	// 검증: FR-MISSION-08 (이미지 미러링은 SRS 등재로 NFR DATA 07)
	@Test
	@DisplayName("새 스냅샷에 이미지가 없어도 이미 채운 코스 이미지는 유지된다 — 이미지 한 필드 예외 (MSG-394 D4)")
	void 새_스냅샷에_이미지가_없어도_이미_채운_코스_이미지는_유지된다() throws IOException {
		// Given: 대표 이미지가 채워진 코스. 후보 스팟이 억제되거나 조인이 비어도 우리 버킷 사본은 살아 있다.
		String title = unique("이미지 보존 코스");
		seeder().seed(writeSeed("keep-first.json",
			courseWithImageKey("T_IT_KEEP", title, "missions/course/1018702-cafebabe.jpg")));
		em.flush();
		em.clear();
		String imageUrlBefore = findByTitle(title).getImageUrl();
		assertThat(imageUrlBefore).isNotNull();

		// When: 같은 제목(dedupe 키)에 imageKey 만 빠지고 소개문이 붙은 파일로 갱신.
		CourseMissionSeeder.SeedResult result = seeder().seed(writeSeed("keep-second.json",
			courseWithMetadata("T_IT_KEEP", title, "바뀐 소개", "부산 영도구", "\"14\"", "\"330\"", "\"2\"")));

		assertThat(result.updated()).isEqualTo(1);
		em.flush();
		em.clear();
		Mission after = findByTitle(title);
		assertThat(after.getImageUrl()).isEqualTo(imageUrlBefore);
		// 예외는 이미지에만 걸린다 — 소개문은 외부 원본을 비추는 값이라 새 스냅샷으로 덮인다.
		assertThat(after.getDescription()).isEqualTo("바뀐 소개");
	}

	// 검증: FR-MISSION-09
	@Test
	@DisplayName("기존 코스의 판정 격자는 갱신에서 바뀌지 않는다 — 재양자화한 파일을 적재해도 판정 무흔들림 (MSG-394 D5)")
	void 기존_코스의_판정_격자는_갱신에서_바뀌지_않는다() throws IOException {
		// 산출물의 스팟 격자가 EPSG:5179 로 재양자화됐어도 기존 코스는 메타데이터만 갱신된다 — 판정 범위
		// 변경은 이 티켓 밖이고 DB 이행을 동반해야 한다.
		String title = unique("격자 불변 코스");
		seeder().seed(writeSeed("grids-before.json", course("T_IT_GRID", title, 5)));
		em.flush();
		em.clear();
		long missionId = findByTitle(title).getId();
		List<String> gridsBefore = gridIdsOf(missionId);

		CourseMissionSeeder.SeedResult result = seeder()
			.seed(writeSeed("grids-after.json", course("T_IT_GRID", title, 5, -39500)));

		assertThat(result.loaded()).isZero();
		em.flush();
		em.clear();
		assertThat(gridIdsOf(missionId)).isEqualTo(gridsBefore);
	}

	// 검증: FR-MISSION-08
	@Test
	@DisplayName("같은 파일로 재실행하면 적재 0건이고 갱신 0건이다 — 이미지가 실려도 멱등")
	void 같은_파일로_재실행하면_적재_0건이고_갱신_0건이다() throws IOException {
		String title = unique("멱등 이미지 코스");
		Path file = writeSeed("idem-image.json",
			courseWithImageKey("T_IT_IDEM_IMG", title, "missions/course/1018702-0badf00d.jpg"));

		seeder().seed(file);
		em.flush();
		em.clear();
		CourseMissionSeeder.SeedResult second = seeder().seed(file);

		assertThat(second.loaded()).isZero();
		assertThat(second.updated()).isZero();
	}

	// 검증: FR-MISSION-16
	@Test
	@DisplayName("메타데이터가 없는 산출물로도 적재가 성공한다 — 결측 허용 (D4)")
	void 메타데이터가_없는_산출물로도_적재가_성공한다() throws IOException {
		// 파이프라인 수정 전 산출물(신규 키 없음)로 재실행해도 148 코스가 통째로 거부되지 않아야 한다.
		String title = unique("구 산출물 코스");
		Path file = writeSeed("legacy.json", course("T_IT_LEGACY", title, 5));

		CourseMissionSeeder.SeedResult result = seeder().seed(file);

		assertThat(result.loaded()).isEqualTo(1);
		// 산출물이 신규 키를 아직 안 싣고 있다는 신호 — 이게 없으면 백필 재실행이 "적재 0 · 갱신 0" 으로
		// 끝나 운영자가 "이미 다 돼 있다" 로 읽는다 (D4, Codex 리뷰 파생).
		assertThat(result.missingMetadata()).isEqualTo(1);
		em.flush();
		em.clear();
		Mission mission = findByTitle(title);
		assertThat(mission.getDescription()).isNull();
		assertThat(mission.getDistanceMeters()).isNull();
		// 판정에 쓰이는 값은 그대로 적재된다.
		assertThat(mission.getTargetCount()).isEqualTo(3);
		assertThat(missionGridRepository.findByMissionIds(List.of(mission.getId()))).hasSize(5);
	}

	// 검증: FR-MISSION-16
	@Test
	@DisplayName("이미 적재된 미션에 재실행하면 메타데이터만 채워진다 — 재실행이 곧 백필 (D6)")
	void 이미_적재된_미션에_재실행하면_메타데이터만_채워진다() throws IOException {
		// Given: 메타데이터 없는 구 산출물로 적재된 코스 (V31 이전 상태 재현).
		String title = unique("백필 대상 코스");
		seeder().seed(writeSeed("bare.json", course("T_IT_BF", title, 5)));
		em.flush();
		em.clear();
		Mission before = findByTitle(title);
		long missionId = before.getId();
		String pathBefore = before.getPath();
		assertThat(before.getDistanceMeters()).isNull();

		// When: 같은 제목(dedupe 키)에 메타데이터가 붙은 산출물로 재실행.
		CourseMissionSeeder.SeedResult result = seeder().seed(writeSeed("filled.json",
			courseWithMetadata("T_IT_BF", title, "채워진 소개", "부산 영도구", "\"13.4\"", "\"330\"", "\"3\"")));

		assertThat(result.loaded()).isZero();
		assertThat(result.updated()).isEqualTo(1);
		em.flush();
		em.clear();
		Mission after = missionRepository.findById(missionId).orElseThrow();
		assertThat(after.getDescription()).isEqualTo("채워진 소개");
		assertThat(after.getDistanceMeters()).isEqualTo(13400);
		assertThat(after.getDurationMinutes()).isEqualTo(330);
		assertThat(after.getDifficulty()).isEqualTo(3);
		// Then: 제목·무기간·target·path·스팟은 그대로다 (D6).
		assertThat(after.getTitle()).isEqualTo(title);
		assertThat(after.getStartAt()).isNull();
		assertThat(after.getEndAt()).isNull();
		assertThat(after.getTargetCount()).isEqualTo(3);
		assertThat(objectMapper.readTree(after.getPath())).isEqualTo(objectMapper.readTree(pathBefore));
		assertThat(missionGridRepository.findByMissionIds(List.of(missionId))).hasSize(5);
	}

	// 검증: FR-MISSION-16
	@Test
	@DisplayName("동명 코스가 둘이면 id가 작은 쪽만 갱신된다 — findBySource ORDER BY m.id (D6)")
	void 동명_코스가_둘이면_id가_작은_쪽만_갱신된다() throws IOException {
		// 과거 적재·동시 실행으로 같은 제목의 DURUNUBI 코스가 둘 남아 있는 상태. 정렬이 없으면 실행마다
		// 다른 행이 갱신돼 메타데이터가 엇갈린다 (Codex 리뷰 파생).
		String title = unique("중복 제목 코스");
		long older = insertDurunubiMission(title);
		long newer = insertDurunubiMission(title);
		assertThat(older).isLessThan(newer);

		seeder().seed(writeSeed("dup.json",
			courseWithMetadata("T_IT_DUP", title, "채워진 소개", "부산 영도구", "\"14\"", "\"330\"", "\"2\"")));

		em.flush();
		em.clear();
		assertThat(missionRepository.findById(older).orElseThrow().getDescription()).isEqualTo("채워진 소개");
		assertThat(missionRepository.findById(newer).orElseThrow().getDescription()).isNull();
	}

	// 검증: FR-MISSION-11
	@Test
	@DisplayName("파일이 없으면 예외로 조기 실패한다 — 조용한 no-op 금지")
	void 파일이_없으면_예외로_조기_실패한다() {
		Path missing = tempDir.resolve("does-not-exist.json");

		assertThatThrownBy(() -> seeder().seed(missing))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("courses-seed");
	}

	// 검증: FR-MISSION-11
	@Test
	@DisplayName("검증 위반이면 전체 롤백으로 기존 데이터가 유지된다 — reader 가 INSERT 이전에 실패")
	void 검증_위반이면_전체_롤백으로_기존_데이터가_유지된다() throws IOException {
		String validTitle = unique("정상 코스");
		// 두 번째 코스가 스팟 4개(FR-6 위반) — 검증은 적재 전 일괄이라 첫 코스도 함께 무산된다.
		Path file = writeSeed("invalid.json",
			course("T_IT_4", validTitle, 5) + "," + course("T_IT_5", unique("결함 코스"), 4));

		assertThatThrownBy(() -> seeder().seed(file))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("스팟");

		assertThat(countByTitle(validTitle)).isZero();
	}

	private String unique(String tag) {
		return "MSG225-it-" + tag + "-" + System.nanoTime();
	}

	private long courseCount() {
		return missionRepository.findBySource(CourseMissionSeeder.SOURCE_DURUNUBI).size();
	}

	private Mission findByTitle(String title) {
		return missionRepository.findBySource(CourseMissionSeeder.SOURCE_DURUNUBI).stream()
			.filter(mission -> mission.getTitle().equals(title))
			.findFirst()
			.orElseThrow();
	}

	/** 판정 격자 seq 순 목록 — 갱신 전후 비교용 (D5). */
	private List<String> gridIdsOf(long missionId) {
		return missionGridRepository.findByMissionIds(List.of(missionId)).stream()
			.sorted(Comparator.comparing(MissionGrid::getSeq))
			.map(MissionGrid::getGridId)
			.toList();
	}

	private long countByTitle(String title) {
		return missionRepository.findBySource(CourseMissionSeeder.SOURCE_DURUNUBI).stream()
			.filter(mission -> mission.getTitle().equals(title))
			.count();
	}

	// 검증: FR-MISSION-19
	// --- MSG-492: 스팟 표시 이름 (테스트 7~11) ---

	@Test
	@DisplayName("새 코스 적재 시 명소 이름이 mission_grids.name 에 그대로 저장된다")
	void 새_코스_적재시_명소_이름이_저장된다() throws IOException {
		String title = unique("이름 적재 코스");
		Path file = writeSeed("name-insert.json", course("T_NM_1", title, 5));

		seeder().seed(file);
		em.flush();
		em.clear();

		assertThat(spotNames(title)).containsExactly("스팟1", "스팟2", "스팟3", "스팟4", "스팟5");
	}

	@Test
	@DisplayName("이미 등재된 코스를 재실행하면 이름만 갱신되고 격자 집합과 seq 는 그대로다")
	void 재실행은_이름만_갱신하고_격자_집합은_그대로다() throws IOException {
		String title = unique("이름 갱신 코스");
		seeder().seed(writeSeed("before.json", course("T_NM_2", title, 5)));
		em.flush();
		em.clear();
		List<String> gridsBefore = spotGridIds(title);

		String renamed = course("T_NM_2", title, 5).replace("스팟", "새이름");
		CourseMissionSeeder.SeedResult result = seeder().seed(writeSeed("after.json", renamed));
		em.flush();
		em.clear();

		assertThat(result.loaded()).isZero();
		assertThat(result.spotNamesUpdated()).isEqualTo(5);
		assertThat(spotNames(title)).containsExactly("새이름1", "새이름2", "새이름3", "새이름4", "새이름5");
		assertThat(spotGridIds(title)).isEqualTo(gridsBefore);
		assertThat(spotSeqs(title)).containsExactly(1, 2, 3, 4, 5);
	}

	@Test
	@DisplayName("명소였다가 폴백으로 바뀐 스팟은 폴백 계산 결과로 덮인다 — 옛 이름이 남지 않는다")
	void 명소가_폴백으로_바뀌면_옛_이름이_남지_않는다() throws IOException {
		String title = unique("억제 코스");
		seeder().seed(writeSeed("named.json", course("T_NM_3", title, 5)));
		em.flush();
		em.clear();
		assertThat(spotNames(title)).contains("스팟1");

		String suppressed = course("T_NM_3", title, 5)
			.replace("\"method\": \"tourapi\"", "\"method\": \"geometric-fallback\"");
		seeder().seed(writeSeed("suppressed.json", suppressed));
		em.flush();
		em.clear();

		// 합성 격자는 서비스 범위 밖이라 폴백 계산 결과가 null 이다. doesNotContain 은 "안 덮었다"와 "null 로
		// 덮었다"를 구분 못 하므로 전부 null 임을 단언한다 — 옛 이름이 하나라도 남으면 여기서 깨진다.
		// 폴백이 실제로 이름을 만들어내는 경로(구역·행정동·최근접)는 CourseSpotNameResolverTest 가 검증한다.
		assertThat(spotNames(title)).containsOnlyNulls();
	}

	@Test
	@DisplayName("같은 산출물로 두 번 돌리면 두 번째 이름 갱신은 0건이다 — 멱등 (FR-10)")
	void 같은_산출물_재실행은_이름_갱신이_0건이다() throws IOException {
		String title = unique("이름 멱등 코스");
		Path file = writeSeed("idempotent-name.json", course("T_NM_4", title, 5));
		seeder().seed(file);
		em.flush();
		em.clear();

		CourseMissionSeeder.SeedResult second = seeder().seed(file);

		assertThat(second.spotNamesUpdated()).isZero();
	}

	@Test
	@DisplayName("이름 갱신이 target_count 와 path 를 건드리지 않는다 — 판정 불변 (성공 기준 7)")
	void 이름_갱신이_판정_재료를_바꾸지_않는다() throws IOException {
		String title = unique("판정 불변 코스");
		seeder().seed(writeSeed("judge-before.json", course("T_NM_5", title, 5)));
		em.flush();
		em.clear();
		Mission before = findByTitle(title);
		Integer targetBefore = before.getTargetCount();
		String pathBefore = before.getPath();

		seeder().seed(writeSeed("judge-after.json", course("T_NM_5", title, 5).replace("스팟", "새이름")));
		em.flush();
		em.clear();

		Mission after = findByTitle(title);
		assertThat(after.getTargetCount()).isEqualTo(targetBefore);
		assertThat(after.getPath()).isEqualTo(pathBefore);
	}

	private List<MissionGrid> spotsOf(String title) {
		return missionGridRepository.findByMissionIds(List.of(findByTitle(title).getId())).stream()
			.sorted(Comparator.comparing(MissionGrid::getSeq))
			.toList();
	}

	private List<String> spotNames(String title) {
		return spotsOf(title).stream().map(MissionGrid::getName).toList();
	}

	private List<String> spotGridIds(String title) {
		return spotsOf(title).stream().map(MissionGrid::getGridId).toList();
	}

	private List<Integer> spotSeqs(String title) {
		return spotsOf(title).stream().map(MissionGrid::getSeq).toList();
	}

	private Path writeSeed(String filename, String coursesJson) throws IOException {
		Path file = tempDir.resolve(filename);
		Files.writeString(file, "[" + coursesJson + "]");
		return file;
	}

	/** 산출물 형식(D6)의 코스 1건 — 남반구 합성 격자 인덱스(-39001..)로 실데이터와 못 겹치게 한다. */
	private static String course(String crsIdx, String name, int spotCount) {
		return course(crsIdx, name, spotCount, -39000);
	}

	/** 스팟 격자 대역을 옮긴 같은 형식의 코스 1건 — 재양자화된 파일로 재실행하는 상황을 만든다 (D5). */
	private static String course(String crsIdx, String name, int spotCount, long baseY) {
		StringBuilder spots = new StringBuilder();
		for (int seq = 1; seq <= spotCount; seq++) {
			if (seq > 1) {
				spots.append(",");
			}
			spots.append("""
				{"seq": %d, "gridId": "%d_112198", "name": "스팟%d", "method": "tourapi"}"""
				.formatted(seq, baseY - seq, seq));
		}
		return """
			{"crsIdx": "%s", "name": "%s", "path": %s, "spots": [%s]}"""
			.formatted(crsIdx, name, PATH_JSON, spots);
	}

	/** 대표 이미지 키(MSG-394 D3)만 얹은 코스 1건 — 키가 null 이면 넣지 않는다(산출물 계약). */
	private static String courseWithImageKey(String crsIdx, String name, String imageKey) {
		if (imageKey == null) {
			return course(crsIdx, name, 5);
		}
		return course(crsIdx, name, 5).replaceFirst("}$", """
			, "imageKey": "%s"}""".formatted(imageKey));
	}

	/** 화면용 필드 5종(MSG-383 D4)까지 실린 산출물 형식의 코스 1건 — 숫자는 원문 조각 그대로 받는다. */
	private static String courseWithMetadata(String crsIdx, String name, String contents, String sigun,
		String distanceJson, String durationJson, String levelJson) {
		return course(crsIdx, name, 5).replaceFirst("}$", """
			, "contents": "%s", "sigun": "%s", "distanceKm": %s, "durationMinutes": %s, "level": %s}"""
			.formatted(contents, sigun, distanceJson, durationJson, levelJson));
	}

	/** 코스 러너 산출물 모사 — 같은 제목으로 둘 넣어 dedupe 대상 중복 상태를 만든다 (D6 결정성 검증용). */
	private long insertDurunubiMission(String title) {
		em.createNativeQuery("""
				INSERT INTO missions (type, title, start_at, end_at, target_count, source)
				VALUES ('COURSE', :title, NULL, NULL, 3, :source)
				""")
			.setParameter("title", title)
			.setParameter("source", CourseMissionSeeder.SOURCE_DURUNUBI)
			.executeUpdate();
		return ((Number) em.createNativeQuery(
			"SELECT max(id) FROM missions WHERE title = :title AND source = :source")
			.setParameter("title", title)
			.setParameter("source", CourseMissionSeeder.SOURCE_DURUNUBI)
			.getSingleResult()).longValue();
	}

	private void insertManualMission(String title) {
		em.createNativeQuery("""
				INSERT INTO missions (type, title, start_at, end_at, target_count, source)
				VALUES ('COURSE', :title, NULL, NULL, 3, NULL)
				""")
			.setParameter("title", title)
			.executeUpdate();
	}

	private static ApplicationArguments emptyArgs() {
		return new DefaultApplicationArguments();
	}
}
