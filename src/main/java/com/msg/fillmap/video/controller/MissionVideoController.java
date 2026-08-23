package com.msg.fillmap.video.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.msg.fillmap.auth.jwt.AuthPrincipal;
import com.msg.fillmap.mission.dto.MissionVideoUploadRequestDto;
import com.msg.fillmap.mission.dto.MissionVideoUploadResponseDto;
import com.msg.fillmap.mission.service.MissionVideoService;
import com.msg.fillmap.response.SuccessResponse;
import com.msg.fillmap.video.dto.GridVideoPageResponseDto;
import com.msg.fillmap.video.service.VideoService;

/**
 * 미션 상세 = 미션 하위 리소스(그 미션의 영상). 경로 접두사는 /api/missions 지만 videos 테이블을 다루는
 * Owner B 코드라 video 패키지에 둔다 (GridVideoController/MSG-127 과 같은 배치).
 * 격자 하위 리소스로 선언된 GridVideoController 에 얹지 않는 것은 태그·문서가 어긋나기 때문이다.
 * <p>
 * MSG-459 의 업로드 POST 가 이 클래스에 붙는다. 같은 경로의 읽기와 쓰기라 한 컨트롤러가 자연스럽고,
 * 무엇보다 <b>같은 단순 클래스명을 하나 더 만들 수 없다</b> — 기본 빈 이름 missionVideoController 가 겹쳐
 * 컴포넌트 스캔이 실패하고 애플리케이션이 뜨지 않는다(D-11). 조회는 비로그인, 업로드는 로그인이며
 * SecurityConfig 의 GET 한정 matcher 가 그 경계를 만든다 — POST 는 anyRequest().authenticated() 로 떨어진다.
 */
@Tag(name = "미션 영상 (Mission Videos)",
	description = "미션 상세 하단 \"이 미션의 영상\" 목록 API — 그 미션의 대상 격자에서 미션 기간에 촬영된 공개 영상.")
@RestController
@RequiredArgsConstructor
public class MissionVideoController {

	private final VideoService videoService;
	// 업로드 판정은 미션 유형·기간·대표 격자라는 미션 도메인 지식이라 mission 패키지 서비스에 위임한다.
	private final MissionVideoService missionVideoService;

	@Operation(
		summary = "미션 영상 목록 조회",
		description = "그 미션의 대상 격자에서 미션 기간에 촬영된 공개(PUBLIC)·READY 영상을 촬영 시각(recordedAt) "
			+ "최신순으로 페이지 조회한다 — 촬영 시각이 같으면 videoId 내림차순으로 갈린다. 기간이 없는 미션"
			+ "(코스·지속형)은 기간 조건 없이 과거 영상까지 담고, 기간이 끝난 미션도 목록은 그대로 조회된다. "
			+ "비공개·친구 공개·삭제·블라인드·인코딩 미완 영상은 본인 것이라도 제외되며, 응답은 누가 부르든 같다. "
			+ "첫 요청은 cursor 없이 부르고, hasNext 가 true 면 응답의 nextCursor 를 다음 요청 cursor 로 넘기면 "
			+ "이어진다. 커서는 발급된 그 미션 전용이라 다른 미션 커서는 400(INVALID_CURSOR)이고, 형식이 깨진 "
			+ "커서도 같다. size 는 1~50 밖이면 클램프된다. 조건에 맞는 영상이 없거나 존재하지 않는 missionId 는 "
			+ "빈 페이지다. 썸네일은 presigned GET URL 로 내려준다."
	)
	@GetMapping("/api/missions/{missionId}/videos")
	public SuccessResponse<GridVideoPageResponseDto> getMissionVideos(
		@Parameter(description = "미션 ID", example = "12") @PathVariable long missionId,
		@Parameter(description = "직전 응답의 nextCursor (opaque). 생략하면 첫 페이지")
		@RequestParam(required = false) String cursor,
		@Parameter(description = "페이지 크기 (1~50, 기본 20)") @RequestParam(defaultValue = "20") int size
	) {
		return SuccessResponse.of(videoService.getMissionVideos(missionId, cursor, size));
	}

	@Operation(
		summary = "미션 경유 영상 업로드 확정",
		description = "축제·팝업 미션에 영상을 올린다. 파일은 기존 presigned 발급"
			+ "(POST /api/videos/presigned-url)으로 S3 에 먼저 올리고 이 API 가 확정한다.\n\n"
			+ "좌표도 격자도 받지 않는다. 저장 위치는 서버가 그 미션의 대표 격자로 정하므로 같은 미션의 "
			+ "영상이 지도에서 한 칸에 모인다. 공개 범위는 PUBLIC 으로 고정되고, 업로드는 일반 업로드와 "
			+ "똑같이 그 격자의 점령을 만들며 뱃지·스트릭·미션 스탬프도 그대로 반영된다.\n\n"
			+ "같은 s3Key 로 다시 보내면 영상이 하나 더 생기지 않고 저장된 행 기준의 성공이 돌아온다. "
			+ "이때 occupied 는 false, newBadges 와 completedMissions 는 빈 배열이다(첫 응답 전용 필드).\n\n"
			+ "촬영 시각이 미래면 400 + developCode 3424, 키 형식이 아니거나 남의 pending 키면 400 + 3401 "
			+ "이다. 그 밖의 모든 실패는 409 + 12409 하나로 돌아온다 — 없는 미션, 코스처럼 대상이 아닌 유형, "
			+ "기간 밖, 촬영 시각이 미션 기간 밖, 대표 격자가 없는 미션, 이미 다른 자리에 쓴 키, S3 에 없는 "
			+ "키가 전부 여기 해당하며 사유는 갈라 주지 않는다. 이 응답을 받으면 그대로 재시도하지 말고 "
			+ "미션 상세를 다시 불러 업로드 가능 여부를 확인하고, 미션이 여전히 열려 있으면 presigned URL 을 "
			+ "새로 발급받아 파일부터 다시 올린다.\n\n"
			+ "인코딩이 끝나기 전에는 목록에 잡히지 않는다 — 업로드 직후 화면에 카드를 보여주려면 이 응답으로 "
			+ "낙관적으로 그린다(기존 업로드와 같은 성질)."
	)
	@PostMapping("/api/missions/{missionId}/videos")
	public SuccessResponse<MissionVideoUploadResponseDto> uploadMissionVideo(
		@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
		@Parameter(description = "미션 ID", example = "12") @PathVariable long missionId,
		@Valid @RequestBody MissionVideoUploadRequestDto request
	) {
		return SuccessResponse.of(missionVideoService.upload(principal.userId(), missionId, request));
	}
}
