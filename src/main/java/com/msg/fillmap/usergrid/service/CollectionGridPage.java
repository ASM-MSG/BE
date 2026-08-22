package com.msg.fillmap.usergrid.service;

import java.util.List;

/** 행정동 전체 보기 개인 격자 페이지 내부 뷰. */
public record CollectionGridPage(
	List<CollectionGridView> items,
	boolean hasNext,
	String nextCursor
) {
}
