package com.msg.fillmap.route.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * 관심사 이어짐 판정 (MSG-514 §도메인 로직 1) — 판정 규칙과 동의어 사전 데이터를 한 곳에 모은다.
 * 관심사 배열 순서대로 (1) 원문 포함(트림 후 두 글자 이상만 — 한 글자는 "회"가 "시민회관"에 걸리는
 * 부분 문자열 오탐이라 건너뛴다), (2) 사전 경유(관심사가 걸린 개념들의 근거어 합집합 contains)를 보고
 * 첫 일치 관심사를 돌려준다. 사전은 classpath 리소스를 기동 시 1회 로드해 불변으로 보관하고, 스키마
 * 위반은 기동 실패다 — 조용한 미적용이 커버리지 하락으로 잠복하는 것을 막는다(결정 2). 순수 함수 판정이라
 * 같은 해석·같은 재료면 같은 결과다(FR-ROUTE-10).
 */
@Component
public class InterestMatcher {

	static final String DICTIONARY_RESOURCE = "/route/interest-synonyms.json";

	private final List<Concept> concepts;

	public InterestMatcher(ObjectMapper objectMapper) {
		this.concepts = load(objectMapper);
	}

	/** 첫 일치 관심사 (해석 결과 배열 순서) — 빈 항목은 건너뛰고, 어느 규칙에도 안 걸리면 null 이다. */
	public String firstMatch(List<String> interests, String haystack) {
		return interests.stream()
			.filter(interest -> !interest.isBlank())
			.filter(interest -> matches(interest.trim(), haystack))
			.findFirst()
			.orElse(null);
	}

	private boolean matches(String interest, String haystack) {
		// 규칙 1 — 원문 포함. 한 글자 관심사는 건너뛰고 규칙 2의 정확 일치 alias 로만 판정한다 (FR-5 가드).
		if (interest.length() >= 2 && haystack.contains(interest)) {
			return true;
		}
		// 규칙 2 — 사전 경유. 걸린 개념들의 근거어 합집합 중 하나라도 재료에 있으면 일치다 (순서 무관 = 결정적).
		return concepts.stream()
			.filter(concept -> concept.catches(interest))
			.flatMap(concept -> concept.evidence().stream())
			.anyMatch(haystack::contains);
	}

	private static List<Concept> load(ObjectMapper objectMapper) {
		DictionaryFile file;
		try (InputStream in = InterestMatcher.class.getResourceAsStream(DICTIONARY_RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("동의어 사전 리소스가 없습니다: " + DICTIONARY_RESOURCE);
			}
			file = objectMapper.readValue(in, DictionaryFile.class);
		} catch (IOException e) {
			throw new IllegalStateException("동의어 사전을 읽을 수 없습니다: " + DICTIONARY_RESOURCE, e);
		}
		return validate(file);
	}

	/** 기동 시 스키마 검증 (결정 2) — key 중복·빈 배열·빈 alias·한 글자 근거어가 하나라도 있으면 기동 실패. */
	private static List<Concept> validate(DictionaryFile file) {
		if (file == null || file.concepts() == null || file.concepts().isEmpty()) {
			throw new IllegalStateException("동의어 사전에 concepts 가 없습니다");
		}
		Set<String> keys = new HashSet<>();
		return file.concepts().stream()
			.map(entry -> {
				if (entry.key() == null || entry.key().isBlank() || !keys.add(entry.key())) {
					throw new IllegalStateException("동의어 사전 key 가 비었거나 중복입니다: " + entry.key());
				}
				return new Concept(entry.key(), trimmed(entry.key(), entry.aliases(), 1),
					trimmed(entry.key(), entry.evidence(), 2));
			})
			.toList();
	}

	private static List<String> trimmed(String key, List<String> values, int minLength) {
		if (values == null || values.isEmpty()) {
			throw new IllegalStateException("동의어 사전 " + key + " 에 빈 배열이 있습니다");
		}
		return values.stream()
			.map(value -> {
				String word = value == null ? "" : value.trim();
				if (word.length() < minLength) {
					throw new IllegalStateException(
						"동의어 사전 " + key + " 에 " + minLength + "자 미만 항목이 있습니다: '" + value + "'");
				}
				return word;
			})
			.toList();
	}

	/** 사전 파일 형태 (결정 2 스키마) — 로드 전용이라 record 로 받는다. */
	private record DictionaryFile(List<ConceptEntry> concepts) {
	}

	private record ConceptEntry(String key, List<String> aliases, List<String> evidence) {
	}

	/**
	 * 검증을 통과한 개념 하나. 관심사가 걸리는 조건은 alias 길이로 갈린다 — 두 글자 이상은 관심사가 그것을
	 * 포함하면("부산 맛집"→"맛집"), 한 글자는 정확 일치일 때만("밥"은 걸리고 "김밥천국"은 안 걸린다).
	 */
	private record Concept(String key, List<String> aliases, List<String> evidence) {

		boolean catches(String interest) {
			return aliases.stream().anyMatch(alias ->
				alias.length() == 1 ? interest.equals(alias) : interest.contains(alias));
		}
	}
}
