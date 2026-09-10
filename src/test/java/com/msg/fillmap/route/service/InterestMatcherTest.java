package com.msg.fillmap.route.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 이어짐 판정 규칙 검증 (MSG-514 §도메인 로직 1). 사전은 배포 아티팩트의 실물
 * (route/interest-synonyms.json)을 그대로 로드한다 — 판정 규칙과 사전 편성 규율(억지 매칭 금지, FR-5)을
 * 같은 데이터로 고정해야 테스트가 배포물의 성질을 증명한다. 순수 함수라 스프링 없이 규칙 자체를 고정한다.
 */
@DisplayName("InterestMatcher — 관심사 이어짐 판정 (원문 포함 + 동의어 사전)")
class InterestMatcherTest {

	private final InterestMatcher matcher = new InterestMatcher(new ObjectMapper());

	// 검증: FR-ROUTE-18
	@Test
	void 관심사_원문이_재료에_있으면_사전_없이_일치한다() {
		// "국밥"은 사전의 어느 alias 에도 걸리지 않는다 — 원문 포함(규칙 1)만으로 일치해야 한다.
		assertThat(matcher.firstMatch(List.of("국밥"), "부산 국밥 골목 투어")).isEqualTo("국밥");
	}

	// 검증: FR-ROUTE-18
	@Test
	@DisplayName("표기가 달라도 사전 근거어가 재료에 있으면 일치한다 — \"맛집\" 관심사와 소개문의 \"국밥\"")
	void 표기가_달라도_사전_근거어가_재료에_있으면_일치한다() {
		assertThat(matcher.firstMatch(List.of("맛집"), "돼지국밥 거리 야시장 먹거리 소개")).isEqualTo("맛집");
	}

	// 검증: FR-ROUTE-18
	@Test
	void 사전에_없는_관심사는_원문_포함으로만_판정한다() {
		assertThat(matcher.firstMatch(List.of("글램핑"), "낙동강 글램핑 파크")).isEqualTo("글램핑");
		assertThat(matcher.firstMatch(List.of("글램핑"), "해운대 빛축제 현장")).isNull();
	}

	// 검증: FR-ROUTE-18
	@Test
	@DisplayName("한 글자 관심사는 원문 포함으로 후보에 걸리지 않는다 — \"회\"가 \"시민회관\"과 이어지지 않는다 (FR-5)")
	void 한_글자_관심사는_원문_포함으로_후보에_걸리지_않는다() {
		assertThat(matcher.firstMatch(List.of("회"), "부산 시민회관 기획 공연장")).isNull();
	}

	// 검증: FR-ROUTE-18
	@Test
	@DisplayName("한 글자 관심사는 정확 일치 alias 로 사전에 걸린다 — \"밥\"이 식당 근거어를 가진 후보와 이어진다 (FR-1)")
	void 한_글자_관심사는_정확_일치_alias로_사전에_걸린다() {
		assertThat(matcher.firstMatch(List.of("밥"), "원조 할매 식당 골목")).isEqualTo("밥");
	}

	// 검증: FR-ROUTE-18
	@Test
	@DisplayName("한 글자 alias 는 긴 관심사에 부분 포함으로 걸리지 않는다 — \"김밥천국\"이 \"밥\" alias 에 걸리지 않는다")
	void 한_글자_alias는_긴_관심사에_부분_포함으로_걸리지_않는다() {
		assertThat(matcher.firstMatch(List.of("김밥천국"), "원조 할매 식당 골목")).isNull();
	}

	// 검증: FR-ROUTE-18
	@Test
	@DisplayName("\"맛집\" 관심사는 음식 근거어가 없는 해안 산책 코스와 이어지지 않는다 (FR-5 사례 고정)")
	void 맛집_관심사는_음식_근거어가_없는_해안_산책_코스와_이어지지_않는다() {
		assertThat(matcher.firstMatch(List.of("맛집"),
			"남파랑길 3코스 코스 부산 앞바다를 따라 걷는 해안 산책로")).isNull();
	}

	// 검증: FR-ROUTE-18
	@Test
	@DisplayName("\"업데이트\"만 있는 재료는 \"데이트\" 관심사와 이어지지 않는다 — 차단어 전처리 (FR-5, PR #248)")
	void 업데이트만_있는_재료는_데이트_관심사와_이어지지_않는다() {
		// 시드 실측 34건 전부가 이 문형이다 — 원문 포함(규칙 1)은 evidence 수정과 별개 경로라 차단어로 막는다.
		assertThat(matcher.firstMatch(List.of("데이트"), "2026년도 축제 내용은 업데이트 중에 있습니다")).isNull();
	}

	// 검증: FR-ROUTE-18
	@Test
	@DisplayName("차단어를 지워도 진짜 데이트 표기는 일치한다 — 전처리가 인접한 정상 일치를 깨지 않는다")
	void 차단어를_지워도_진짜_데이트_표기는_일치한다() {
		assertThat(matcher.firstMatch(List.of("데이트"), "최근 업데이트된 연인 데이트 명소 소개")).isEqualTo("데이트");
	}

	// 검증: FR-ROUTE-18
	@Test
	void 관심사_배열의_앞_항목이_먼저_일치한다() {
		// 재료에 야경 근거어(불빛)와 음식 근거어(국밥)가 다 있다 — 배열 앞 항목이 이긴다.
		assertThat(matcher.firstMatch(List.of("야경", "맛집"), "불빛 축제와 국밥 골목")).isEqualTo("야경");
	}

	// 검증: FR-ROUTE-18
	@Test
	@DisplayName("사전 스키마 규칙을 지킨다 — key 중복 없음, 근거어 2자 이상, alias 비공백, 빈 배열 없음 (파일 정본 검증)")
	void 사전_스키마_규칙을_지킨다() throws Exception {
		JsonNode root;
		try (InputStream in = getClass().getResourceAsStream("/route/interest-synonyms.json")) {
			root = new ObjectMapper().readTree(in);
		}
		// 차단어(exclusions) — 다른 말을 품어 오탐을 만드는 합성어. 빈 배열은 허용, 항목은 트림 후 2자 이상.
		for (JsonNode word : root.path("exclusions")) {
			assertThat(word.asString().trim().length())
				.as("exclusions 2자 미만: %s", word).isGreaterThanOrEqualTo(2);
		}
		JsonNode concepts = root.path("concepts");
		assertThat(concepts.isArray()).isTrue();
		assertThat(concepts.size()).isGreaterThan(0);
		Set<String> keys = new HashSet<>();
		for (JsonNode concept : concepts) {
			String key = concept.path("key").asString();
			assertThat(key).isNotBlank();
			assertThat(keys.add(key)).as("key 중복: %s", key).isTrue();
			JsonNode aliases = concept.path("aliases");
			JsonNode evidence = concept.path("evidence");
			assertThat(aliases.size()).as("%s aliases 빈 배열", key).isGreaterThan(0);
			// 개념당 근거어 5건 이상 하한 (결정 2) — dev 시드 텍스트 실측에서 뽑은 최소 커버리지.
			assertThat(evidence.size()).as("%s evidence 5건 미만", key).isGreaterThanOrEqualTo(5);
			for (JsonNode alias : aliases) {
				assertThat(alias.asString().trim()).as("%s 빈 alias", key).isNotEmpty();
			}
			for (JsonNode word : evidence) {
				// 한 글자 근거어는 부분 문자열 오탐의 주범이다 ("회"가 "회관"·"회의"에 걸린다).
				assertThat(word.asString().trim().length()).as("%s 근거어 2자 미만: %s", key, word).isGreaterThanOrEqualTo(2);
				// 유형 라벨 맨몸 등재 금지 — 미션 재료에 라벨이 상시 포함이라 그 유형 전 미션이 의미 신호 0으로 걸린다.
				assertThat(word.asString().trim()).as("%s 유형 라벨 맨몸 근거어", key).isNotIn("축제", "팝업", "코스");
			}
		}
	}
}
