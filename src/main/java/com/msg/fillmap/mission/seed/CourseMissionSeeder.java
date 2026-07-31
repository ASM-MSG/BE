package com.msg.fillmap.mission.seed;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.msg.fillmap.mission.entity.Mission;
import com.msg.fillmap.mission.entity.MissionGrid;
import com.msg.fillmap.mission.entity.MissionType;
import com.msg.fillmap.mission.repository.MissionGridRepository;
import com.msg.fillmap.mission.repository.MissionRepository;

/**
 * 코스 미션 시더 (MSG-225 D7, FestivalMissionSeeder 미러). 기본 off —
 * {@code fillmap.mission.course.seed.enabled=true} 로 앱 1회 기동할 때만 산출물(courses-seed.json)을
 * 검증·적재한다(상시 배치 금지, GPX 파싱·스팟 선정은 레포 밖 파이프라인 몫 — D2). INSERT-only:
 * 코스는 무기간 반영구 데이터라 정리 단계(축제 D4) 자체가 없고, 클럭도 불요(날짜 판정 없음).
 * dedupe 는 제목 × {@code source='DURUNUBI'} 한정(D6). {@code @Order(40)}: Region 10 · Zone 20 ·
 * Festival 30 이후 — 의존은 없지만 결정적 순서.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(40)
public class CourseMissionSeeder implements ApplicationRunner {

	/** 적재 출처 값 (V13 재사용) — 이 러너 산출물 식별자. dedupe 대조가 이 값만 본다(타 소스·수동 불가침). */
	static final String SOURCE_DURUNUBI = "DURUNUBI";
	/** "스팟 5~8 중 3곳" 시작안 (D4) — 조정 시 missions UPDATE 만 필요(스키마·코드 무변경). */
	private static final int TARGET_COUNT = 3;

	private final MissionRepository missionRepository;
	private final MissionGridRepository missionGridRepository;
	private final CourseSeedReader reader;

	@Value("${fillmap.mission.course.seed.enabled:false}")
	private boolean enabled;

	@Value("${fillmap.mission.course.seed.path:data/mission/courses-seed.json}")
	private String seedPath;

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (!enabled) {
			return;
		}
		SeedResult result = seed(Path.of(seedPath));
		log.info("코스 미션 시드 완료 — 적재 {} 건, dedupe 건너뜀 {} 건", result.loaded(), result.deduped());
	}

	/**
	 * 산출물을 파싱·검증해 제목 dedupe 통과분만 INSERT 한다. 파일 부재·파싱 실패·검증 위반·유효 0건은
	 * 명확한 예외로 조기 실패(reader 가 위반 즉시 던진다) — 부분 적재 없이 전체 롤백, 기존 미션 무변경.
	 * 트랜잭션은 호출자(run 또는 테스트)가 연다.
	 */
	public SeedResult seed(Path path) {
		if (!Files.isReadable(path)) {
			throw new IllegalStateException("코스 산출물 파일을 읽을 수 없습니다: " + path.toAbsolutePath()
				+ " (spot_pipeline.py 산출 courses-seed.json 을 복사하세요 — D9 절차 2)");
		}

		List<CourseRecord> courses;
		try (InputStream in = Files.newInputStream(path)) {
			courses = reader.read(in);
		} catch (IOException | UncheckedIOException e) {
			throw new IllegalStateException("코스 산출물 읽기에 실패했습니다: " + path.toAbsolutePath(), e);
		}
		if (courses.isEmpty()) {
			throw new IllegalStateException("코스 산출물에서 유효한 코스를 0건 파싱했습니다: " + path.toAbsolutePath()
				+ " — 파이프라인 산출물 내용을 확인하세요");
		}

		Set<String> existingTitles = missionRepository.findBySource(SOURCE_DURUNUBI).stream()
			.map(Mission::getTitle)
			.collect(Collectors.toSet());
		int loaded = 0;
		for (CourseRecord course : courses) {
			if (existingTitles.contains(course.title())) {
				continue;
			}
			insertCourse(course);
			loaded++;
		}
		return new SeedResult(loaded, courses.size() - loaded);
	}

	/** 미션 1건(무기간·path 원문) + 스팟 mission_grids(seq 1..N) INSERT — 표시·판정 분리 저장 (FR-6). */
	private void insertCourse(CourseRecord course) {
		Mission mission = missionRepository.save(Mission.builder()
			.type(MissionType.COURSE)
			.title(course.title())
			.targetCount(TARGET_COUNT)
			.source(SOURCE_DURUNUBI)
			.path(course.pathJson())
			.build());
		missionGridRepository.saveAll(course.spots().stream()
			.map(spot -> new MissionGrid(mission.getId(), spot.gridId(), spot.seq()))
			.toList());
	}

	public record SeedResult(int loaded, int deduped) {
	}
}
