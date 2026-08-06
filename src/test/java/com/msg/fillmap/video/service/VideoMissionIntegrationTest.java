package com.msg.fillmap.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import com.msg.fillmap.badge.dto.EarnedBadgeResponseDto;
import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.mission.dto.CompletedMissionResponseDto;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.dto.VideoUploadResponseDto;

/**
 * 업로드 훅의 미션 판정 배선 통합 검증 (실 PostGIS, MSG-223 모듈 4). 공유 로컬 DB — 합성 유저·미션만
 * 만들고 @Transactional 롤백. 트리거는 saveVideo 뿐(§D4) — 삭제 경로 무변경(스탬프 비회수, FR-15)과
 * 도감 분리(user_grids 무변경, FR-16)를 회귀 테스트로 고정한다.
 */
@SpringBootTest
@Transactional
@DisplayName("업로드 훅 미션 판정 배선 (실 PostGIS)")
class VideoMissionIntegrationTest {

	// 미션을 붙일 격자. 도심 좌표(연남)를 쓰면 시드를 적재한 로컬 DB 에서 그 격자에 진짜 축제·팝업이
	// 겹친다. 좌표를 산간으로 옮긴 건 겹칠 확률을 줄이는 것일 뿐이라, 단언 쪽도 내가 만든 미션만
	// 보도록 해 뒀다(아래 contains) — 언젠가 이 격자에 시드가 들어와도 테스트는 그대로 통과한다.
	private static final double 미션격자_LAT = 35.3600;
	private static final double 미션격자_LON = 127.7400;
	// 미션이 있을 수 없는 외딴 좌표 (지리산 자락) — 미해당 경로의 빈 배열 단언용. 위 격자와 겹치지 않는다.
	private static final double 산간_LAT = 35.3500;
	private static final double 산간_LON = 127.7300;

	@Autowired
	private VideoService videoService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@MockitoBean
	private S3Client s3Client;

	private long userId;
	private String missionGridId;

	@BeforeEach
	void setUp() {
		given(s3Client.headObject(any(HeadObjectRequest.class))).willReturn(HeadObjectResponse.builder().build());
		String email = "video-mission-" + System.nanoTime() + "@example.com";
		userId = userRepository.save(User.createLocalUser(email, "hash", "미션훅테스터")).getId();
		missionGridId = GridEncoder.encode(미션격자_LAT, 미션격자_LON);
	}

	@Test
	@DisplayName("미션 격자 업로드 응답에 completedMissions 가 동봉된다 (FR-19)")
	void 미션_격자_업로드_응답에_completedMissions가_동봉된다() {
		long mission = insertActiveMission("시험용 축제");
		insertMissionGrid(mission, missionGridId);

		VideoUploadResponseDto response = videoService.saveVideo(userId, uploadRequest(미션격자_LAT, 미션격자_LON));

		// contains 인 이유: 같은 격자에 다른 미션이 걸려 있으면 그것도 함께 완료되는 게 옳은 동작이라,
		// "정확히 이것만"은 DB 상태에 기대는 단언이 된다. 이 테스트가 책임지는 건 내가 만든 미션의 동봉이다.
		assertThat(response.completedMissions())
			.contains(new CompletedMissionResponseDto(mission, "시험용 축제", "EVENT"));
		assertThat(stampCount(mission)).isEqualTo(1);
	}

	@Test
	@DisplayName("미션 미해당 업로드는 completedMissions 가 빈 배열이다 (FR-18)")
	void 미션_미해당_업로드는_completedMissions가_빈_배열이다() {
		VideoUploadResponseDto response = videoService.saveVideo(userId, uploadRequest(산간_LAT, 산간_LON));

		assertThat(response.completedMissions()).isEmpty();
	}

	@Test
	@DisplayName("미션 뱃지가 newBadges 에 합류한다 — 기존 뱃지와 한 배열 (FR-17·19)")
	void 미션_뱃지가_newBadges에_합류한다() {
		insertMissionGrid(insertActiveMission("시험용 축제"), missionGridId);

		VideoUploadResponseDto response = videoService.saveVideo(userId, uploadRequest(미션격자_LAT, 미션격자_LON));

		// 첫 업로드라 첫 발자국(EXPLORER_1)과 첫 스탬프(MISSION_1)가 한 응답에 실린다.
		assertThat(response.newBadges()).extracting(EarnedBadgeResponseDto::code)
			.contains("EXPLORER_1", "MISSION_1");
	}

	@Test
	@DisplayName("미션 판정은 user_grids 를 변경하지 않는다 — 도감은 기존 upsertUserGrid 1회뿐 (FR-16)")
	void 미션_판정은_user_grids를_변경하지_않는다() {
		long mission = insertActiveMission("시험용 축제");
		insertMissionGrid(mission, missionGridId);
		// 방문하지 않을 대상 격자 — 판정이 도감에 손대면 이 격자의 가짜 row 로 드러난다.
		insertMissionGrid(mission, GridEncoder.encode(미션격자_LAT + 0.001, 미션격자_LON));

		videoService.saveVideo(userId, uploadRequest(미션격자_LAT, 미션격자_LON));

		assertThat(myGridRows()).containsExactly(missionGridId + ":1");
	}

	@Test
	@DisplayName("영상을 삭제해도 스탬프는 유지된다 — 비회수, 삭제 경로 무변경 (FR-15)")
	void 영상을_삭제해도_스탬프는_유지된다() {
		long mission = insertActiveMission("시험용 축제");
		insertMissionGrid(mission, missionGridId);
		long videoId = videoService.saveVideo(userId, uploadRequest(미션격자_LAT, 미션격자_LON)).videoId();

		videoService.deleteVideo(userId, videoId);

		assertThat(stampCount(mission)).isEqualTo(1);
	}

	private VideoUploadRequestDto uploadRequest(double lat, double lon) {
		String pendingKey = "videos/pending/" + userId + "/" + UUID.randomUUID() + ".mp4";
		return new VideoUploadRequestDto(pendingKey, lat, lon, (short) 10, LocalDateTime.now(ZoneOffset.UTC), "PRIVATE");
	}

	/** 지금 진행 중인 EVENT 미션(UTC 기간 — §D3, target 1)을 삽입하고 id 를 돌려준다. */
	private long insertActiveMission(String title) {
		LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
		String uniqueTitle = title;
		em.createNativeQuery("""
				INSERT INTO missions (type, title, start_at, end_at, target_count)
				VALUES ('EVENT', :title, :startAt, :endAt, 1)
				""")
			.setParameter("title", uniqueTitle)
			.setParameter("startAt", nowUtc.minusDays(1))
			.setParameter("endAt", nowUtc.plusDays(1))
			.executeUpdate();
		return ((Number) em.createNativeQuery(
				"SELECT id FROM missions WHERE title = :title ORDER BY id DESC LIMIT 1")
			.setParameter("title", uniqueTitle)
			.getSingleResult()).longValue();
	}

	private void insertMissionGrid(long missionId, String gridId) {
		em.createNativeQuery("INSERT INTO mission_grids (mission_id, grid_id) VALUES (:missionId, :gridId)")
			.setParameter("missionId", missionId)
			.setParameter("gridId", gridId)
			.executeUpdate();
	}

	private int stampCount(long missionId) {
		return ((Number) em.createNativeQuery("""
				SELECT COUNT(*) FROM user_missions WHERE user_id = :userId AND mission_id = :missionId
				""")
			.setParameter("userId", userId)
			.setParameter("missionId", missionId)
			.getSingleResult()).intValue();
	}

	@SuppressWarnings("unchecked")
	private List<String> myGridRows() {
		List<Object[]> rows = em.createNativeQuery("""
				SELECT grid_id, video_count FROM user_grids WHERE user_id = :userId ORDER BY grid_id
				""")
			.setParameter("userId", userId)
			.getResultList();
		return rows.stream().map(row -> row[0] + ":" + row[1]).toList();
	}
}
