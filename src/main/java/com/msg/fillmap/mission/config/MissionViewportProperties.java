package com.msg.fillmap.mission.config;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.msg.fillmap.mission.entity.MissionType;

/**
 * 미션 목록 뷰포트 여유 마진 (MSG-398 D4) — "화면 밖 얼마까지 보여 줄까"라는 노출 정책 값이라
 * 종류별 프로퍼티로 뺀다. 기본 0 인 근거: 축제·팝업은 판정 범위(9×9, 사방 450m)가 이미 데이터에
 * 들어 있고, 코스는 경로 전체가 사각형에 들어와 여유가 필요 없다. FE 와 값이 정해지면 설정만 바꾼다.
 * D3 의 투영 보정 1칸과는 별개 값이다 — 그쪽은 정확도 보정이라 마진이 0 이어도 항상 붙는다.
 * HotZoneProperties 관례의 record 바인딩 — @ConfigurationPropertiesScan 이 자동 등록한다.
 *
 * @param marginMeters 종류별 여유 마진(미터). 미지정 종류는 0
 */
@ConfigurationProperties(prefix = "fillmap.mission.viewport")
public record MissionViewportProperties(
	@DefaultValue Map<MissionType, Integer> marginMeters
) {

	public MissionViewportProperties {
		marginMeters.forEach((type, meters) -> {
			// 음수 마진은 "판정 범위 줄이기"로 오독될 값 — 그 문제는 MSG-385(판정 범위 축소)가 원인 쪽에서
			// 고친다. 설정 오류는 런타임이 아니라 기동 시점에 잡는다 (HotZoneProperties fail-fast 관행).
			if (meters == null || meters < 0) {
				throw new IllegalArgumentException(
					"fillmap.mission.viewport.margin-meters." + type + " 는 0 이상이어야 합니다: " + meters);
			}
		});
	}

	/** 미터 마진을 격자 칸 수로 바꾼다 — 한 칸이 전국 어디서나 100m 라 나눗셈 하나로 끝난다(D2). */
	public long marginCells(MissionType type) {
		return (long) Math.ceil(marginMeters.getOrDefault(type, 0) / 100.0);
	}
}
