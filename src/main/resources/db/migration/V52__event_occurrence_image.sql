-- 이벤트 회차 대표 이미지 (MSG-538): 헤더에 걸리는 한 장. 정본 PRD US-002 판정문이 처음부터
-- 요구했으나 MSG-439 구현에서 빠져 있던 자리다.
--
-- 시리즈가 아니라 회차에 다는 이유: 해마다 열리는 행사는 회차마다 포스터가 달라, 시리즈에 한 장을
-- 달면 지난 회차 목록까지 같은 그림으로 덮인다 (스펙 D1).
-- 값은 버킷 상대 키만 담고 공개 주소는 조회 시점에 조립한다 (event_locations.image_key 와 같은 계약).
ALTER TABLE event_occurrences
    ADD COLUMN image_key VARCHAR(255);
