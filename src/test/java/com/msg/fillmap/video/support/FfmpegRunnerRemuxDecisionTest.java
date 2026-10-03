package com.msg.fillmap.video.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * remux 판정 규칙(MSG-615 D2)을 ffprobe {@code -show_streams -of json} 출력 고정 문자열로 검증한다.
 * 순수 함수라 ffmpeg 없이 돈다 — 실제 ffprobe 출력에서 판정에 필요한 필드만 남긴 형태다.
 */
// 검증: FR-MEDIA-03, AC-615-01
@DisplayName("FfmpegRunner.isRemuxable — remux 판정 규칙")
class FfmpegRunnerRemuxDecisionTest {

	private static final String H264_720P = """
		{"codec_type":"video","codec_name":"h264","pix_fmt":"yuv420p","width":1280,"height":720}""";
	private static final String AAC = """
		{"codec_type":"audio","codec_name":"aac"}""";

	private static String streams(String... entries) {
		return "{\"streams\":[" + String.join(",", entries) + "]}";
	}

	@Nested
	@DisplayName("remux 가능")
	class Remuxable {

		// 검증: NFR-PERF-08
		@Test
		void 가로_720p_h264_yuv420p_aac_는_remux_가능하다() {
			assertThat(FfmpegRunner.isRemuxable(streams(H264_720P, AAC))).isTrue();
		}

		@Test
		void 오디오_스트림이_없어도_remux_가능하다() {
			assertThat(FfmpegRunner.isRemuxable(streams(H264_720P))).isTrue();
		}

		@Test
		void 회전_90_메타데이터가_있는_1280x720_도_remux_가능하다() {
			// 새 ffprobe 의 side_data_list 형태 — width/height 는 회전 적용 전 코딩 크기라 판정이 같다.
			String sideData = """
				{"codec_type":"video","codec_name":"h264","pix_fmt":"yuv420p","width":1280,"height":720,
				 "side_data_list":[{"side_data_type":"Display Matrix","rotation":-90}]}""";
			// 옛 ffprobe 의 tags.rotate 형태
			String tagRotate = """
				{"codec_type":"video","codec_name":"h264","pix_fmt":"yuv420p","width":1280,"height":720,
				 "tags":{"rotate":"90"}}""";

			assertThat(FfmpegRunner.isRemuxable(streams(sideData, AAC))).isTrue();
			assertThat(FfmpegRunner.isRemuxable(streams(tagRotate, AAC))).isTrue();
		}

		@Test
		void 세로로_코딩된_720x1280_도_remux_가능하다() {
			// MSG-616 이 세로 폰 촬영본을 720p 로 뽑으면 이 모양이다 — height<=720 으로 좁히면 전부 인코딩으로 떨어진다.
			String portrait = """
				{"codec_type":"video","codec_name":"h264","pix_fmt":"yuv420p","width":720,"height":1280}""";

			assertThat(FfmpegRunner.isRemuxable(streams(portrait, AAC))).isTrue();
		}

		@Test
		void iPhone_mov_의_데이터_트랙은_판정에_영향이_없다() {
			// mebx 타임드 메타데이터 — D3 의 -map 이 싣지 않으므로 판정에서도 무시한다.
			String data = """
				{"codec_type":"data","codec_name":"none","codec_tag_string":"mebx"}""";

			assertThat(FfmpegRunner.isRemuxable(streams(H264_720P, AAC, data, data))).isTrue();
		}

		@Test
		void full_range_yuvj420p_도_remux_가능하다() {
			String fullRange = """
				{"codec_type":"video","codec_name":"h264","pix_fmt":"yuvj420p","width":1280,"height":720}""";

			assertThat(FfmpegRunner.isRemuxable(streams(fullRange, AAC))).isTrue();
		}
	}

	@Nested
	@DisplayName("remux 불가")
	class NotRemuxable {

		@Test
		void 가로_1080p_h264_는_remux_불가다() {
			String fhd = """
				{"codec_type":"video","codec_name":"h264","pix_fmt":"yuv420p","width":1920,"height":1080}""";

			assertThat(FfmpegRunner.isRemuxable(streams(fhd, AAC))).isFalse();
		}

		@Test
		void hevc_는_remux_불가다() {
			String hevc = """
				{"codec_type":"video","codec_name":"hevc","pix_fmt":"yuv420p","width":1280,"height":720}""";

			assertThat(FfmpegRunner.isRemuxable(streams(hevc, AAC))).isFalse();
		}

		@Test
		void yuv420p10le_는_h264_여도_remux_불가다() {
			// High 10 프로파일 — 모바일 하드웨어 디코더가 못 푼다. profile 문자열이 아니라 pix_fmt 로 거른다.
			String high10 = """
				{"codec_type":"video","codec_name":"h264","profile":"High 10","pix_fmt":"yuv420p10le",
				 "width":1280,"height":720}""";

			assertThat(FfmpegRunner.isRemuxable(streams(high10, AAC))).isFalse();
		}

		@Test
		void pcm_오디오는_remux_불가다() {
			String pcm = """
				{"codec_type":"audio","codec_name":"pcm_s16le"}""";

			assertThat(FfmpegRunner.isRemuxable(streams(H264_720P, pcm))).isFalse();
		}

		@Test
		void 비디오_스트림이_없으면_remux_불가다() {
			assertThat(FfmpegRunner.isRemuxable(streams(AAC))).isFalse();
			assertThat(FfmpegRunner.isRemuxable("{\"streams\":[]}")).isFalse();
			assertThat(FfmpegRunner.isRemuxable("{}")).isFalse();
		}

		@Test
		void 긴_변이_1280_을_넘으면_짧은_변이_720_이하여도_remux_불가다() {
			// 울트라와이드 1600×720 — 짧은 변만 보면 통과하지만 픽셀 수가 720p 예산을 넘는다.
			String ultrawide = """
				{"codec_type":"video","codec_name":"h264","pix_fmt":"yuv420p","width":1600,"height":720}""";

			assertThat(FfmpegRunner.isRemuxable(streams(ultrawide, AAC))).isFalse();
		}
	}

	@Test
	void JSON_이_아니면_파일_불량_예외다() {
		// probeDurationSec 의 숫자 파싱 실패와 같은 분류 (D2) — 인프라 실패가 아니다.
		assertThatThrownBy(() -> FfmpegRunner.isRemuxable("not json"))
			.isInstanceOf(FfmpegRunner.InvalidMediaException.class);
	}
}
