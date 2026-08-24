package com.msg.fillmap.video.support;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 업로드 확정 시점의 영상 컨테이너 판별 (MSG-392 §D1) — 읽어 온 앞부분 안에서 ISO BMFF 최상위 박스
 * 체인을 걸어, 허용 목록에 있는 박스만 나오고 구조가 깨지지 않았는지 본다. 브랜드(isom·qt 등)는 보지
 * 않는다 — 인코더마다 값이 달라 목록이 낡는 순간 정상 영상이 막힌다.
 *
 * 오프셋 4의 ftyp 고정 비교가 아닌 이유는 mov 가 허용 형식이기 때문이다. QuickTime 변형은 wide·mdat 로
 * 시작해 ftyp 가 아예 없을 수 있어, ftyp 만 통과시키면 정상 mov 를 전부 오거부한다.
 *
 * 거부와 통과의 기본값이 갈린다. 형식 위반이 확인된 입력만 거부하고, 창이 모자라 판정하지 못한 입력은
 * 통과시킨다 — 오거부가 이 검사에서 가장 비싼 실패다.
 *
 * S3 도 스프링도 contentLength 도 모르는 순수 함수다. 창이 객체 전체를 담았는지(wholeObject)는 호출부가
 * 계산해 넘긴다. 구현이 하나뿐이라 인터페이스도 빈도 만들지 않는다.
 */
public final class VideoSignature {

	/**
	 * 정상 파일의 최상위에 나올 수 있는 박스 타입. 모르는 타입을 전부 통과시키면 방어가 무너지므로 좁게
	 * 유지한다 — 4KB 를 넘는 텍스트 파일은 타입 자리가 인쇄 가능한 ASCII 이기 쉬워 이 목록이 막는다.
	 * 정상 인코더 산출물이 막히는 사례가 나오면 여기에 타입을 더하는 것이 정해진 대응이고, 규칙 구조는
	 * 바꾸지 않는다(오거부 예산 0건).
	 */
	private static final Set<String> ALLOWED_BOX_TYPES =
		Set.of("ftyp", "moov", "mdat", "wide", "free", "skip", "pnot", "uuid");

	/** 박스 헤더 = 4바이트 크기 + 4바이트 타입. */
	private static final int BOX_HEADER_BYTES = 8;

	/** size 필드가 1이면 헤더 뒤 8바이트가 64비트 largesize 다. */
	private static final int LARGESIZE_HEADER_BYTES = 16;

	/** 최상위 박스를 훑는 상한 — 넘어가면 판정 불가로 보고 통과시킨다. */
	private static final int MAX_BOXES = 4;

	private VideoSignature() {
	}

	/**
	 * @param head        객체 앞부분에서 읽어 온 바이트
	 * @param wholeObject 그 바이트가 객체 전체인가 — 참이면 창 끝이 곧 파일 끝(EOF)이라 잘린 구조가 거부되고,
	 *                    거짓이면 뒤가 더 있다는 뜻이라 판정 불가가 통과가 된다
	 */
	public static boolean looksLikeVideoContainer(byte[] head, boolean wholeObject) {
		if (head.length < BOX_HEADER_BYTES) {
			return false;
		}
		int p = 0;
		for (int walked = 0; walked < MAX_BOXES; walked++) {
			if (head.length - p < BOX_HEADER_BYTES) {
				return !wholeObject;   // 파일 끝이면 잘린 박스 헤더라 거부, 창 소진이면 판정 불가라 통과
			}
			long size = readUint32(head, p);
			String type = readType(head, p + 4);
			if (type == null) {
				return false;   // 타입 자리가 인쇄 가능한 ASCII 4글자가 아니다 (JPEG·PNG 가 여기서 걸린다)
			}
			if (!ALLOWED_BOX_TYPES.contains(type)) {
				return false;
			}

			long next;
			if (size == 0) {
				next = head.length;   // 파일 끝까지 이어지는 마지막 박스
			} else if (size == 1) {
				if (head.length - p < LARGESIZE_HEADER_BYTES) {
					return !wholeObject;   // 잘린 largesize(거부) vs 창 소진(통과)
				}
				long largesize = readUint64(head, p + BOX_HEADER_BYTES);
				if (largesize < LARGESIZE_HEADER_BYTES) {
					return false;
				}
				next = nextOffset(p, largesize, head.length);
			} else if (size < BOX_HEADER_BYTES) {
				return false;   // 헤더보다 작은 박스는 형식 위반
			} else {
				next = nextOffset(p, size, head.length);
			}
			// 크기 타당성 검사가 ftyp/moov 통과 판정보다 앞이다 — 순서를 뒤집으면 크기 24 를 선언한
			// 8바이트짜리 ftyp 객체가 타입만 보고 통과한다.
			if (wholeObject && next > head.length) {
				return false;   // 박스가 선언한 크기가 파일 밖을 가리킨다
			}

			if ("ftyp".equals(type) || "moov".equals(type)) {
				return true;
			}
			if (next >= head.length) {
				// wholeObject 면 박스가 파일을 빈틈없이 덮은 정확 종료, 아니면 창 소진이다.
				return true;
			}
			p = (int) next;
		}
		return true;   // 반복 상한 소진 — 판정 불가라 통과
	}

	/**
	 * 다음 박스의 시작 오프셋. 선언 크기는 부호 없는 64비트라 그대로 더하면 오버플로로 음수가 될 수 있다.
	 * 그러면 창 밖 박스가 파일 안으로 보여 파일 밖 거부가 통째로 새고, 절단된 오프셋에서 워크가 재시작해
	 * 오거부까지 난다 (크래시는 나지 않는다 — 절단값이 항상 창 안이라 더 잡기 어렵다). 창 밖 한 칸으로
	 * 포화시키면 이후 판정이 {@code head.length} 와의 비교뿐이라 의도한 결과가 그대로 유지된다.
	 */
	private static long nextOffset(int p, long boxSize, int headLength) {
		return p + Math.min(boxSize, headLength + 1L);
	}

	private static long readUint32(byte[] bytes, int at) {
		return ((long) (bytes[at] & 0xFF) << 24)
			| ((long) (bytes[at + 1] & 0xFF) << 16)
			| ((long) (bytes[at + 2] & 0xFF) << 8)
			| (bytes[at + 3] & 0xFF);
	}

	/** 최상위 비트가 선 largesize 는 음수로 읽히는데, 그런 크기는 호출부의 16 미만 검사가 거부한다. */
	private static long readUint64(byte[] bytes, int at) {
		long value = 0;
		for (int i = 0; i < 8; i++) {
			value = (value << 8) | (bytes[at + i] & 0xFF);
		}
		return value;
	}

	/** 박스 타입 4글자 — 인쇄 가능한 ASCII(0x20~0x7E)가 아니면 null. */
	private static String readType(byte[] bytes, int at) {
		for (int i = 0; i < 4; i++) {
			int c = bytes[at + i] & 0xFF;
			if (c < 0x20 || c > 0x7E) {
				return null;
			}
		}
		return new String(bytes, at, 4, StandardCharsets.US_ASCII);
	}
}
