package com.msg.fillmap.video.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.badge.dto.EarnedBadgeResponseDto;
import com.msg.fillmap.global.exception.ApiException;
import com.msg.fillmap.mission.dto.CompletedMissionResponseDto;
import com.msg.fillmap.mission.dto.MissionVideoUploadRequestDto;
import com.msg.fillmap.mission.dto.MissionVideoUploadResponseDto;
import com.msg.fillmap.mission.exception.MissionErrorCode;
import com.msg.fillmap.mission.service.MissionVideoService;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.video.dto.GridGlobalVideoResponseDto;
import com.msg.fillmap.video.dto.GridVideoPageResponseDto;
import com.msg.fillmap.video.service.VideoService;

/**
 * 미션 영상 엔드포인트 — 목록(MSG-390)과 미션 경유 업로드(MSG-459). 클램프·커서·후보 술어는 서비스·리포지토리 계약
 * (MissionVideoListServiceTest·MissionVideoListQueryTest)이라 여기서는 라우팅·직렬화·인증 게이트만 본다.
 * 경로 접두사는 /api/missions 지만 videos 를 다루는 코드라 video 패키지에 둔다(GridVideoController 선례).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("MissionVideoController 미션 영상 목록·업로드")
class MissionVideoControllerTest {

	private static final long USER_ID = 42L;
	private static final long MISSION_ID = 12L;
	private static final String URL = "/api/missions/{missionId}/videos";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private VideoService videoService;

	/** 업로드 판정은 미션 도메인 계약이라 목이다 — 이 클래스의 변수는 라우팅·직렬화·검증 게이트뿐이다. */
	@MockitoBean
	private MissionVideoService missionVideoService;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	// 검증: FR-MISSION-17
	@Test
	@DisplayName("응답 항목은 격자 전역 목록과 같은 여섯 필드다")
	void 응답_항목은_격자_전역_목록과_같은_여섯_필드다() throws Exception {
		given(videoService.getMissionVideos(eq(MISSION_ID), isNull(), eq(20))).willReturn(
			new GridVideoPageResponseDto(List.of(
				new GridGlobalVideoResponseDto(1042L, "https://bucket.s3/thumb.jpg?X-Amz-Signature=abc",
					(short) 27, 1200L, LocalDateTime.of(2026, 7, 20, 18, 3, 11), "busan.vlog")),
				true, "MTI6MTc4NDQ1NTgwMDAwMDAwMDoxMDM5"));

		mockMvc.perform(get(URL, MISSION_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.videos[0].videoId").value(1042))
			.andExpect(jsonPath("$.data.videos[0].thumbnailUrl")
				.value("https://bucket.s3/thumb.jpg?X-Amz-Signature=abc"))
			.andExpect(jsonPath("$.data.videos[0].durationSec").value(27))
			.andExpect(jsonPath("$.data.videos[0].viewCount").value(1200))
			.andExpect(jsonPath("$.data.videos[0].recordedAt").value("2026-07-20T18:03:11Z"))
			.andExpect(jsonPath("$.data.videos[0].nickname").value("busan.vlog"))
			.andExpect(jsonPath("$.data.videos[0].userId").doesNotExist())            // 작성자 id 비노출
			.andExpect(jsonPath("$.data.videos[0].processingStatus").doesNotExist())   // 목록은 항상 READY
			.andExpect(jsonPath("$.data.hasNext").value(true))
			.andExpect(jsonPath("$.data.nextCursor").value("MTI6MTc4NDQ1NTgwMDAwMDAwMDoxMDM5"));
	}

	@Test
	@DisplayName("조건에 맞는 영상이 없으면 빈 페이지 200 이다")
	void 조건에_맞는_영상이_없으면_빈_페이지_200이다() throws Exception {
		// 존재하지 않는 미션 ID 도 같은 응답이다 — 서비스가 빈 페이지를 돌려주고 컨트롤러는 그대로 싣는다.
		given(videoService.getMissionVideos(eq(MISSION_ID), isNull(), eq(20)))
			.willReturn(new GridVideoPageResponseDto(List.of(), false, null));

		mockMvc.perform(get(URL, MISSION_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.videos").isEmpty())
			.andExpect(jsonPath("$.data.hasNext").value(false))
			.andExpect(jsonPath("$.data.nextCursor").value(Matchers.nullValue()));
	}

	// 미인증 401 이었던 시나리오는 MSG-454(비로그인 열람 허용)로 뜻이 뒤집혀 삭제했다 — 무인증 200 계약은
	// MissionPublicAccessHttpTest 가 다섯 경로를 한 곳에서 본다.

	/**
	 * 미션 경유 업로드 (MSG-459). 판정 순서·12409 수렴·보존 확인은 서비스 계약
	 * (MissionVideoUploadServiceTest)이라 여기서는 라우팅·본문 역직렬화·응답 직렬화·검증 게이트만 본다.
	 * 같은 경로의 GET 과 한 컨트롤러에 사는 이유는 스펙 D-11 에 있다(빈 이름 충돌 회피).
	 */
	@Nested
	@DisplayName("미션 경유 업로드")
	class 업로드 {

		private String 본문(String s3Key, int durationSec) {
			return """
				{"s3Key": "%s", "durationSec": %d, "recordedAt": "2026-07-20T18:03:11Z"}"""
				.formatted(s3Key, durationSec);
		}

		// 검증: FR-MISSION-22
		@Test
		@DisplayName("업로드가 대표 격자와 완료 미션을 담아 200 으로 돌아온다")
		void 업로드가_대표_격자와_완료_미션을_담아_200으로_돌아온다() throws Exception {
			given(missionVideoService.upload(eq(USER_ID), eq(MISSION_ID), any()))
				.willReturn(new MissionVideoUploadResponseDto(1042L, "19422_9582", "UPLOADED", true,
					List.of(new EarnedBadgeResponseDto(7L, "POPUP_1", "팝업 입문", "팝업 1곳을 다녀왔어요", null)),
					List.of(new CompletedMissionResponseDto(MISSION_ID, "성수 팝업", "POPUP"))));

			mockMvc.perform(post(URL, MISSION_ID)
					.header(HttpHeaders.AUTHORIZATION, bearer())
					.contentType(MediaType.APPLICATION_JSON)
					.content(본문("videos/pending/42/6f1c1f0e.mp4", 15)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.developCode").value(200))
				.andExpect(jsonPath("$.data.videoId").value(1042))
				// 요청에 좌표도 격자도 없었는데 저장 격자가 응답에 실린다 — 서버가 정한다는 계약의 와이어 증거다.
				.andExpect(jsonPath("$.data.gridId").value("19422_9582"))
				.andExpect(jsonPath("$.data.processingStatus").value("UPLOADED"))
				.andExpect(jsonPath("$.data.occupied").value(true))
				.andExpect(jsonPath("$.data.newBadges[0].code").value("POPUP_1"))
				.andExpect(jsonPath("$.data.completedMissions[0].missionId").value(MISSION_ID))
				.andExpect(jsonPath("$.data.completedMissions[0].type").value("POPUP"));

			// 본문 3필드가 그대로 역직렬화돼 서비스로 넘어갔는지 — 촬영 시각은 UTC Z 표기로 들어온다.
			then(missionVideoService).should().upload(USER_ID, MISSION_ID,
				new MissionVideoUploadRequestDto("videos/pending/42/6f1c1f0e.mp4", (short) 15,
					LocalDateTime.of(2026, 7, 20, 18, 3, 11)));
		}

		// 검증: FR-MISSION-22
		@Test
		@DisplayName("영상 길이가 30초를 넘으면 핸들러에 닿기 전에 400 이다")
		void 영상_길이가_30초를_넘으면_핸들러에_닿기_전에_400이다() throws Exception {
			mockMvc.perform(post(URL, MISSION_ID)
					.header(HttpHeaders.AUTHORIZATION, bearer())
					.contentType(MediaType.APPLICATION_JSON)
					.content(본문("videos/pending/42/6f1c1f0e.mp4", 31)))
				.andExpect(status().isBadRequest());

			// @Valid 가 앞에서 거른다 — 서비스는 불려서도 안 된다(무효 요청이 확정 경로에 들어가지 않는다).
			then(missionVideoService).shouldHaveNoInteractions();
		}

		// 검증: FR-MISSION-26
		@Test
		@DisplayName("서비스가 던진 12409 가 409 응답으로 그대로 변환된다")
		void 서비스가_던진_12409가_409_응답으로_그대로_변환된다() throws Exception {
			given(missionVideoService.upload(eq(USER_ID), eq(MISSION_ID), any()))
				.willThrow(new ApiException(MissionErrorCode.MISSION_UPLOAD_UNAVAILABLE));

			// 컨트롤러는 try-catch 없이 던지기만 하고 GlobalExceptionHandler 가 공통 래퍼로 바꾼다.
			mockMvc.perform(post(URL, MISSION_ID)
					.header(HttpHeaders.AUTHORIZATION, bearer())
					.contentType(MediaType.APPLICATION_JSON)
					.content(본문("videos/pending/42/6f1c1f0e.mp4", 15)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.developCode").value(12409))
				.andExpect(jsonPath("$.message").value(MissionErrorCode.MISSION_UPLOAD_UNAVAILABLE.getMessage()))
				.andExpect(jsonPath("$.data").value(Matchers.nullValue()));
		}
	}
}
