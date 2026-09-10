package com.msg.fillmap.grid.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 뷰포트 집계 단위 (MSG-356). 행정동 코드 앞자리가 상위 단위를 가리키므로 매핑 테이블 없이
 * 자를 길이(codePrefixLength)만으로 묶음 키가 나온다 — 동 10자리, 시군구 5자리, 시도 2자리.
 * nameTokenIndex 는 "부산광역시 부산진구 부전2동" 전체 경로 이름에서 꺼낼 공백 구분 토큰 번호다.
 * maxSpanDeg 는 이 단위로 허용하는 bbox 한 변의 상한(도)이다. 시도도 무상한이 아니라 유한 상한(10도)을
 * 두는데, 전국 시야(약 5도)를 여유로 덮으면서도 유효 범위 밖 좌표를 걸러내기 위해서다 (§bbox 차등 상한).
 * ViewportBounds 와 같은 자리에 둔다(Owner B 가 계약 호출에 그대로 쓰는 값).
 */
@Getter
@AllArgsConstructor
public enum RegionUnit {

	DONG(10, 3, 1.0),
	SIGUNGU(5, 2, 4.0),
	SIDO(2, 1, 10.0),
	;

	private final int codePrefixLength;
	private final int nameTokenIndex;
	private final double maxSpanDeg;
}
