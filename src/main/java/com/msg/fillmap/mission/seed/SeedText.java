package com.msg.fillmap.mission.seed;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * 시더 3종이 공유하는 화면용 텍스트 추출·정규화 (MSG-383 D3·D5 · MSG-394 D3). 세 원본이 같은 컬럼
 * (missions.place_name · description · image_url)에 값을 넣으므로 결측 규칙과 절단 길이와 {@code <br>}
 * 규칙과 이미지 키 형식을 한 곳에 둔다 — 축제 static 헬퍼를 팝업 시더가 그대로 호출하는 선례
 * (MSG-235 D2 "복제 금지")와 같은 판단이다.
 */
final class SeedText {

	/** missions.place_name VARCHAR(200) 방어 절단 — title 과 같은 방어선(§D2). */
	private static final int PLACE_NAME_MAX_LENGTH = 200;

	/** {@code <br>}·{@code <br/>}·{@code <br />} 대소문자 무관 (§D5). 다른 태그는 전수 확인 결과 없다. */
	private static final String BR_TAG = "(?i)<br\\s*/?>";

	/**
	 * 래스터 이미지 확장자 허용목록 — 목적은 형식 통일이 아니라 <b>보안</b>이다(MSG-384 D2 · MSG-394 D3).
	 * 공개 읽기인 missions/ 접두사에 {@code .svg}·{@code .html} 이 올라가면 우리 S3 도메인에서 스크립트가
	 * 실행된다. 스크립트가 JPEG 로 변환해 올리므로 실제로는 .jpg 만 오지만 리더가 그것을 믿지 않는다.
	 * jpg 하나로 좁히지 않는 것은 변환 대상이 바뀔 때 리더가 곧바로 막지 않게 하기 위해서다 —
	 * 래스터 안에서는 무엇이 오든 위험이 같다. {@code UserServiceImpl.validatePendingKey} 와 같은 이유·같은
	 * 방식이되, 그쪽 상수는 private 이고 업로드 Content-Type 짝 검증용이라 재사용하지 않는다.
	 */
	private static final Set<String> ALLOWED_IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "bmp", "gif");

	/**
	 * 객체 이름 문법 — 우리 키 규약이 {@code {안정 id}-{내용 해시}.jpg} 라 이 네 부류면 충분하다.
	 * 확장자 허용목록만으로는 <b>확장자가 실제로 요청되는 자원인지</b>를 보장하지 못하기 때문에 같이 건다:
	 * 저장 값은 키를 이스케이프 없이 이어 붙인 URL 이라, {@code payload.html#x.jpg} 는 {@code #} 뒤가
	 * 프래그먼트로 잘려 클라이언트가 {@code payload.html} 을 요청한다({@code ?} 는 쿼리로 같은 결과).
	 * 그 둘이 이 규칙을 넣은 직접적 이유이고, 덤으로 {@code \}(경로 구분자로 정규화하는 클라이언트)·
	 * 공백·{@code %}(퍼센트 인코딩으로 슬래시를 되살리는 {@code %2f})·제어문자가 함께 막힌다.
	 * 슬래시는 앞의 검사가 이미 잡지만 여기서도 걸린다 — 경로 탈출과 구분자 우회를 다른 메시지로 남겨
	 * 운영자가 산출물의 어느 쪽 결함인지 바로 알게 하려고 검사를 둘로 둔다.
	 */
	private static final Pattern OBJECT_NAME = Pattern.compile("[A-Za-z0-9._-]+");

	private SeedText() {
	}

	/**
	 * 화면용 텍스트 추출 — 누락·null·공백은 전부 null 이다. 빈 문자열을 그대로 저장하면 "값 없음"을 FE 가
	 * 두 가지(null·"")로 분기해야 한다(축제 homepage 결측 34%가 이 경로다).
	 *
	 * 결측이 거부가 아닌 이유는 판정에 쓰이는 값(좌표·날짜·id·스팟)과 무게가 다르기 때문이다(D4) — 화면 값
	 * 하나가 비었다고 산출물 전체를 되돌리면 운영자가 손댈 수 없다. 코스의 판정용 {@code requireText} 와
	 * 계약이 다른 것도 그래서다.
	 */
	static String text(JsonNode owner, String field) {
		JsonNode value = owner.path(field);
		if (!value.isTextual() || value.asString().isBlank()) {
			return null;
		}
		return value.asString();
	}

	static String truncatePlaceName(String placeName) {
		if (placeName == null || placeName.length() <= PLACE_NAME_MAX_LENGTH) {
			return placeName;
		}
		return placeName.substring(0, PLACE_NAME_MAX_LENGTH);
	}

	/**
	 * 소개문의 {@code <br>} 태그를 개행 하나로 바꾼다 (§D5). 다른 태그 제거·HTML 이스케이프는 하지
	 * 않는다 — 지금 없는 입력을 상대로 한 새니타이저이고, 값을 문자열로 렌더하는 클라이언트가 이미
	 * 이스케이프한다. 원문 그대로 노출이 PRD 확정 사항이라 요약·재작성도 없다.
	 */
	static String normalizeBreaks(String description) {
		return description == null ? null : description.replaceAll(BR_TAG, "\n");
	}

	/**
	 * 대표 이미지 키 검증 (MSG-384 D2 · MSG-394 D3) — 결측은 허용하고 형식 위반은 전량 거부한다.
	 * 종류별로 다른 것은 접두사 하나뿐이라 호출부가 그것만 준다(축제·팝업·코스).
	 *
	 * 셋을 함께 보는 것이 검증의 요점이다: 접두사만 보면 {@code missions/festival/x.svg} 가 통과하고,
	 * 확장자만 보면 다른 공개 경로에 키를 심을 수 있으며, 둘만 보면
	 * {@code missions/festival/../course/other.jpg} 가 양쪽을 통과한다. 마지막 키는 저장되는 URL 을
	 * 클라이언트가 정규화하는 순간 자기 접두사 밖의 객체를 가리킨다.
	 *
	 * 그래서 접두사 뒤는 <b>슬래시 없는 단일 객체 이름</b>이어야 한다 — 스펙의 키 형식이
	 * {@code missions/{종류}/{원본 id}-{해시}.jpg} 라 하위 경로가 애초에 없고, {@code ..} 만 막는 것보다
	 * 단순하면서 강하다. 이름 문법까지 좁히는 이유는 아래 상수 주석에 있다.
	 */
	static String imageKey(JsonNode row, String prefix) {
		String key = text(row, "imageKey");
		if (key == null) {
			return null;
		}
		if (!key.startsWith(prefix)) {
			throw new IllegalStateException("imageKey 가 " + prefix + " 아래가 아닙니다 (D2): " + key);
		}
		String objectName = key.substring(prefix.length());
		if (objectName.contains("/")) {
			throw new IllegalStateException(
				"imageKey 의 " + prefix + " 뒤가 단일 객체 이름이 아닙니다 (D2 경로 탈출 방어): " + key);
		}
		if (!OBJECT_NAME.matcher(objectName).matches()) {
			throw new IllegalStateException(
				"imageKey 의 객체 이름에 허용되지 않는 문자가 있습니다 (D2 URL 구분자 우회 방어): " + key);
		}
		// extensionAt == 0 은 이름이 비어 확장자만 남은 키(".jpg") — 확장자 부재(-1)와 같이 거부한다.
		// Locale.ROOT: 터키어 로케일에서 "GIF".toLowerCase() 가 "gıf"(점 없는 ı)가 돼 허용목록을 빗나간다.
		int extensionAt = objectName.lastIndexOf('.');
		if (extensionAt <= 0
			|| !ALLOWED_IMAGE_EXTENSIONS.contains(objectName.substring(extensionAt + 1).toLowerCase(Locale.ROOT))) {
			throw new IllegalStateException(
				"imageKey 가 허용된 래스터 확장자를 가진 파일 이름이 아닙니다 (D2): " + key);
		}
		return key;
	}
}
