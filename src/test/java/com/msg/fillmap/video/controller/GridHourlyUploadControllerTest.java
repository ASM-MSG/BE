package com.msg.fillmap.video.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.msg.fillmap.auth.jwt.TokenProvider;
import com.msg.fillmap.user.entity.UserRole;
import com.msg.fillmap.video.dto.GridHourlyUploadResponseDto;
import com.msg.fillmap.video.dto.HourlyUploadCountResponseDto;
import com.msg.fillmap.video.service.VideoService;

/**
 * 격자 전역 시간대 분포 엔드포인트 (MSG-372). 게이트·KST 변환·24구간 채움은 서비스·쿼리 계약이라
 * 여기서는 라우팅·직렬화·인증 게이트만 본다 (GridGlobalVideoControllerTest 선례로 기능별 클래스 분리).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("GridVideoController 격자 전역 시간대 분포")
class GridHourlyUploadControllerTest {

	private static final long USER_ID = 42L;
	private static final String GRID_ID = "19422_9582";
	private static final String URL = "/api/grids/{gridId}/hourly-uploads";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenProvider tokenProvider;

	@MockitoBean
	private VideoService videoService;

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(USER_ID, UserRole.USER);
	}

	/** 10시 2건·18시 5건, 나머지는 0 인 24구간 응답 (서비스가 이미 채워서 준다). */
	private void givenDistribution() {
		List<HourlyUploadCountResponseDto> hours = IntStream.range(0, 24)
			.mapToObj(hour -> new HourlyUploadCountResponseDto(hour, switch (hour) {
				case 10 -> 2L;
				case 18 -> 5L;
				default -> 0L;
			}))
			.toList();
		given(videoService.getGridHourlyUploads(GRID_ID))
			.willReturn(new GridHourlyUploadResponseDto(GRID_ID, hours));
	}

	@Test
	// 검증: FR-MAP-09
	@DisplayName("시간대 분포 조회는 200 과 gridId·24구간 hours 를 반환한다")
	void 시간대_분포_조회는_200과_gridId와_24구간_hours를_반환한다() throws Exception {
		givenDistribution();

		mockMvc.perform(get(URL, GRID_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.gridId").value(GRID_ID))
			.andExpect(jsonPath("$.data.hours.length()").value(24))
			.andExpect(jsonPath("$.data.hours[0].hour").value(0))
			.andExpect(jsonPath("$.data.hours[0].count").value(0))
			.andExpect(jsonPath("$.data.hours[10].count").value(2))
			.andExpect(jsonPath("$.data.hours[18].count").value(5))
			.andExpect(jsonPath("$.data.hours[23].hour").value(23));
	}

	@Test
	@DisplayName("공개 영상이 없어도 200 과 전 구간 0 인 24구간이다")
	void 공개_영상이_없어도_200과_전_구간_0인_24구간이다() throws Exception {
		List<HourlyUploadCountResponseDto> empty = IntStream.range(0, 24)
			.mapToObj(hour -> new HourlyUploadCountResponseDto(hour, 0L))
			.toList();
		given(videoService.getGridHourlyUploads(GRID_ID))
			.willReturn(new GridHourlyUploadResponseDto(GRID_ID, empty));

		mockMvc.perform(get(URL, GRID_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.developCode").value(200))
			.andExpect(jsonPath("$.data.hours.length()").value(24))
			.andExpect(jsonPath("$.data.hours[0].count").value(0));
	}
}
