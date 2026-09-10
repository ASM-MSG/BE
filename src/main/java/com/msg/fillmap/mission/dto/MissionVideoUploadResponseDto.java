package com.msg.fillmap.mission.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.badge.dto.EarnedBadgeResponseDto;

/**
 * 미션 경유 영상 업로드 확정 응답 (MSG-459 §API 1). 행사 업로드 응답에 completedMissions 하나를 더한
 * 모양이다 — 행사방은 미션과 연계되지 않아 그 필드가 없지만 이 경로는 판정을 태우기 때문이다.
 * gridId 는 서버가 정한 그 미션의 대표 격자이며 요청이 무엇을 보냈든 이 값으로 저장된다.
 * <p>
 * occupied·newBadges·completedMissions 는 <b>첫 응답 전용</b>이다 — 같은 s3Key 재시도(응답 유실 후
 * 재전송)는 저장된 행 기준으로 성공을 돌려주되 occupied 는 false, 두 배열은 비어 있다. 재시도 시점에
 * 첫 응답을 복원할 저장 컬럼이 없기 때문이며, 유실된 뱃지·스탬프 연출은 뱃지 목록과 미션 상세로
 * 이미 노출된다. processingStatus 는 신규 확정이면 UPLOADED, 재시도면 그 시점의 실제 값이다.
 */
@Schema(description = "미션 경유 영상 업로드 확정 응답",
	requiredProperties = {"videoId", "gridId", "processingStatus", "occupied", "newBadges", "completedMissions"})
public record MissionVideoUploadResponseDto(
	@Schema(description = "생성된 영상 ID", example = "1001")
	Long videoId,

	@Schema(description = "서버가 정한 그 미션의 대표 격자 ID", example = "19422_9582")
	String gridId,

	@Schema(description = "영상 처리 상태 (UPLOADED/ENCODING/BLURRING/READY/FAILED)", example = "UPLOADED")
	String processingStatus,

	@Schema(description = "이 업로드로 대표 격자를 처음 점령했는지 여부. 재시도 응답은 항상 false", example = "true")
	boolean occupied,

	@Schema(description = "이 업로드로 새로 획득한 뱃지 목록 — 없거나 재시도 응답이면 빈 배열")
	List<EarnedBadgeResponseDto> newBadges,

	@Schema(description = "이 업로드로 새로 발급된 스탬프 — 이미 받았거나 재시도 응답이면 빈 배열")
	List<CompletedMissionResponseDto> completedMissions
) {
}
