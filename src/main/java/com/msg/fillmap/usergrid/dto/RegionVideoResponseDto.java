package com.msg.fillmap.usergrid.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.usergrid.service.RegionVideoView;

/**
 * 동 단위 내 영상 리스트 항목 응답 (MSG-167 §D3 · Open Q2). by-grid 로 행정동을 확인한 뒤 그 행정동 격자들에
 * 올린 내 영상을 모아본다. 127 GridVideoResponseDto 와 달리 gridId 를 담는다 — 디자인 "지역별 갤러리"
 * 항목마다 격자 라벨(예 "서면 A-14 #4")이 붙어 FE 가 항목별 gridId 를 쓰기 때문(127 은 격자 단위 조회라
 * gridId 불요). thumbnailUrl 은 presigned GET URL 이며 READY 이전(썸네일 key 없음)이면 null 이다.
 */
@Schema(description = "동 단위 내 영상 리스트 항목 — 그 행정동 격자들에 올린 내 영상 하나.",
	requiredProperties = {"videoId", "gridId", "processingStatus", "durationSec", "createdAt", "thumbnailUrl",
		"zoneName", "zoneCell"})
public record RegionVideoResponseDto(
	@Schema(description = "영상(방문 이벤트) ID. 개별 재생·교체·삭제 진입 키", example = "1042")
	Long videoId,

	@Schema(description = "영상이 속한 격자 ID \"{grid_y}_{grid_x}\" — 항목별 격자 라벨·지도 이동용",
		example = "19422_9582")
	String gridId,

	@Schema(description = "썸네일 presigned GET URL. READY 아니면(썸네일 key 없음) null", nullable = true)
	String thumbnailUrl,

	@Schema(description = "영상 처리 상태 (UPLOADED/ENCODING/BLURRING/READY/FAILED)", example = "READY")
	String processingStatus,

	@Schema(description = "영상 길이(초, 최대 30)", example = "12")
	Integer durationSec,

	@Schema(description = "업로드(방문) 시각 — 정렬 키", example = "2026-07-20T18:03:11Z")
	LocalDateTime createdAt,

	@Schema(description = "격자가 속한 구역 이름 (예 \"서면\"). 구역 밖 격자면 null — 이 화면은 행정동 헤더 "
		+ "아래 목록이라 폴백 이름을 문맥에서 알 수 있어 항목에 regionName 을 따로 담지 않는다",
		example = "서면", nullable = true)
	String zoneName,

	@Schema(description = "구역 내 위치 코드 \"{행}-{열}\" (행 A 는 구역 북단, 열 1 은 서단). zoneName 과 항상 "
		+ "쌍이라 구역 밖이면 함께 null", example = "I-6", nullable = true)
	String zoneCell
) {

	public static RegionVideoResponseDto from(RegionVideoView view) {
		return new RegionVideoResponseDto(
			view.videoId(),
			view.gridId(),
			view.thumbnailUrl(),
			view.processingStatus(),
			view.durationSec(),
			view.createdAt(),
			view.zoneName(),
			view.zoneCell()
		);
	}
}
