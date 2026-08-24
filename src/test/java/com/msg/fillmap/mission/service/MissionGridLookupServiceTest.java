package com.msg.fillmap.mission.service;

import static com.msg.fillmap.video.support.S3VideoObjectStub.givenUploadedVideoObject;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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

import com.msg.fillmap.grid.GridEncoder;
import com.msg.fillmap.grid.GridEncoder.GridIndex;
import com.msg.fillmap.grid.GridEncoder.GridPoint;
import com.msg.fillmap.hotzone.service.HotScoreCommandService;
import com.msg.fillmap.mission.dto.GridMissionResponseDto;
import com.msg.fillmap.user.entity.User;
import com.msg.fillmap.user.repository.UserRepository;
import com.msg.fillmap.video.dto.VideoUploadRequestDto;
import com.msg.fillmap.video.service.VideoEncodingService;
import com.msg.fillmap.video.service.VideoService;

/**
 * 격자로 미션 되짚기 (MSG-459 §API 2, 실 PostgreSQL). 검증의 축이 "무엇이 나오고 무엇이 안 나오는가"라
 * 실제 대표 격자 컬럼·부분 인덱스·영상 술어를 그대로 태운다 — 특히 판정 범위(축제 81칸)에만 걸친 칸이
 * 나오지 않는다는 D-4 계약은 조인 대상이 진짜여야 관측된다.
 * <p>
 * 격리(공유 로컬 DB): 서해 먼바다 격자(125.7 대역)와 합성 제목(msg459-grid-*)만 쓰고
 * {@code @Transactional} 롤백으로 정리한다.
 */
@SpringBootTest
@Transactional
@DisplayName("격자로 미션 되짚기 (실 PostgreSQL)")
class MissionGridLookupServiceTest {

	/** 서해 먼바다 기준 격자 — 업로드 테스트(125.5·125.6)와도 겹치지 않는 대역. */
	private static final GridIndex 바다 = GridEncoder.decode(GridEncoder.encode(34.0, 125.7));

	@Autowired
	private MissionQueryService missionQueryService;

	@Autowired
	private VideoService videoService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager em;

	@MockitoBean
	private S3Client s3Client;

	@MockitoBean
	private VideoEncodingService videoEncodingService;

	@MockitoBean
	private HotScoreCommandService hotScoreCommandService;

	private long userId;

	@BeforeEach
	void setUp() {
		givenUploadedVideoObject(s3Client);
		userId = userRepository.save(User.createLocalUser(
			"msg459-grid-" + UUID.randomUUID() + "@example.com", "hash", "역조회테스터")).getId();
	}

	private static LocalDateTime 지금() {
		// PostgreSQL timestamp 는 마이크로초까지만 저장하며 그보다 잔 자리는 반올림한다 — Linux CI 의
		// 나노초 클럭 값을 그대로 심으면 저장값이 심은 값보다 커질 수 있어 정각 경계 대조가 뒤집힌다
		// (macOS 클럭은 마이크로초라 로컬에서 재현되지 않았다). 절단해 왕복이 항등이 되게 한다.
		return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
	}

	private String 격자(long dy, long dx) {
		return (바다.gridY() + dy) + "_" + (바다.gridX() + dx);
	}

	private long 미션(String type, LocalDateTime startAt, LocalDateTime endAt, List<String> gridIds, String repGrid) {
		long missionId = ((Number) em.createNativeQuery("""
				INSERT INTO missions (type, title, start_at, end_at, target_count, source)
				VALUES (:type, :title, :startAt, :endAt, 1, 'MSG459GRID') RETURNING id
				""")
			.setParameter("type", type)
			.setParameter("title", "msg459-grid-" + UUID.randomUUID())
			.setParameter("startAt", startAt)
			.setParameter("endAt", endAt)
			.getSingleResult()).longValue();
		gridIds.forEach(gridId -> em.createNativeQuery(
				"INSERT INTO mission_grids (mission_id, grid_id) VALUES (:m, :g)")
			.setParameter("m", missionId).setParameter("g", gridId).executeUpdate());
		em.createNativeQuery("UPDATE missions SET representative_grid_id = :g WHERE id = :m")
			.setParameter("g", repGrid).setParameter("m", missionId).executeUpdate();
		return missionId;
	}

	/** 축제 판정 범위(9×9)를 그대로 심고 정중앙을 대표 격자로 둔다 — 판정 범위와 대표 격자를 가르는 픽스처다. */
	private long 축제(long dy, long dx, LocalDateTime startAt, LocalDateTime endAt) {
		List<String> gridIds = new ArrayList<>(81);
		for (long y = -4; y <= 4; y++) {
			for (long x = -4; x <= 4; x++) {
				gridIds.add(격자(dy + y, dx + x));
			}
		}
		return 미션("EVENT", startAt, endAt, gridIds, 격자(dy, dx));
	}

	/** 전역 공개 게이트를 통과한 영상 한 건 — 확정 후 인코딩 완료 상태로 올려 목록·집계 술어에 잡히게 한다. */
	private void 공개영상(String gridId, LocalDateTime recordedAt) {
		GridPoint center = GridEncoder.center(gridId);
		var response = videoService.saveVideo(userId, new VideoUploadRequestDto(
			"videos/pending/" + userId + "/" + UUID.randomUUID() + ".mp4",
			center.lat(), center.lon(), (short) 10, recordedAt, null));
		em.createNativeQuery("UPDATE videos SET processing_status = 'READY' WHERE id = :v")
			.setParameter("v", response.videoId()).executeUpdate();
		em.flush();
		em.clear();
	}

	@Test
	@DisplayName("대표_격자를_누르면_그_미션이_나온다")
	void 대표_격자를_누르면_그_미션이_나온다() {
		long missionId = 축제(0, 0, 지금().minusDays(1), 지금().plusDays(1));

		List<GridMissionResponseDto> found = missionQueryService.getMissionsByGrid(격자(0, 0));

		assertThat(found).extracting(GridMissionResponseDto::missionId).containsExactly(missionId);
		assertThat(found.get(0).type()).isEqualTo("EVENT");
		assertThat(found.get(0).videoCount()).isZero();
	}

	// 검증: FR-MISSION-25
	@Test
	@DisplayName("기간이_끝난_축제도_역조회에_나온다")
	void 기간이_끝난_축제도_역조회에_나온다() {
		long missionId = 축제(10, 0, 지금().minusDays(10), 지금().minusDays(3));

		// 기간 필터가 없다 — 영상이 모인 자리를 눌러 무슨 축제였는지 되짚는 것이 이 조회의 목적이다.
		assertThat(missionQueryService.getMissionsByGrid(격자(10, 0)))
			.extracting(GridMissionResponseDto::missionId)
			.containsExactly(missionId);
	}

	@Test
	@DisplayName("진행_중_미션이_종료_미션보다_앞에_온다")
	void 진행_중_미션이_종료_미션보다_앞에_온다() {
		// 같은 자리에서 축제가 여러 번 열린 경우 — 배열 첫 항목이 화면 진입 기본값이라 순서가 계약이다.
		String gridId = 격자(20, 0);
		long ended = 미션("EVENT", 지금().minusDays(10), 지금().minusDays(3), List.of(gridId), gridId);
		long upcoming = 미션("EVENT", 지금().plusDays(3), 지금().plusDays(5), List.of(gridId), gridId);
		long ongoing = 미션("POPUP", 지금().minusDays(1), 지금().plusDays(1), List.of(gridId), gridId);

		assertThat(missionQueryService.getMissionsByGrid(gridId))
			.extracting(GridMissionResponseDto::missionId)
			.containsExactly(ongoing, upcoming, ended);
	}

	@Test
	@DisplayName("어느_미션의_대표_격자도_아닌_격자는_빈_배열이다")
	void 어느_미션의_대표_격자도_아닌_격자는_빈_배열이다() {
		assertThat(missionQueryService.getMissionsByGrid(격자(30, 0))).isEmpty();
	}

	@Test
	@DisplayName("격자_포맷이_아닌_문자열도_빈_배열이다")
	void 격자_포맷이_아닌_문자열도_빈_배열이다() {
		// 오류가 아니다 — 역조회는 지도 탭 한 번에 붙는 조회라 형식 위반도 조용히 빈 배열이다.
		assertThat(missionQueryService.getMissionsByGrid("not-a-grid")).isEmpty();
	}

	// 검증: FR-MISSION-25
	@Test
	@DisplayName("판정_범위에만_걸친_격자는_역조회에_나오지_않는다")
	void 판정_범위에만_걸친_격자는_역조회에_나오지_않는다() {
		축제(40, 0, 지금().minusDays(1), 지금().plusDays(1));

		// 축제 81칸 중 중앙이 아닌 칸 — mission_grids 역방향 인덱스로 찾았다면 여기서 미션이 나온다(D-4).
		assertThat(missionQueryService.getMissionsByGrid(격자(43, 2))).isEmpty();
	}

	@Test
	@DisplayName("역조회의_영상_수가_미션_상세의_영상_수와_같다")
	void 역조회의_영상_수가_미션_상세의_영상_수와_같다() {
		long missionId = 축제(50, 0, 지금().minusDays(1), 지금().plusDays(1));
		// 대표 격자 한 건, 판정 범위의 다른 칸 한 건, 기간 밖 촬영 한 건 — 술어가 어긋나면 두 숫자가 갈린다.
		공개영상(격자(50, 0), 지금().minusHours(2));
		공개영상(격자(52, 1), 지금().minusHours(3));
		공개영상(격자(50, 0), 지금().minusDays(30));

		long lookupCount = missionQueryService.getMissionsByGrid(격자(50, 0)).get(0).videoCount();

		assertThat(lookupCount).isEqualTo(2);
		assertThat(lookupCount).isEqualTo(missionQueryService.getMissionDetail(missionId, null).videoCount());
	}
}
