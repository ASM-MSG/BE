package com.msg.fillmap.video.service;

import java.util.List;

import com.msg.fillmap.badge.dto.EarnedBadgeResponseDto;
import com.msg.fillmap.video.entity.Video;

/**
 * 격자 지정 업로드 확정 결과 (MSG-440). 확정 코어가 만든 videos 행과, 응답 조립에 필요한 두 파생값을 함께
 * 돌려준다 — occupied 는 이 업로드가 그 격자의 첫 점령이었는지(user_grids 행이 새로 생겼는지)고,
 * newBadges 는 이 업로드로 새로 획득한 뱃지다(업로드 수·수집·꾸준함, 없으면 빈 목록).
 * <p>
 * 와이어 DTO 가 아니라 Owner B 내부(video → event) 반환 타입이다. video 는 소비 측에서 <b>읽기 전용</b>이며
 * 상태 변경은 video 도메인 서비스만 한다(영속 컨벤션 "타 도메인 엔티티는 읽기 전용").
 */
public record ConfirmedVideo(Video video, boolean occupied, List<EarnedBadgeResponseDto> newBadges) {
}
