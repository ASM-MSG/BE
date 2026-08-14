package com.msg.fillmap.mission.seed;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.msg.fillmap.global.config.AwsProperties;
import com.msg.fillmap.mission.entity.Mission;
import com.msg.fillmap.mission.entity.MissionGrid;
import com.msg.fillmap.mission.entity.MissionMetadata;
import com.msg.fillmap.mission.entity.MissionType;
import com.msg.fillmap.mission.repository.MissionGridRepository;
import com.msg.fillmap.mission.repository.MissionRepository;

/**
 * 코스 미션 시더 (MSG-225 D7, FestivalMissionSeeder 미러). 기본 off —
 * {@code fillmap.mission.course.seed.enabled=true} 로 앱 1회 기동할 때만 산출물(courses-seed.json)을
 * 검증·적재한다(상시 배치 금지, GPX 파싱·스팟 선정은 레포 밖 파이프라인 몫 — D2). 정리 단계(축제 D4)는
 * 없다 — 코스는 무기간 반영구 데이터이고 클럭도 불요(날짜 판정 없음). 기존 미션에 대한 쓰기는 메타데이터
 * 갱신 하나뿐이다(MSG-383 D6) — 제목·기간·격자·path 는 건드리지 않는다.
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
	/** 결측 집계 기준 (MSG-383 D4) — 산출물에 화면용 키가 하나도 없으면 metadataOf 가 이 값을 낸다. */
	private static final MissionMetadata NO_METADATA =
		new MissionMetadata(null, null, null, null, null, null, null, null);

	private final MissionRepository missionRepository;
	private final MissionGridRepository missionGridRepository;
	private final CourseSeedReader reader;
	private final AwsProperties awsProperties;

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
		log.info("코스 미션 시드 완료 — 적재 {} 건, dedupe 건너뜀 {} 건(메타데이터 갱신 {} 건), 메타데이터 결측 {} 건",
			result.loaded(), result.deduped(), result.updated(), result.missingMetadata());
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

		// 제목 → 미션 색인 (D6) — 제목만 담지 않는 이유는 skip 경로에서 그 미션의 메타데이터를 갱신하기
		// 때문이다(MSG-383 D6). 동명 미션은 id 가 가장 작은 쪽으로 갱신 대상을 고정한다 — findBySource 가
		// ORDER BY m.id 라 첫 원소가 최소 id 이고, 그 정렬이 이 결정성의 근거다.
		Map<String, Mission> existing = missionRepository.findBySource(SOURCE_DURUNUBI).stream()
			.collect(Collectors.toMap(Mission::getTitle, mission -> mission, (first, duplicate) -> first));
		int loaded = 0;
		int updated = 0;
		int missingMetadata = 0;
		for (CourseRecord course : courses) {
			MissionMetadata metadata = metadataOf(course);
			if (NO_METADATA.equals(metadata)) {
				missingMetadata++;
			}
			Mission existingMission = existing.get(course.title());
			if (existingMission != null) {
				// 이미지 보존은 갱신 경로에서만 명시적으로 고른다 (MSG-394 D4) — 억제나 후보 소멸로 새
				// 산출물에 키가 없어도 이미 채운 사본 주소를 잃지 않는다.
				if (existingMission.applyMetadata(metadata.withImageFallback(existingMission.getImageUrl()))) {
					updated++;
				}
				continue;
			}
			insertCourse(course, metadata);
			loaded++;
		}
		return new SeedResult(loaded, courses.size() - loaded, updated, missingMetadata);
	}

	/** 미션 1건(무기간·path 원문) + 스팟 mission_grids(seq 1..N) INSERT — 표시·판정 분리 저장 (FR-6). */
	private void insertCourse(CourseRecord course, MissionMetadata metadata) {
		Mission mission = missionRepository.save(Mission.builder()
			.type(MissionType.COURSE)
			.title(course.title())
			.targetCount(TARGET_COUNT)
			.source(SOURCE_DURUNUBI)
			.path(course.pathJson())
			.metadata(metadata)
			.build());
		missionGridRepository.saveAll(course.spots().stream()
			.map(spot -> new MissionGrid(mission.getId(), spot.gridId(), spot.seq()))
			.toList());
	}

	/**
	 * 코스 산출물 → 메타데이터 컬럼 매핑 (MSG-383 D3 · MSG-394 D3). 원문 링크·운영시간은 코스에 개념이
	 * 없어 계속 null 이다. 대표 이미지는 코스 위 포토스팟의 TourAPI 사진에서 오고, 버킷 상대 키를 받아
	 * 자기 환경의 공개 주소로 조립한다. 거리·소요시간·난이도는 chk_missions_course_metrics 가 COURSE
	 * 에서만 허용하는 값이라 이 시더에서만 채워진다.
	 */
	private MissionMetadata metadataOf(CourseRecord course) {
		return new MissionMetadata(course.description(), course.placeName(), null, null,
			awsProperties.publicUrl(course.imageKey()),
			course.distanceMeters(), course.durationMinutes(), course.difficulty());
	}

	/**
	 * updated: 이미 있던 미션의 메타데이터를 채운 건수 (MSG-383 D6).
	 * missingMetadata: 화면용 값(소개·시군·거리·소요시간·난이도·대표 이미지)이 <b>전부</b> 없는 코스 수 —
	 * 산출물이 신규 키를 아직 안 싣고 있다는 신호다(D4). 이게 없으면 백필 재실행이 "적재 0 · 갱신 0"으로
	 * 끝나 운영자가 "이미 다 돼 있다"로 읽는다.
	 */
	public record SeedResult(int loaded, int deduped, int updated, int missingMetadata) {
	}
}
