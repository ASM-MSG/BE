package com.msg.fillmap.usergrid.service;

/**
 * 격자 점령 사용자 내부 뷰 (서비스 간 계약, MSG-181 D6). regionName 은 격자 중심점 행정동 이름 —
 * 행정동 없는 격자(해상 등)면 null. 핫구역 진입 통지 문구 재료로 notification 만 소비한다.
 */
public record GridOccupantView(long userId, String regionName) {
}
