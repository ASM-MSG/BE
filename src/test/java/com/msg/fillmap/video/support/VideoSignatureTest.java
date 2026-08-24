package com.msg.fillmap.video.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("영상 컨테이너 판별 (MSG-392 D1)")
class VideoSignatureTest {

	private static final int WINDOW = 4096;

	@Nested
	@DisplayName("정상 컨테이너는 통과한다")
	class 정상_컨테이너 {

		@Test
		void ftyp로_시작하는_mp4_헤더는_통과한다() {
			byte[] head = box(16, "ftyp");

			assertThat(VideoSignature.looksLikeVideoContainer(head, true)).isTrue();
		}

		@Test
		void ftyp_없이_wide로_시작하는_QuickTime_구조도_통과한다() {
			byte[] head = concat(box(8, "wide"), box(16, "ftyp"));

			assertThat(VideoSignature.looksLikeVideoContainer(head, true)).isTrue();
		}

		@Test
		void mdat이_먼저_오고_창을_넘어가는_구조도_통과한다() {
			// 100000 을 선언한 mdat 은 창(4096) 밖으로 이어진다 — 뒤를 안 읽었으니 판정 불가라 통과다.
			byte[] head = Arrays.copyOf(header(100000, "mdat"), WINDOW);

			assertThat(VideoSignature.looksLikeVideoContainer(head, false)).isTrue();
		}

		@Test
		void moov로_시작하면_통과한다() {
			byte[] head = box(16, "moov");

			assertThat(VideoSignature.looksLikeVideoContainer(head, true)).isTrue();
		}

		@Test
		void uuid_박스로_시작해도_통과한다() {
			// ISO BMFF 표준 확장 박스라 정상 파일의 최상위에 나올 수 있다 — 허용 목록의 오거부 회귀 테스트.
			byte[] head = concat(box(16, "uuid"), box(16, "ftyp"));

			assertThat(VideoSignature.looksLikeVideoContainer(head, true)).isTrue();
		}

		@Test
		void 크기가_0인_마지막_박스도_통과한다() {
			byte[] head = Arrays.copyOf(header(0, "mdat"), 64);

			assertThat(VideoSignature.looksLikeVideoContainer(head, true)).isTrue();
		}

		@Test
		void 크기_필드가_1이면_64비트_largesize를_읽는다() {
			byte[] 정상 = Arrays.copyOf(largeHeader("mdat", 32), 32);
			byte[] 헤더보다_작은_largesize = Arrays.copyOf(largeHeader("mdat", 8), 32);

			assertThat(VideoSignature.looksLikeVideoContainer(정상, true)).isTrue();
			assertThat(VideoSignature.looksLikeVideoContainer(헤더보다_작은_largesize, true)).isFalse();
		}

		@Test
		void 창보다_짧아도_박스가_파일_끝에_정확히_맞으면_통과한다() {
			byte[] head = box(200, "mdat");

			assertThat(VideoSignature.looksLikeVideoContainer(head, true)).isTrue();
		}
	}

	@Nested
	@DisplayName("형식 위반이 확인되면 거부한다")
	class 형식_위반 {

		@Test
		void 선언한_크기가_파일_밖을_가리키면_거부된다() {
			// 타입이 ftyp 여도 크기 타당성 검사가 앞서므로 거부다 — 순서가 뒤집히면 8바이트 파일이 통과한다.
			byte[] head = header(24, "ftyp");

			assertThat(VideoSignature.looksLikeVideoContainer(head, true)).isFalse();
		}

		@Test
		void 텍스트_파일은_박스_타입이_허용_목록_밖이라_거부된다() {
			byte[] head = "Hello, this is not a video file at all.".getBytes(StandardCharsets.US_ASCII);

			assertThat(VideoSignature.looksLikeVideoContainer(head, true)).isFalse();
		}

		@Test
		void JPEG는_박스_타입_자리가_인쇄_불가능해서_거부된다() {
			byte[] head = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 0x4A, 0x46};

			assertThat(VideoSignature.looksLikeVideoContainer(head, true)).isFalse();
		}

		@Test
		void PNG는_박스_타입_자리가_인쇄_불가능해서_거부된다() {
			byte[] head = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

			assertThat(VideoSignature.looksLikeVideoContainer(head, true)).isFalse();
		}

		@Test
		void 여덟_바이트_미만_입력은_거부된다() {
			byte[] head = {0x00};

			assertThat(VideoSignature.looksLikeVideoContainer(head, true)).isFalse();
			assertThat(VideoSignature.looksLikeVideoContainer(head, false)).isFalse();
		}

		@Test
		void 박스_크기가_8_미만이면_거부된다() {
			byte[] head = Arrays.copyOf(header(4, "ftyp"), 16);

			assertThat(VideoSignature.looksLikeVideoContainer(head, true)).isFalse();
		}
	}

	@Nested
	@DisplayName("창 소진과 파일 끝은 결과가 갈린다")
	class 창_소진과_파일_끝 {

		@Test
		void largesize가_잘린_8바이트_객체는_거부된다() {
			byte[] head = header(1, "mdat");   // size 필드가 1인데 뒤따라야 할 largesize 8바이트가 없다

			assertThat(VideoSignature.looksLikeVideoContainer(head, true)).isFalse();
			assertThat(VideoSignature.looksLikeVideoContainer(head, false)).isTrue();
		}

		@Test
		void 정확히_4096바이트인_객체의_잘린_구조는_거부된다() {
			byte[] head = Arrays.copyOf(header(8192, "mdat"), WINDOW);

			assertThat(VideoSignature.looksLikeVideoContainer(head, true)).isFalse();
			assertThat(VideoSignature.looksLikeVideoContainer(head, false)).isTrue();
		}

		@Test
		void largesize가_64비트_상한에_가까워도_창_밖_판정이_유지된다() {
			// 선행 박스로 p>0 을 만들면 p + largesize 가 부호 있는 64비트를 넘어 음수가 된다.
			// 포화가 없으면 창 밖 박스가 파일 안으로 보여 ftyp 가 통과하고(오통과), 창 소진은 엉뚱한
			// 오프셋에서 재시작해 오거부가 된다. 두 방향을 함께 고정한다.
			byte[] ftyp가_파일_밖 = new byte[24];
			System.arraycopy(header(8, "free"), 0, ftyp가_파일_밖, 0, 8);
			System.arraycopy(largeHeader("ftyp", Long.MAX_VALUE), 0, ftyp가_파일_밖, 8, 16);
			byte[] mdat이_창_밖 = new byte[24];
			System.arraycopy(header(8, "free"), 0, mdat이_창_밖, 0, 8);
			System.arraycopy(largeHeader("mdat", Long.MAX_VALUE), 0, mdat이_창_밖, 8, 16);

			assertThat(VideoSignature.looksLikeVideoContainer(ftyp가_파일_밖, true)).isFalse();
			assertThat(VideoSignature.looksLikeVideoContainer(mdat이_창_밖, false)).isTrue();
		}

		@Test
		void 같은_바이트라도_창_소진이면_통과하고_파일_끝이면_거부된다() {
			byte[] head = Arrays.copyOf(concat(box(8, "wide")), 12);   // 둘째 박스 헤더가 4바이트에서 잘렸다

			assertThat(VideoSignature.looksLikeVideoContainer(head, false)).isTrue();
			assertThat(VideoSignature.looksLikeVideoContainer(head, true)).isFalse();
		}
	}

	/** 박스 헤더 8바이트 — 선언 크기(부호 없는 32비트 빅엔디언) + ASCII 4글자 타입. */
	private static byte[] header(long declaredSize, String type) {
		byte[] out = new byte[8];
		out[0] = (byte) (declaredSize >>> 24);
		out[1] = (byte) (declaredSize >>> 16);
		out[2] = (byte) (declaredSize >>> 8);
		out[3] = (byte) declaredSize;
		System.arraycopy(type.getBytes(StandardCharsets.US_ASCII), 0, out, 4, 4);
		return out;
	}

	/** size 필드 1 + 64비트 largesize 인 16바이트 헤더. */
	private static byte[] largeHeader(String type, long largesize) {
		byte[] out = Arrays.copyOf(header(1, type), 16);
		for (int i = 0; i < 8; i++) {
			out[8 + i] = (byte) (largesize >>> (56 - 8 * i));
		}
		return out;
	}

	/** 선언 크기와 실제 길이가 맞는 완결 박스 — 내용은 0 패딩이다(판별기가 내용을 보지 않는다). */
	private static byte[] box(int size, String type) {
		return Arrays.copyOf(header(size, type), size);
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] part : parts) {
			out.writeBytes(part);
		}
		return out.toByteArray();
	}
}
