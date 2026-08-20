package com.msg.fillmap.event.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 대표 격자 3단 결정 (MSG-438, FR-EVENT-08 · PRD §4.1). 순수 함수라 DB 없이 검증한다.
 * 판정 입력이 시드 표현이 아니라 전개된 격자 집합이라는 점(표현 무관)과, 세 경로가 모두 결정적이라는 점이 초점이다.
 */
@DisplayName("RepresentativeGridResolver 대표 격자 3단 결정")
class RepresentativeGridResolverTest {

	/** 정수 사각형을 셀 단위로 펼친다 (시더 전개의 테스트용 축약). */
	private static Set<AreaCell> rect(int minY, int maxY, int minX, int maxX) {
		Set<AreaCell> cells = new LinkedHashSet<>();
		for (int y = minY; y <= maxY; y++) {
			for (int x = minX; x <= maxX; x++) {
				cells.add(new AreaCell(y, x));
			}
		}
		return cells;
	}

	@SafeVarargs
	private static Set<AreaCell> union(Set<AreaCell>... parts) {
		Set<AreaCell> cells = new LinkedHashSet<>();
		for (Set<AreaCell> part : parts) {
			cells.addAll(part);
		}
		return cells;
	}

	@Nested
	@DisplayName("1단 — 홀수 직사각형 정중앙")
	class OddRectangle {

		// 검증: FR-EVENT-08
		@Test
		@DisplayName("홀수 행·열 직사각형 영역의 대표 격자는 정중앙이다 (PRD 테스트 1)")
		void 홀수_행렬_직사각형_영역의_대표_격자는_정중앙이다() {
			Set<AreaCell> cells = rect(100, 108, 200, 208);   // 9행 × 9열

			assertThat(RepresentativeGridResolver.resolve(cells, null)).isEqualTo("104_204");
		}

		// 검증: FR-EVENT-08
		@Test
		@DisplayName("여러 사각형으로 표현된 홀수 직사각형 영역도 정중앙이 대표가 된다 (표현 무관 판정)")
		void 여러_사각형으로_표현된_홀수_직사각형_영역도_정중앙이_대표가_된다() {
			// 같은 9×9 를 위·아래 두 조각으로 그리고 가운데 줄을 양쪽이 겹쳐 갖는다 — 합집합은 같은 영역이다.
			Set<AreaCell> split = union(rect(100, 104, 200, 208), rect(104, 108, 200, 208));

			assertThat(split).isEqualTo(rect(100, 108, 200, 208));
			assertThat(RepresentativeGridResolver.resolve(split, null)).isEqualTo("104_204");
		}

		@Test
		@DisplayName("홀수 직사각형에서 정중앙과 다른 운영자 지정값은 모순으로 거절된다")
		void 홀수_직사각형에서_정중앙과_다른_운영자_지정값은_모순으로_거절된다() {
			Set<AreaCell> cells = rect(100, 108, 200, 208);

			// 조용히 무시하면 시드 파일과 저장값이 어긋난 채 남는다.
			assertThatThrownBy(() -> RepresentativeGridResolver.resolve(cells, "101_201"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("104_204")
				.hasMessageContaining("101_201");
		}

		@Test
		@DisplayName("정중앙과 같은 값을 지정하면 모순이 아니라 그대로 통과한다")
		void 정중앙과_같은_값을_지정하면_그대로_통과한다() {
			assertThat(RepresentativeGridResolver.resolve(rect(100, 108, 200, 208), "104_204"))
				.isEqualTo("104_204");
		}
	}

	@Nested
	@DisplayName("2단 — 운영자 지정")
	class Designated {

		@Test
		@DisplayName("짝수 열 직사각형은 정중앙 규칙 대상이 아니라 지정값을 쓴다 (9×8 경계)")
		void 짝수_행_직사각형은_정중앙_규칙_대상이_아니라_지정값을_쓴다() {
			Set<AreaCell> cells = rect(100, 108, 200, 207);   // 9행 × 8열 — 열이 짝수

			assertThat(RepresentativeGridResolver.resolve(cells, "104_203")).isEqualTo("104_203");
		}

		@Test
		@DisplayName("비직사각형 영역에서는 운영자 지정 대표 격자를 쓴다 (PRD 테스트 2)")
		void 비직사각형_영역에서는_운영자_지정_대표_격자를_쓴다() {
			// ㄴ 자 영역 — 경계 상자를 꽉 채우지 못해 직사각형 판정에서 빠진다.
			Set<AreaCell> cells = union(rect(100, 102, 200, 202), rect(100, 100, 203, 203));

			assertThat(RepresentativeGridResolver.resolve(cells, "101_201")).isEqualTo("101_201");
		}

		@Test
		@DisplayName("운영자 지정 대표 격자가 영역 밖이면 거절된다 (FR-3)")
		void 운영자_지정_대표_격자가_영역_밖이면_거절된다() {
			Set<AreaCell> cells = union(rect(100, 102, 200, 202), rect(100, 100, 203, 203));

			assertThatThrownBy(() -> RepresentativeGridResolver.resolve(cells, "102_203"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("영역 밖");
		}
	}

	@Nested
	@DisplayName("3단 — 영역 중심 최근접")
	class NearestToCentroid {

		@Test
		@DisplayName("지정이 없으면 영역 중심 최근접 포함 격자가 선택된다 (PRD 테스트 3)")
		void 지정이_없으면_영역_중심_최근접_포함_격자가_선택된다() {
			// 1행(3칸)과 3행(2칸) — 2행은 통째로 비어 중심 (1.8, 1.8) 이 비포함 자리에 떨어진다.
			Set<AreaCell> cells = union(rect(1, 1, 1, 3), rect(3, 3, 1, 2));

			// (n·gy - Sy)² + (n·gx - Sx)² 최소: (1,2) 가 17 로 유일 최소 (다음이 (1,1)·(3,2) 의 32·37).
			assertThat(RepresentativeGridResolver.resolve(cells, null)).isEqualTo("1_2");
		}

		@Test
		@DisplayName("중심 최근접 동률이면 남서쪽 격자로 결정된다 (결정성)")
		void 중심_최근접_동률이면_남서쪽_격자로_결정된다() {
			// 3×3 에서 한가운데를 뺀 고리 — 중심 (10,10) 은 비포함이고 네 변 가운데 칸이 모두 동률이다.
			Set<AreaCell> ring = rect(9, 11, 9, 11);
			ring.remove(new AreaCell(10, 10));

			// 동률 4칸 (9,10)·(10,9)·(10,11)·(11,10) 중 gridY 최소 → (9,10).
			assertThat(RepresentativeGridResolver.resolve(ring, null)).isEqualTo("9_10");
		}

		@Test
		@DisplayName("집합 순회 순서가 달라도 같은 격자가 나온다 (결정성)")
		void 집합_순회_순서가_달라도_같은_격자가_나온다() {
			Set<AreaCell> forward = union(rect(1, 1, 1, 3), rect(3, 3, 1, 2));
			Set<AreaCell> reversed = new LinkedHashSet<>();
			forward.stream().toList().reversed().forEach(reversed::add);

			assertThat(RepresentativeGridResolver.resolve(reversed, null))
				.isEqualTo(RepresentativeGridResolver.resolve(forward, null));
		}
	}

	@Test
	@DisplayName("빈 영역은 대표 격자가 성립하지 않아 거절된다")
	void 빈_영역은_대표_격자가_성립하지_않아_거절된다() {
		assertThatThrownBy(() -> RepresentativeGridResolver.resolve(Set.of(), null))
			.isInstanceOf(IllegalStateException.class);
	}
}
