-- V30__videos_visibility_default_public.sql
-- MSG-363: videos.visibility 의 DB 기본값을 실제 동작과 같은 PUBLIC 으로 맞춘다.
--
-- V1 이 PRIVATE 로 선언해 뒀지만 서버는 미지정 업로드를 PUBLIC 으로 저장한다
-- (VideoServiceImpl 의 "미지정(null)은 PUBLIC" — MSG-204 결정, glossary "영상" 절이 정책 정본).
-- 애플리케이션이 INSERT 마다 값을 명시하므로 이 DEFAULT 가 발동한 적은 없다. 즉 동작 변화가 없는
-- 표기 정정이고, 고치는 이유는 스키마만 읽는 쪽(ERD·덤프·직접 SQL 시드)이 반대 결론을 내리는 것을
-- 막기 위해서다.
--
-- 기존 행은 무변경 — SET DEFAULT 는 앞으로의 INSERT 에만 적용되고 저장된 값을 건드리지 않는다.
-- 메타데이터만 바꾸는 ALTER 라 테이블 재작성도 없다.

ALTER TABLE videos ALTER COLUMN visibility SET DEFAULT 'PUBLIC';
