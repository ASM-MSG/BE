-- 검색어 일별 집계 (MSG-258). 저장은 정규화 검색어 텍스트·날짜·횟수뿐 — 사용자 식별자·카카오 응답 필드 없음 (약관 경계, FR-7).
-- 삭제·보관 정책 없음 — 영구 축적이 요구사항 (FR-10, 배치 확장 재료).
CREATE TABLE search_keyword_daily_counts (
	id           BIGSERIAL    PRIMARY KEY,
	keyword_date DATE         NOT NULL,   -- KST(Asia/Seoul) 기준 일자 — 애플리케이션이 확정해 전달 (DB TIMESTAMP UTC 관례와 구분)
	keyword      VARCHAR(255) NOT NULL,   -- 정규화(trim·연속 공백 1칸·소문자) 후 텍스트
	search_count INT          NOT NULL DEFAULT 1,
	created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

	CONSTRAINT uq_search_keyword_daily UNIQUE (keyword_date, keyword)   -- UPSERT 충돌 키 + 조회 인덱스 겸용
);
