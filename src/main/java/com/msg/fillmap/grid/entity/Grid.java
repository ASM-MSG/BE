package com.msg.fillmap.grid.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.msg.fillmap.region.entity.Region;

/**
 * 전역 격자 등록 row (grids). 조회 전용 최소 매핑 — 색칠 응답(MSG-73)에 필요한
 * 논리 식별자·정수 인덱스만 매핑한다. center_geom/bbox_geom·타임스탬프는 매핑하지 않는다
 * (ddl-auto=validate 는 미매핑 컬럼을 문제 삼지 않는다. 지오메트리는 접근 B 네이티브 쿼리로 처리).
 */
@Entity
@Table(name = "grids")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Grid {

	@Id
	@Column(name = "grid_id", length = 20)
	private String gridId;

	@Column(name = "grid_y", nullable = false)
	private Integer gridY;

	@Column(name = "grid_x", nullable = false)
	private Integer gridX;

	/**
	 * 격자 중심점이 속한 행정동 라벨 (MSG-167 저장 컬럼, MSG-466 에서 읽기 전용 매핑). 쓰기는 종전대로
	 * native upsert 경로가 하므로 insert·update 에서 뺀다 — 이 매핑은 행정 단위 집계의 그룹핑 키를
	 * JPQL 로 읽기 위한 것뿐이다. 무귀속(해상 등)은 NULL 이다.
	 */
	@Column(name = "region_code", length = 10, insertable = false, updatable = false)
	private String regionCode;

	/**
	 * 같은 region_code 컬럼의 읽기 전용 연관 (MSG-585). 이 연관은 JPQL 에서 행정동 이름을 경로 탐색
	 * (g.region.regionName)으로 잇기 위한 것이고, 쓰기는 종전대로 native upsert 가 하므로 insert·update 에서 뺀다.
	 * LAZY — 조회 JPQL 이 필요할 때만 조인한다.
	 *
	 * <p><b>위 regionCode 문자열 필드를 함께 남기는 이유</b> (2026-09-09 실측): 같은 컬럼을 두 번 매핑한 중복으로
	 * 보이지만 둘은 가리키는 대상이 다르다. 쿼리에 명시 조인이 없으면 {@code g.region.regionCode} 는 FK 컬럼으로
	 * 풀리지만(생성 SQL {@code g1_0.region_code}, 조인 없음), <b>조인이 이미 있으면 조인된 regions 쪽 컬럼</b>
	 * ({@code r1_0.region_code})으로 바인딩된다. LEFT JOIN 에서 이 차이가 동작을 바꾼다 — 격자에 라벨이 있는데
	 * regions 행이 없는 데이터 엣지에서 {@code g.region IS NOT NULL} 은 조인 실패 행을 걸러내 사실상 INNER JOIN 이
	 * 된다(findRegionCodeNames javadoc 이 막으려는 바로 그 경우). 실제로 바꿔 보니 생성 SQL 의 SELECT·WHERE 가
	 * {@code r1_0.region_code} 로 옮겨갔고 기존 테스트 194건은 그 변화를 잡지 못했다. FK 원값이 필요한 자리는
	 * 이 문자열 필드로 읽는다.
	 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "region_code", insertable = false, updatable = false)
	private Region region;
}
