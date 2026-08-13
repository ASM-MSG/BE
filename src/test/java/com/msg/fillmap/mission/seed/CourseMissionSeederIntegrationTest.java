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
	private MissionRepository missionRepository;

	@Autowired
	private MissionGridRepository missionGridRepository;

	@Autowired
	private CourseSeedReader reader;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private EntityManager em;

	@TempDir
	private Path tempDir;

	private CourseMissionSeeder newSeeder(boolean enabled, String path) {
		CourseMissionSeeder seeder = new CourseMissionSeeder(missionRepository, missionGridRepository, reader);
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

	// 검증: FR-MISSION-16
	@Test
	@DisplayName("대표 이미지는 어느 시더도 채우지 않는다 — 스팟 사진 수집은 MSG-384 (D7)")
	void 대표_이미지는_어느_시더도_채우지_않는다() throws IOException {
		String title = unique("이미지 없는 코스");
		Path file = writeSeed("image.json", courseWithMetadata("T_IT_IMG", title,
			"소개", "부산 영도구", "\"14\"", "\"330\"", "\"2\""));

		seeder().seed(file);

		em.flush();
		em.clear();
		assertThat(findByTitle(title).getImageUrl()).isNull();
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

	private long countByTitle(String title) {
		return missionRepository.findBySource(CourseMissionSeeder.SOURCE_DURUNUBI).stream()
			.filter(mission -> mission.getTitle().equals(title))
			.count();
	}

	private Path writeSeed(String filename, String coursesJson) throws IOException {
		Path file = tempDir.resolve(filename);
		Files.writeString(file, "[" + coursesJson + "]");
		return file;
	}

	/** 산출물 형식(D6)의 코스 1건 — 남반구 합성 격자 인덱스(-39001..)로 실데이터와 못 겹치게 한다. */
	private static String course(String crsIdx, String name, int spotCount) {
		StringBuilder spots = new StringBuilder();
		for (int seq = 1; seq <= spotCount; seq++) {
			if (seq > 1) {
				spots.append(",");
			}
			spots.append("""
				{"seq": %d, "gridId": "%d_112198", "name": "스팟%d", "method": "tourapi"}"""
				.formatted(seq, -39000 - seq, seq));
		}
		return """
			{"crsIdx": "%s", "name": "%s", "path": %s, "spots": [%s]}"""
			.formatted(crsIdx, name, PATH_JSON, spots);
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
