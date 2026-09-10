package com.msg.fillmap.event.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.msg.fillmap.badge.dto.EarnedBadgeResponseDto;

/**
 * 행사 영상 업로드 확정 응답 (MSG-440 §API 1). gridId 는 서버가 정한 그 위치의 대표 격자다.
 * <p>
 * occupied 와 newBadges 는 <b>첫 응답 전용</b>이다 — 같은 s3Key 재시도(응답 유실 후 재전송)는 저장된 행
 * 기준으로 성공을 돌려주되 occupied 는 false, newBadges 는 빈 배열이다. 재시도 시점에 첫 응답을 복원할
 * 저장 컬럼이 없기 때문이며, 유실된 뱃지 연출은 뱃지 목록과 알림 경로로 이미 노출된다.
 * processingStatus 는 신규 확정이면 UPLOADED, 재시도면 그 시점의 실제 값이다(인코딩이 진행됐을 수 있다).
 * <p>
 * completedMissions 는 없다 — 행사 업로드는 미션과 연계되지 않는다(MSG-438 제외 계약).
 * 격자 표시명 재료도 싣지 않는다 — 업로드 후 화면은 위치별 피드로 돌아가고 위치 이름이 이미 거기 있다.
 */
@Schema(description = "행사 영상 업로드 확정 응답",
	requiredProperties = {"videoId", "gridId", "processingStatus", "occupied", "newBadges"})
public record EventVideoUploadResponseDto(
	@Schema(description = "생성된 영상 ID", example = "1001")
	Long videoId,

	@Schema(description = "서버가 지정한 대표 격자 ID", example = "19422_9582")
	String gridId,

	@Schema(description = "영상 처리 상태 (UPLOADED/ENCODING/BLURRING/READY/FAILED)", example = "UPLOADED")
	String processingStatus,

	@Schema(description = "이 업로드로 대표 격자를 처음 점령했는지 여부. 재시도 응답은 항상 false", example = "true")
	boolean occupied,

	@Schema(description = "이 업로드로 새로 획득한 뱃지 목록 — 없거나 재시도 응답이면 빈 배열")
	List<EarnedBadgeResponseDto> newBadges
) {
}
