# 백엔드 감사 2026-09-08

> **tx 후속 정정:** 업무량·반복 처리·원자성까지 확장한 [재감사 보고서](/Users/ssomae/seongmin/coding/FillMap/docs/audit/2026-09-08-backend-audit-tx.md)가 이 문서의 tx 판단을 대체한다. 아래 tx P1 분류는 당시 기준의 기록이며, 새 기준에서는 P2로 재분류했다. 코드 수정에 따른 해소는 아니다.

- 기준 커밋: `c93d9763` (`feature/MSG-583-backend-audit-skill`). 기존 미커밋 파일은 유지했다.
- 렌즈: 전체 8개. 프로덕션 Java 601파일을 검색하고 쿼리 선언, 서비스 경계, 설정, 마이그레이션을 확인했다. 후보의 호출 경로와 결정 문서는 별도로 읽었다.
- 실측 환경: 로컬 PostgreSQL/PostGIS. `plan` 일부와 `tz`를 실측했다. `tx`의 외부 I/O 소요·연결 점유 시간은 미실측이며 `osiv`는 제한된 테스트만 수행했다. dev/prod에는 접속하지 않았다.
- 발단: 2026-09-07 박원형 멘토링 후속. 다음 멘토링 10월 8일. 이전 감사 보고서 없음.
- **이 문서는 검토 목록이다. P1도 곧 장애 확정을 뜻하지 않는다.** 스킬 기준상 트랜잭션 안의 S3 호출이 코드로 확인되면 P1 검토로 분류한다. 지연·고갈은 실측 전까지 추측이다.

## 1. 요약

| 렌즈 | 확인 범위 | P1 | P2 | P3 | 결론 |
|---|---|---:|---:|---:|---|
| native | 네이티브 108문장 | 0 | 0 | 40문장 | 68개는 유지 근거가 있고 40개는 전환 검토군. 최소 5개는 매핑 추가가 먼저 필요 |
| plan | 실제 SQL 캡처 2회, SELECT 플랜 7개 | 0 | 2묶음 | 0 | 주요 조회의 N+1은 재현되지 않음. 큰 데이터에서 인덱스·정렬 비용은 미확인 |
| tx | 실제 @Transactional 선언 143개, 간접 외부 호출 추적 | 3묶음 | 3묶음 | 0 | 영상·프로필·행사 이미지가 DB 작업 중 S3 호출. 보상·원자성 결정과 함께 검토 |
| osiv | 설정·컨트롤러 반환·연관 getter·제한된 false 실행 | 0 | 1묶음 | 0 | 끌 때 실패하는 코드 경로는 미발견. 전체 HTTP 요청의 안전성은 미입증 |
| tz | 코드·DDL·로컬 DB 시각 컬럼 58개 | 0 | 2묶음 | 0 | UTC 저장 규칙은 구현됨. 로컬 psql 세션은 KST, 서버 반영 여부는 미확인 |
| calls | 서비스 주입 간선 58개와 비동기 진입점 | 0 | 0 | 1묶음 | 분석한 클래스 그래프에 순환 없음. 쓰기/조회 혼합은 구조 선택 |
| naming | View 17, Projection 31, ResponseDto 117 | 0 | 0 | 9묶음 | 역할 구분은 대체로 유지. View 조립기 1개와 미명문화 관례가 정리 후보 |
| iface | 인터페이스 42개와 구체 Service 10개 | 0 | 0 | 5개 | 모든 인터페이스가 구현체 1개지만 계약·소비자·테스트를 따지면 일괄 삭제 근거 없음 |

건수는 native의 메서드 단위와 나머지 패턴 단위가 섞여 있으므로 합산하지 않는다. 파일별 병합 표는 같은 항목의 요약이며 중복 건수에 넣지 않는다.

먼저 볼 P1 검토 3개:
1. **영상 확정·교체:** DB flush 이후 S3 복사. 일반·미션·행사 업로드가 같은 코어를 쓴다. 중복 확정 방지와 뱃지 응답 계약을 보존하며 연결 점유를 측정할 대상이다.
2. **행사 제출·승인:** 이미지 HEAD/COPY가 상태 전이와 같은 트랜잭션이다. S3 실패 시 전체 롤백이라는 요구사항이 있다.
3. **프로필 이미지 갱신:** 사용자 조회 후 S3 HEAD/COPY를 수행한다. 영상 외에도 같은 경계가 있다.

멘토 지적과 달랐던 점: 인코딩·AI 처리 전체가 DB 트랜잭션인 것은 아니며, 이벤트 반응 집계는 배치 조회다. 시간대 정책과 도메인 간 인터페이스도 이미 결정 근거가 있다. QueryDSL·Command/Query 분리·비동기 전환의 채택 여부는 이 보고서에서 결정하지 않는다.

## 2. 파일별 병합 항목 (여러 렌즈에 걸친 것)

| 파일 | 렌즈 | 관찰 | 근거 | 판단 | 우선순위 |
|---|---|---|---|---|---|
| [video/service/VideoServiceImpl.java:175](../../src/main/java/com/msg/fillmap/video/service/VideoServiceImpl.java#L175) | tx, calls, naming | 확정·교체가 DB flush 후 S3 복사. 같은 서비스에 재생·조회도 있음 | MSG-247 클레임 선행, MSG-239 뱃지 응답 동봉, 아래 3.3/3.6/3.7 | 동기 계약을 먼저 설명하고 지연 측정. 메서드 분리만으로 I/O 경계가 바뀌지는 않음 | P1 검토; 가독성은 P3 |
| [event/submission/service/EventSubmissionServiceImpl.java:102](../../src/main/java/com/msg/fillmap/event/submission/service/EventSubmissionServiceImpl.java#L102), [AdminEventSubmissionService.java:188](../../src/main/java/com/msg/fillmap/event/submission/service/AdminEventSubmissionService.java#L188) | tx, calls, osiv | 상태 전이와 이미지 복사·DTO 조립이 서비스 TX 내부 | MSG-498·MSG-500, 아래 3.3/3.4 | S3 실패 원자성과 세션 의존을 각각 검증. 명칭·계층 전면 변경으로 묶지 않음 | P1 검토 |
| [user/service/UserServiceImpl.java:184](../../src/main/java/com/msg/fillmap/user/service/UserServiceImpl.java#L184) | tx, calls | 프로필 갱신의 S3 호출, 사용자 조회·변경 기능 동거 | 메서드의 고아 객체 수용 주석, 아래 3.3/3.6 | 실제 I/O 경계가 우선이고 쓰기/조회 분리는 선택 | P1 검토 |
| [event/service/EventVideoServiceImpl.java:165](../../src/main/java/com/msg/fillmap/event/service/EventVideoServiceImpl.java#L165) | plan, osiv, calls | 서비스 TX 안에서 피드·반응 배치 조회 후 DTO 반환 | 피드 23테스트, 아래 3.2/3.4 | 집계 N+1 미재현. 테스트 TX가 끝난 뒤 HTTP 직렬화까지 보장한 것은 아님 | 확인된 결함 없음 |

소스의 짧은 경로는 `src/main/java/com/msg/fillmap/` 기준이다. 이하 표의 파일·줄은 해당 커밋 기준이다.

## 3. 렌즈별 발견

### 3.1 native: 네이티브 쿼리 분류


실제 @Query 선언은 161개(네이티브 108개, JPQL 53개)다. 인벤토리의 162개는 EventSubmissionRepository:26 Javadoc의 @Query 설명을 포함한다. 아래 40개는 PostgreSQL 전용 연산이 핵심이 아닌 전환 검토군이다. 현재 엔티티 매핑 그대로 즉시 JPQL/파생 조회로 바꿀 수 있다는 뜻은 아니다. LIMIT은 Pageable, 튜플 비교는 동등 OR 조건, COUNT 캐스트는 반환값 변환으로 바꿀 수 있어 전용 기능으로 세지 않았다.

108개 중 전환 후보 40개, 현재 유지 근거가 있는 68개. 유지군은 PostgreSQL 전용 SQL뿐 아니라 기존 동시성·벌크 갱신 계약을 보존해야 하는 문장도 포함한다. 유지군 전체가 JPQL로 기술적으로 불가능하다는 뜻은 아니다.

경로 접두사는 src/main/java/com/msg/fillmap/. 표의 줄은 대표 시작 위치이며 후보 메서드는 전체 열거했다.

| Repository 파일:줄 | native | 유지 | 후보 | 후보 메서드 | 판단 |
|---|---:|---:|---:|---|---|
| [video/repository/VideoRepository.java:78](../../src/main/java/com/msg/fillmap/video/repository/VideoRepository.java#L78) | 22 | 11 | 11 | findGlobalCover, findGlobalVideos, findGlobalVideosAfter, findMissionVideos, findMissionVideosAfter, countMissionVideosByGrid, countVideosByMissionIds, isMissionHidden, findRegionNameByGridId, existsUserGrid, getRegionExploreSummary | P3 전환 검토 |
| [region/repository/RegionRepository.java:227](../../src/main/java/com/msg/fillmap/region/repository/RegionRepository.java#L227) | 12 | 9 | 3 | findStats, findNationalStat, findStatByRegion | P3 전환 검토 |
| [notification/repository/NotificationRepository.java:97](../../src/main/java/com/msg/fillmap/notification/repository/NotificationRepository.java#L97) | 12 | 7 | 5 | findPendingBatch, countSentSince, countPending, countPublished, countRecordedSince | P3 전환 검토 |
| [badge/repository/UserBadgeRepository.java:48](../../src/main/java/com/msg/fillmap/badge/repository/UserBadgeRepository.java#L48) | 10 | 6 | 4 | countMyVideos, countMyGrids, findMyRegionProgress, findFeatured | P3 전환 검토 |
| [grid/repository/GridRepository.java:24](../../src/main/java/com/msg/fillmap/grid/repository/GridRepository.java#L24) | 8 | 2 | 6 | findVideoCount, findOccupiedInRange, summarizeOccupiedByRegion, findOccupiedPage, findOccupiedPageAfter, findRegionNames | P3 전환 검토 |
| [usergrid/repository/UserGridRepository.java:84](../../src/main/java/com/msg/fillmap/usergrid/repository/UserGridRepository.java#L84) | 7 | 4 | 3 | getCollectionGrids, getRegionVideos, getGridOccupants | P3 전환 검토 |
| [user/repository/UserRepository.java:60](../../src/main/java/com/msg/fillmap/user/repository/UserRepository.java#L60) | 6 | 5 | 1 | findAllS3KeysByUserId | P3 전환 검토 |
| [notification/repository/PushTokenRepository.java:28](../../src/main/java/com/msg/fillmap/notification/repository/PushTokenRepository.java#L28) | 4 | 4 | 0 | 없음 | upsert와 벌크 삭제 유지 |
| [mission/repository/MissionRepository.java:105](../../src/main/java/com/msg/fillmap/mission/repository/MissionRepository.java#L105) | 4 | 3 | 1 | findCompleted | P3 전환 검토 |
| [streak/repository/StreakRepository.java:55](../../src/main/java/com/msg/fillmap/streak/repository/StreakRepository.java#L55) | 3 | 1 | 2 | findCurrentCount, findRemindTargets | P3 전환 검토 |
| [badge/repository/BadgeRepository.java:70](../../src/main/java/com/msg/fillmap/badge/repository/BadgeRepository.java#L70) | 3 | 2 | 1 | findAllWithMyStatus | P3 전환 검토 |
| [search/repository/SearchKeywordDailyCountRepository.java:37](../../src/main/java/com/msg/fillmap/search/repository/SearchKeywordDailyCountRepository.java#L37) | 2 | 1 | 1 | findTopKeywords | P3 전환 검토 |
| [mission/repository/UserMissionRepository.java:38](../../src/main/java/com/msg/fillmap/mission/repository/UserMissionRepository.java#L38) | 2 | 1 | 1 | countMyStampsByType | P3 전환 검토 |
| [notification/repository/NotificationOptOutRepository.java:22](../../src/main/java/com/msg/fillmap/notification/repository/NotificationOptOutRepository.java#L22) | 2 | 2 | 0 | 없음 | 멱등 쓰기 유지 |
| [event/submission/repository/EventSubmissionRepository.java:192](../../src/main/java/com/msg/fillmap/event/submission/repository/EventSubmissionRepository.java#L192) | 2 | 2 | 0 | 없음 | sequence 발급 유지 |
| [event/repository/EventVideoHelpfulRepository.java:29](../../src/main/java/com/msg/fillmap/event/repository/EventVideoHelpfulRepository.java#L29) | 2 | 2 | 0 | 없음 | 멱등 쓰기 유지 |
| [event/repository/EventNotificationSubscriptionRepository.java:24](../../src/main/java/com/msg/fillmap/event/repository/EventNotificationSubscriptionRepository.java#L24) | 2 | 2 | 0 | 없음 | 멱등 쓰기 유지 |
| [mission/repository/MissionGridRepository.java:31](../../src/main/java/com/msg/fillmap/mission/repository/MissionGridRepository.java#L31) | 1 | 0 | 1 | findVisitedGridIds | P3 전환 검토 |
| [event/repository/EventSeriesRepository.java:25](../../src/main/java/com/msg/fillmap/event/repository/EventSeriesRepository.java#L25) | 1 | 1 | 0 | 없음 | advisory lock 유지 |
| [user/repository/OrgEmailChangeRequestRepository.java:28](../../src/main/java/com/msg/fillmap/user/repository/OrgEmailChangeRequestRepository.java#L28) | 1 | 1 | 0 | 없음 | partial ON CONFLICT 유지 |
| [user/repository/OrgAccountRequestRepository.java:32](../../src/main/java/com/msg/fillmap/user/repository/OrgAccountRequestRepository.java#L32) | 1 | 1 | 0 | 없음 | partial ON CONFLICT 유지 |
| [zone/repository/ZoneRepository.java:23](../../src/main/java/com/msg/fillmap/zone/repository/ZoneRepository.java#L23) | 1 | 1 | 0 | 없음 | ON CONFLICT 유지 |

#### 관찰과 판단 근거

- 아래 대표 단건 스칼라·COUNT 예시는 현 매핑으로 JPQL/파생 조회 표현이 가능하다. 다른 후보는 미매핑 컬럼과 엔티티를 먼저 확인해야 한다. 조인 집계와 여러 응답 필드는 생성자/인터페이스 프로젝션이 필요하다. findNationalStat은 독립 집계 두 개이므로 JPQL 두 문장 또는 Hibernate 확장과 왕복 횟수 비교가 필요하다. 단일 JPQL 치환 가능으로 단정하지 않는다.
- NotificationRepository:201의 `SELECT count(*) FROM notifications WHERE status='PENDING'`는 `SELECT COUNT(n) FROM Notification n WHERE n.status=:status` 또는 countByStatus로 표현 가능하다.
- GridRepository:24의 `SELECT ug.video_count FROM user_grids ug WHERE ug.user_id=:userId AND ug.grid_id=:gridId`는 `SELECT ug.videoCount FROM UserGrid ug WHERE ug.id.userId=:userId AND ug.id.gridId=:gridId` 형태다. UserGrid.id 복합키 매핑과 일치한다.
- StreakRepository:55의 `SELECT current_count FROM streaks WHERE user_id=:userId`는 `SELECT s.currentCount FROM Streak s WHERE s.userId=:userId` 형태다. Streak.userId 매핑과 일치한다.
- 유지군은 PostGIS, ON CONFLICT, advisory/key-share 잠금, statement_timestamp, jsonb, LATERAL/CTE/UNION, sequence와 벌크 쓰기를 포함한다. RegionRepository.findDistricts는 split_part와 COLLATE C 정렬 계약을 지킨다.
- VideoRepository:416/429/441/464는 원자 카운터·대표 영상 변경이다. UserBadgeRepository:112/123는 대표 순위 다중행 갱신이다. 이들은 JPQL bulk로 옮길 기술적 여지가 있으나 기존 동시성·영속성 컨텍스트 계약 재검증 없이 단순 변환으로 보지 않는다.
- EventVideoHelpfulRepository:38의 DELETE는 MSG-441의 멱등 단일문 계약이다. JPQL bulk DELETE도 검토 가능하므로 native만 가능한 절대 제약으로 보지 않는다.
- UserRepository:93/116/138은 IS DISTINCT FROM으로 null-safe 동의 시각 갱신을 처리한다. 엔티티 읽기 후 변경으로 치환하면 원자성 재검증이 필요하다.

#### 매핑 또는 쿼리 구조 변경이 선행되는 후보

| 후보 | 현재 제약 | 필요한 검토 |
|---|---|---|
| RegionRepository.findStats/findNationalStat/findStatByRegion, UserBadgeRepository.findMyRegionProgress | region_stats에 대응하는 JPA 엔티티가 없음 | 읽기 엔티티 도입 또는 기존 native 유지 결정이 먼저 |
| BadgeRepository.findAllWithMyStatus | Badge.java에 retired_at 매핑이 없음 | retiredAt 매핑 추가 후 동일 은퇴 노출 조건 검증 |
| RegionRepository.findNationalStat | FROM 없이 서로 독립적인 두 SELECT 집계를 하나의 행으로 반환 | JPQL 두 문장과 왕복 비용 또는 Hibernate 확장 비교 |
| 조인/집계 프로젝션 후보 | 기존 도메인은 연관관계 대신 id를 보관함 | 생성자 프로젝션, 엔티티 간 명시 조인 지원 범위, null/정렬/타입 정합 검증 |

40개의 상세 JPQL 구현을 작성하거나 컴파일 검증하지 않았으므로 즉시 전환 가능 건수를 확정하지 않는다. 최소 5개는 엔티티/필드 매핑이 선행되어야 한다.

#### 기존 규칙과 문서

project-conventions.md:147은 파생/JPQL 우선이며 native를 PostgreSQL 기능·대량 배치로 한정한다. :154는 기존 코드 소급 리팩터링 금지다. 40개는 P3 검토 자료이며 QueryDSL 도입을 확정하지 않는다.

근거: docs/spec/MSG-390.md:284,516(미션 피드 native/2왕복), MSG-356.md:352(집계 원문), MSG-441.md:296,319,665(멱등 DELETE·반응 배치·실행 JPQL). 위키 `04-decisions/ADR 영속 계층 JPA 유지 MyBatis 반려.md:33`의 2026-08-03 '전부 PostgreSQL 전용'은 당시 약50건 기록이며 현재108건의 유지 근거로 확대할 수 없다. 일부 후보는 별도 native 선택 이유가 없으며 기존 id 보관·프로젝션 패턴을 따른다.


### 3.2 plan: 실제 SQL·실행 계획


첫 캡처 mentor-query.log: GridAggregationIntegrationTest, EventVideoInteractionRepositoryTest, CollectionGridsRepositoryTest 통과. Gradle BUILD SUCCESSFUL 12초.
두 번째 mentor-query-followup.log: EventVideoQueryServiceTest23, EventVideoVisibilityIntegrationTest2, UtcLocalDateTimeJsonCodecTest8, DtoTimeTypeGuardTest1, TimestampZoneIndependenceIntegrationTest3, 합계37 통과. Gradle BUILD SUCCESSFUL 16초.

capture-sql.sh 자체 exit141은 마지막 sort/head 파이프 SIGPIPE이며 내부 Gradle 종료코드0과 다르다. trap 원복 후 log_min_duration_statement=-1 확인. 멀티라인 SELECT는 스크립트 한 줄 요약에서 서로 합쳐지므로 원문 블록과 parameters를 다시 읽어 집계했다. fixture 생성과 부팅 조회를 API 요청 횟수로 세지 않았다.

| 관찰 위치 | 사실 | 실측 근거 | 판단 |
|---|---|---|---|
| [grid/repository/GridRepository.java:67](../../src/main/java/com/msg/fillmap/grid/repository/GridRepository.java#L67) | 공간함수 포함 집계 한 문장 | 첫 로그 집계SQL11회, 최대0.555ms | 검증 범위 N+1 없음. P2 실제 규모 플랜 확인 후보 |
| [usergrid/repository/UserGridRepository.java:84](../../src/main/java/com/msg/fillmap/usergrid/repository/UserGridRepository.java#L84) | cover/region LEFT JOIN한 한 문장 | 첫 로그7회, 최대0.119ms | 검증 범위 N+1 없음. P2 대량 도감 정렬 비용 미측정 |
| [event/service/EventVideoServiceImpl.java:185](../../src/main/java/com/msg/fillmap/event/service/EventVideoServiceImpl.java#L185) | 피드 개수와 무관하게 댓글/도움돼요 GROUP BY 두 번 | 둘째 로그1/2/10/20/50개 IN별 각1회, 1개/10개 동일 문장수 테스트 통과 | 멘토 집계 N+1 우려는 재현되지 않음 |
| event/repository/EventVideoRepository.java 피드 조회 | enum은 SQL 리터럴, Pageable은 fetch first 바인딩 | 첫페이지16회 max0.560ms, 커서1회0.045ms | 요청당1회. 테스트 반복수를 N+1로 판정하지 않음 |
| [hotzone/service/HotZoneServiceImpl.java:97,125,183](../../src/main/java/com/msg/fillmap/hotzone/service/HotZoneServiceImpl.java#L97) | 점수는 Redis, 라벨은 gridIds IN 배치 | 정적 코드; 두 캡처에 Redis 조회 미포함 | DB 집계로 오분류하지 않음. 미실측 |
| 업로드 확정 SELECT 전체 | S3 포함 확정 저장 전체 구간 미실행 | 일부 원자 갱신은 시간대 통합테스트에서 검증 | 요청당 SQL·I/O지연·커넥션 점유 미실측 |

#### 플랜 한계

테스트는 롤백되므로 EXPLAIN 때 테스트 행은 없다. regions3558, grids1, videos/user_grids/event_videos/helpfuls0행이었다(pg_stat 추정). 아래는 캡처된 SELECT에 실제 parameters를 대입한 EXPLAIN ANALYZE/BUFFERS다. API 실행 중 데이터 분포의 플랜과 같다고 주장하지 않는다. 인덱스 존재는 확인하되 실제 규모 성능은 미검증이다.

인덱스 근거: V1__init.sql:107/110(user별 영상, 공개 격자 영상), :124(user_grids 복합PK), V7__grids_region_code_index.sql:8, V24__user_grids_grid_index.sql:4, V39__event_room_schema.sql:84(위치 영상), V41__event_video_interaction.sql:25/39(댓글 video/id, 도움돼요 video/user PK). 범위 컬럼에 각각 인덱스가 없다는 이유만으로 P1로 판정하지 않았다. 사용자 PK 선두 조건으로 먼저 좁힐 수 있으므로 실제 플랜·카디널리티가 필요하다.


실행 계획[^1] 7건의 결과 행 수는 모두 `actual rows=0`이었다. `Seq Scan`[^2]이 나왔지만 빈 테이블 표본으로 인덱스 누락을 단정할 수 없다. 격자·도감 계획의 regions_pkey는 계획에만 있고 `never executed`였다. 아래 시간은 별도 EXPLAIN 실행값이며 위 DB 로그의 execute 시간, API 응답 시간과 서로 다르다.

| 캡처/쿼리 | EXPLAIN 실행 시간 | 최종 실제 행 수 |
|---|---:|---:|
| comments (mentor-query) | 2.275 ms | 0 |
| helpful (mentor-query) | 2.035 ms | 0 |
| grid-aggregation (mentor-query) | 12.304 ms | 0 |
| collection (mentor-query) | 1.902 ms | 0 |
| event-feed (mentor-query-followup) | 1.413 ms | 0 |
| comments (mentor-query-followup) | 1.490 ms | 0 |
| helpful (mentor-query-followup) | 1.522 ms | 0 |

<details>
<summary>캡처에서 추출한 실제 SELECT와 당시 테스트 바인딩 값 7개</summary>

**comments (mentor-query)**

```sql
select evc1_0.video_id,count(evc1_0.id) from event_video_comments evc1_0 where evc1_0.video_id in ('35821','35822') group by evc1_0.video_id
```

**helpful (mentor-query)**

```sql
select evh1_0.video_id,count(*) from event_video_helpfuls evh1_0 where evh1_0.video_id in ('35821','35822') group by evh1_0.video_id
```

**grid-aggregation (mentor-query)**

```sql
SELECT substring(g.region_code FROM 1 FOR '10') AS "regionCode", MIN(split_part(r.region_name, ' ', '3')) AS "name", AVG(ST_Y(g.center_geom::geometry)) AS "lat", AVG(ST_X(g.center_geom::geometry)) AS "lng", COUNT(*) AS "count" FROM user_grids ug JOIN grids g ON g.grid_id = ug.grid_id LEFT JOIN regions r ON r.region_code = g.region_code WHERE ug.user_id = '199380' AND g.grid_y BETWEEN '18267' AND '18301' AND g.grid_x BETWEEN '7216' AND '7224' GROUP BY 1 ORDER BY 1 NULLS LAST
```

**collection (mentor-query)**

```sql
SELECT ug.grid_id AS "gridId", ug.first_collected_at AS "firstCollectedAt", ug.last_uploaded_at AS "lastUploadedAt", ug.video_count AS "videoCount", ug.cover_video_id AS "coverVideoId", CASE WHEN v.processing_status = 'READY' THEN v.thumbnail_url END AS "coverThumbnailKey", v.duration_sec AS "coverDurationSec", r.region_name AS "regionName" FROM user_grids ug LEFT JOIN videos v ON v.id = ug.cover_video_id LEFT JOIN grids g ON g.grid_id = ug.grid_id LEFT JOIN regions r ON r.region_code = g.region_code WHERE ug.user_id = '199394' AND (CAST(NULL AS varchar) IS NULL OR g.region_code = CAST(NULL AS varchar)) ORDER BY CASE WHEN 'COLLECTED' = 'UPLOADED' THEN ug.last_uploaded_at END DESC, CASE WHEN 'COLLECTED' = 'COLLECTED' THEN ug.first_collected_at END DESC, ug.grid_id DESC LIMIT CAST('30' AS bigint)
```

**event-feed (mentor-query-followup)**

```sql
select v1_0.id,v1_0.thumbnail_url,v1_0.duration_sec,v1_0.created_at from event_videos ev1_0 join videos v1_0 on v1_0.id=ev1_0.video_id where ev1_0.event_location_id='7723' and v1_0.status='ACTIVE' and v1_0.visibility='PUBLIC' and v1_0.processing_status='READY' order by v1_0.created_at desc,v1_0.id desc fetch first '21' rows only
```

**comments (mentor-query-followup)**

```sql
select evc1_0.video_id,count(evc1_0.id) from event_video_comments evc1_0 where evc1_0.video_id in ('35834') group by evc1_0.video_id
```

**helpful (mentor-query-followup)**

```sql
select evh1_0.video_id,count(*) from event_video_helpfuls evh1_0 where evh1_0.video_id in ('35834') group by evh1_0.video_id
```

</details>

원본 DB 로그: `/tmp/backend-audit/mentor-query.log`, `/tmp/backend-audit/mentor-query-followup.log`. 모든 횟수는 parse/bind를 빼고 execute 문장만 셌다. 이 경로는 임시 자료라 삭제될 수 있으며, 재검토에 필요한 대표 SQL과 핵심 수치는 본문에 남겼다.

### 3.3 tx: 트랜잭션 범위


요약: 영상·프로필·행사 이미지 확정은 실제로 DB 트랜잭션 안에서 S3를 호출한다. 다만 복사 실패 시 DB를 되돌리는 계약과 보상 코드가 있으므로 장애 확정 항목이 아니라 우선 측정할 P1 검토항목이다. 알림 발행·메일·인코딩은 이미 DB 쓰기와 분리돼 있다. 이 감사에서 S3 소요와 DB 연결 점유 시간은 미실측이다.

파일 경로는 `src/main/java/com/msg/fillmap/` 기준이다. 정상으로 표기한 행은 발견 건수에 넣지 않는다.

| 파일:줄 | 관찰 사실 | 결정 근거/실측 | 판단 | 우선순위 |
|---|---|---|---|---|
| [video/service/VideoServiceImpl.java:175,237,251,291,316,935](../../src/main/java/com/msg/fillmap/video/service/VideoServiceImpl.java#L175) | 일반 업로드·격자 확정·교체가 `confirmAndStore/replaceVideo → copyToOriginal → S3.copyObject` 호출. 복사 전 INSERT/UPDATE flush로 DB를 사용한다. 미션 업로드(MissionVideoServiceImpl:92)와 행사 업로드(EventVideoServiceImpl:123)도 같은 코어에 합류한다 | MSG-247:164 클레임 선행, MSG-239:156 비동기 뱃지 지급 기각(응답 동봉). 시간 미실측 | DB 연결을 사용한 상태의 외부 I/O 경계가 확인됨. 동기 정합성 계약을 유지하며 소요부터 측정. 단순 afterCommit 이동은 답이 아님 | P1 검토 |
| [user/service/UserServiceImpl.java:184,188,196,314](../../src/main/java/com/msg/fillmap/user/service/UserServiceImpl.java#L184) | 프로필 갱신이 User 조회 뒤 S3 HEAD·COPY를 수행하고 이전 이미지는 afterCommit 삭제 | 메서드 주석에 5MB 이하 고아 허용 한계. 시간 미실측 | 영상 외 같은 패턴의 측정 후보. 복사 후 DB 실패 시 고아 가능성은 코드가 수용한 한계 | P1 검토 |
| [event/submission/service/EventSubmissionServiceImpl.java:102,108,176,196](../../src/main/java/com/msg/fillmap/event/submission/service/EventSubmissionServiceImpl.java#L102) 및 [AdminEventSubmissionService.java:188,259,284](../../src/main/java/com/msg/fillmap/event/submission/service/AdminEventSubmissionService.java#L188) | 제출·재제출·승인이 ImageStore.confirm/copyToPublic의 HEAD/COPY를 트랜잭션 안에서 실행 | MSG-498 이미지 계약, MSG-500:265 공개 복사·롤백 보상, :668 S3 실패 시 승인 전량 롤백 테스트 기록. 시간 미실측 | 명시된 원자성 계약. 긴 잠금·연결 점유 여부는 측정 필요 | P1 검토 |
| [video/service/VideoServiceImpl.java:935](../../src/main/java/com/msg/fillmap/video/service/VideoServiceImpl.java#L935), [event/submission/service/EventSubmissionImageStore.java:124](../../src/main/java/com/msg/fillmap/event/submission/service/EventSubmissionImageStore.java#L124) | 영상은 COPY 반환 뒤 rollback 보상을 등록하고 행사 이미지는 COPY 전에 등록 | MSG-247의 성공 후 보상 설계, ImageStore 주석의 응답 유실 대비. 장애 재현 미실측 | 추측: S3에서는 복사가 끝났지만 응답이 유실되면 영상은 보상 등록 전 예외로 빠져 고아가 남을 수 있음. 데이터 유실로 판정하지 않고 두 경로의 보상 일관성 검토 | P2 |
| [auth/service/OidcLoginService.java:43,50](../../src/main/java/com/msg/fillmap/auth/service/OidcLoginService.java#L43) → [auth/oidc/KakaoOidcIdTokenVerifier.java:30](../../src/main/java/com/msg/fillmap/auth/oidc/KakaoOidcIdTokenVerifier.java#L30) → [OidcDecoderConfig.java:29](../../src/main/java/com/msg/fillmap/auth/oidc/OidcDecoderConfig.java#L29) | 로그인 트랜잭션 안에서 JWK URI 기반 JWT decoder 호출. 키를 새로 가져오는 경로에서는 HTTP 가능 | MSG-135는 발급 통합 근거이며 키 조회까지 트랜잭션으로 묶은 별도 결정은 찾지 못함. HTTP/연결 획득 시점 미실측 | 키 캐시 적중/미적중을 나눠 경계 확인. 논리 TX 내부라고 HTTP 전에 물리 연결을 반드시 획득했다고 단정하지 않음 | P2 |
| [auth/service/AuthService.java:52,61,65](../../src/main/java/com/msg/fillmap/auth/service/AuthService.java#L52) 및 [RefreshTokenService.java:37,49,65](../../src/main/java/com/msg/fillmap/auth/service/RefreshTokenService.java#L37) | 로그인·재발급은 readOnly TX 안에서 Redis 세션을 저장/회전하고 로그아웃은 DB 푸시 토큰 해제와 Redis 폐기를 함께 수행 | MSG-135 발급·회전 계약. 시간 미실측 | readOnly라도 외부 캐시 읽기만 하는 경로가 아님. DB rollback이 Redis까지 되돌린다고 해석하지 말고 장애 경계를 검증 | P2 |
| [auth/service/PasswordService.java:109,143,198](../../src/main/java/com/msg/fillmap/auth/service/PasswordService.java#L109) 및 [user/service/OrgAccountIssueService.java:153](../../src/main/java/com/msg/fillmap/user/service/OrgAccountIssueService.java#L153) | 비밀번호 변경/재설정/재발송은 Redis 폐기·무효화 마커를 커밋 전에 기록 | MSG-497:589,594 fail-closed 결정, MSG-499 발급 계약 | 정상 사유 ①. Redis 실패 시 비밀번호 변경도 실패해야 하므로 무조건 밖으로 옮기면 보안 회귀 | 정상 |
| [video/service/VideoServiceImpl.java:408,458](../../src/main/java/com/msg/fillmap/video/service/VideoServiceImpl.java#L408) 및 [user/service/UserServiceImpl.java:120,436](../../src/main/java/com/msg/fillmap/user/service/UserServiceImpl.java#L120) | 삭제의 S3/세션 정리는 afterCommit. 삭제 함수가 자체 catch로 실패를 격리 | MSG-247·코드 주석 | 정상 사유 ②. commit 이후 호출이지만 응답 스레드에서 동기 실행한다. afterCommit은 비동기/물리 연결 반환 완료를 뜻하지 않음 | 정상 |
| [hotzone/service/HotScoreCommandServiceImpl.java:67](../../src/main/java/com/msg/fillmap/hotzone/service/HotScoreCommandServiceImpl.java#L67) 및 [search/service/impl/SearchKeywordCommandServiceImpl.java:101](../../src/main/java/com/msg/fillmap/search/service/impl/SearchKeywordCommandServiceImpl.java#L101) | 핫스코어는 commit 뒤 자체 executor, 검색 집계도 자체 executor에서 Redis/DB 기록 | MSG-233·MSG-251 주석. 시간 미실측 | 정상. 요청의 DB 트랜잭션으로 외부 I/O가 확장되지 않음 | 정상 |
| [user/service/OrgAccountIssueService.java:121,123](../../src/main/java/com/msg/fillmap/user/service/OrgAccountIssueService.java#L121); [AdminEmailChangeRequestService.java:109,142](../../src/main/java/com/msg/fillmap/user/service/AdminEmailChangeRequestService.java#L109); [event/submission/service/AdminApprovedEventService.java:133,154](../../src/main/java/com/msg/fillmap/event/submission/service/AdminApprovedEventService.java#L133) | TransactionTemplate 저장이 끝난 뒤 메일 발송. 메일 실패는 응답 emailSent로 분리 | MSG-499:725, MSG-500:797 | 정상. 같은 빈 호출 문제를 TransactionTemplate으로 피함 | 정상 |
| [video/service/VideoEncodingServiceImpl.java:57](../../src/main/java/com/msg/fillmap/video/service/VideoEncodingServiceImpl.java#L57); [AiBlurPoller.java:99](../../src/main/java/com/msg/fillmap/video/service/AiBlurPoller.java#L99); [HighlightPreviewServiceImpl.java:75](../../src/main/java/com/msg/fillmap/video/service/HighlightPreviewServiceImpl.java#L75) | 인코딩/AI/S3 작업 자체에는 TX 없음. 상태 쓰기는 별도 VideoStatusWriter REQUIRES_NEW | VideoStatusWriter:24 프록시 분리 근거 | 정상. 긴 미디어 처리 전체가 DB TX라는 멘토 가설은 현재 코드와 다름 | 정상 |
| [notification/relay/NotificationRelay.java:53,61,63](../../src/main/java/com/msg/fillmap/notification/relay/NotificationRelay.java#L53) 및 [notification/consumer/NotificationConsumer.java:81,108](../../src/main/java/com/msg/fillmap/notification/consumer/NotificationConsumer.java#L81) | Kafka 발행/FCM 발송은 TX 밖, 결과 기록만 TransactionTemplate. 알림 생성은 NotificationCommandService REQUIRED로 원래 업무와 함께 저장 | MSG-179:34 유실 0 계약, :576 단계별 TX | 정상. 발송 실패가 업무 커밋/재시도 카운트를 되돌리지 않도록 분리됨 | 정상 |

결정 문서: [MSG-247](../spec/MSG-247.md#L164), [MSG-239](../spec/MSG-239.md#L156), [MSG-498](../spec/MSG-498.md), [MSG-500](../spec/MSG-500.md#L265), [MSG-135](../spec/MSG-135.md), [MSG-497](../spec/MSG-497.md#L589), [MSG-499](../spec/MSG-499.md), [MSG-179](../spec/MSG-179.md#L576).

규칙 차단/한계: 인벤토리 외부 I/O 주입 목록 외에 auth의 간접 Redis/JWK 경로까지 추적했다. route의 RouteIntentClient/TmapWalkClient/캐시/한도, search의 KakaoLocalClient, EventViewerServiceImpl, HotZoneServiceImpl 호출에는 서비스 TX가 없었다. presign URL 생성만으로 S3 HTTP 호출이라 세지 않았다. afterCommit 검색의 8파일은 주석만 있는 파일도 포함하므로 실제 8개 구현이라는 뜻이 아니다. 감사 시점이 불명확한 기존 /tmp 로그는 근거에서 제외했다. 동일 클래스 내부 @Transactional 호출(예: OidcLoginService.login → issueForOidcUser, VideoStatusWriter.markEncoding → complete)은 바깥 TX가 이미 있으므로 'TX 없음' 결함으로 세지 않았다.


### 3.4 osiv


요약: `application.yml:15`의 open-in-view 설정은 주석이며 활성 false 설정을 찾지 못했다. 엔티티 직접 응답이나 서비스 반환 이후 연관 getter 사용으로 OSIV에 의존하는 경로는 정적 검토에서 발견하지 못했다. OSIV를 꺼도 안전하다는 최종 판단은 웹 요청 통합 검증 범위에 한정해야 한다.

| 파일:줄 | 관찰 사실 | 근거 | 판단 | 우선순위 |
|---|---|---|---|---|
| [application.yml:15](../../src/main/resources/application.yml#L15) | open-in-view:false가 주석이며 유지 이유가 `OSIV ?? (N+1 ??)`뿐 | 활성 설정 없음, 결정 근거 문서 미발견 | 설정 의도를 결정하고 OSIV=false 웹 요청 회귀 검증 후 반영할 후보 | P2 |
| [event/service/EventQueryServiceImpl.java:54,165,214,231](../../src/main/java/com/msg/fillmap/event/service/EventQueryServiceImpl.java#L54); [EventVideoServiceImpl.java:165,234](../../src/main/java/com/msg/fillmap/event/service/EventVideoServiceImpl.java#L165) | EventOccurrence/Location/Video 연관 getter를 서비스 TX 내부에서 읽고 DTO로 반환 | 클래스/메서드 TX와 EventOccurrenceController:77, EventVideoController:105 대조 | 현재 정적 경로에 OSIV 필요 근거 없음 | 정상 |
| [event/service/EventVideoInteractionServiceImpl.java:86,131,174](../../src/main/java/com/msg/fillmap/event/service/EventVideoInteractionServiceImpl.java#L86); [event/submission/service/EventSubmissionServiceImpl.java:136,156](../../src/main/java/com/msg/fillmap/event/submission/service/EventSubmissionServiceImpl.java#L136); [AdminEventSubmissionService.java:140,166](../../src/main/java/com/msg/fillmap/event/submission/service/AdminEventSubmissionService.java#L140) | 댓글/도움돼요와 신청 위치 컬렉션을 TX 내부에서 사용, View 헬퍼 역시 그 호출 범위 안 | 컨트롤러는 DTO 응답. Entity 타입 응답 필드 검색에서 직접 노출 미발견 | OSIV=false 검증 우선 영역이지만 현재 결함 확정 없음 | 정상 |

규칙 차단/한계: 대부분 기존 도메인은 id 보관 방식이다. `default_batch_fetch_size:100`은 쿼리 묶음 크기이며 서비스 밖 세션 부재를 해결하는 설정은 아니다. 서비스 직접 호출 테스트나 @Transactional 테스트의 성공만으로 HTTP 직렬화까지 입증할 수 없다. OSIV=false 실행에서 EventVideoQueryServiceTest 23건과 HotZoneAggregationHttpTest 17건이 통과했다. 전자는 테스트 자체에 @Transactional이 있어 서비스 종료 후 세션 단절을 검증하지 못한다. 후자는 MockMvc로 실제 빈을 호출하지만 성공 케이스가 빈 공해상 뷰포트라 DB 조회를 타지 않는다(HotZoneAggregationHttpTest:28). 따라서 연관관계 지연 로딩의 안전성을 입증하지 못한다. 요청당 연결 점유 시간은 미실측.


### 3.5 tz: 시간대

UTC 저장 규약은 [.claude/rules/project-conventions.md](../../.claude/rules/project-conventions.md#L172)에 있다. `Clock`을 쓰는 이유는 UTC 생성과 테스트 시각 고정이다. 중앙 빈 없이 생성자에서 고정하는 현재 패턴만으로 결함이라 볼 근거는 없다.

| 위치 | 관찰(사실) | 근거 | 판단 | 우선순위 |
|---|---|---|---|---|
| `docker-compose.yml:14`, `docs/spec/MSG-379.md:111` | compose TZ는 UTC인데 로컬 psql의 SHOW timezone은 Asia/Seoul | 이번 `/tmp/backend-audit/tz-db.txt`; 현재 DEFAULT 25개 모두 UTC 식 | 설정과 세션 값이 다름. 기존 볼륨의 설정 반영 여부 확인 대상이며 현재 앱 저장 오류는 입증되지 않음 | P2 |
| `docs/spec/MSG-379.md:359` | 서버 컨테이너 재생성·과거 KST 시딩분 보정이 잔여 단계로 기록됨 | [MSG-379 작업 로그](../spec/MSG-379.md#L359)와 status.md | 현재 서버 상태는 미확인. 문서가 낡은 것인지 실제 미반영인지 판단 보류 | P2 |

확인한 방어 장치:
- 실제 업무 테이블의 시각 컬럼은 58개이며 전부 `timestamp without time zone`[^3]이다. DEFAULT 25개 모두 `statement_timestamp() AT TIME ZONE 'utc'`; 나머지 33개는 DEFAULT가 없다. Flyway 자체 관리 컬럼 installed_on의 now()는 업무 컬럼 집계에서 제외했다.
- [UtcLocalDateTimeJsonCodec.java:50,67](../../src/main/java/com/msg/fillmap/global/config/UtcLocalDateTimeJsonCodec.java#L50)은 UTC Z 출력·오프셋 입력 환산을 담당한다. [MSG-376](../spec/MSG-376.md), [MSG-379](../spec/MSG-379.md)가 근거다.
- `build.gradle:88`에 bootRun JVM UTC 설정이 있다. IDE 직접 실행 안내는 [.claude/docs/infrastructure.md:116](../../.claude/docs/infrastructure.md#L116)에 있다.
- [StreakRemindScheduler.java:53,56](../../src/main/java/com/msg/fillmap/streak/service/StreakRemindScheduler.java#L53)은 KST 날짜, [WeeklySummaryScheduler.java:59,67](../../src/main/java/com/msg/fillmap/usergrid/service/WeeklySummaryScheduler.java#L59)은 KST 주 경계를 UTC로 환산한다. FestivalMissionSeeder:84와 PopupMissionSeeder:85의 KST 시계는 날짜 필터용이며 저장할 때 FestivalMissionSeeder:264,269에서 UTC로 환산한다.
- `Instant.now()` 텍스트 11건 중 주석 1건을 뺀 실제 10건은 JWT 발급·무효화·서명 URL 만료에 쓰인다. [OrgAccountIssueService.java:151](../../src/main/java/com/msg/fillmap/user/service/OrgAccountIssueService.java#L151)에 JWT 발급 시각과 맞추는 근거가 있다. 시간대 오류로 세지 않았다.

규칙으로 차단됨: `src/main`의 인자 없는 LocalDateTime.now(), Clock.systemDefaultZone(), 활성 @CreationTimestamp/@UpdateTimestamp 모두 0건. DTO 시각 타입 가드와 JSON 코덱 테스트 9건, KST 세션 강제 저장 테스트 3건이 통과했다. 저장 테스트는 오차 5초 미만을 단언하며 정확한 관측 오차값은 출력하지 않았다.

한계: psql 세션과 JDBC 세션은 다르다. 현재 서버 JVM·DB와 과거 저장 데이터에는 접속하지 않았다. 위키의 [DB 자료형 기준](../../../LLM-WIKI/03-specs/FillMap%20DB%20자료형·ENUM·GeoJSON%20기준.md)은 UTC 저장 원칙에 부합한다. 별도 시간대 ADR은 타겟 검색에서 발견하지 못했으며 상세 결정은 위 두 스펙에 있다.

### 3.6 calls: 호출 구조·진입점


요약: service 경로의 `private final *Service*` 주입 필드 58개 간선을 인터페이스→구현체로 연결해 DFS 순환 검사했으며 0건이었다. 전체 그래프는 `/tmp/backend-audit/service-graph.txt`. 이 범위에서 Owner A/B 사이 구현체 직접 주입도 발견하지 못했다. Command/Query 전면 분리는 기능 결함 해결이 아니라 구조 선택이다.

| 파일:줄 | 관찰 사실 | 근거 | 판단 | 우선순위 |
|---|---|---|---|---|
| [video/service/VideoServiceImpl.java:138,143,148](../../src/main/java/com/msg/fillmap/video/service/VideoServiceImpl.java#L138); [friend/service/FriendServiceImpl.java:63,65](../../src/main/java/com/msg/fillmap/friend/service/FriendServiceImpl.java#L63); [route/service/RouteCandidateCollector.java:65](../../src/main/java/com/msg/fillmap/route/service/RouteCandidateCollector.java#L65); [event/service/EventQueryServiceImpl.java:102](../../src/main/java/com/msg/fillmap/event/service/EventQueryServiceImpl.java#L102) | B→A 접점은 RegionStatsCommandService, HotScoreCommandService, ZoneNameQueryService, GridQueryService 등 인터페이스 | .claude/CLAUDE.md:150, project-conventions.md:162 | 경계 규칙과 일치. 도메인명을 합치면 양방향처럼 보여도 클래스 그래프 순환과 구분 필요 | 정상 |
| [mission/service/impl/MissionVideoServiceImpl.java:92](../../src/main/java/com/msg/fillmap/mission/service/impl/MissionVideoServiceImpl.java#L92) → [VideoServiceImpl.java:213](../../src/main/java/com/msg/fillmap/video/service/VideoServiceImpl.java#L213); [event/service/EventVideoServiceImpl.java:123](../../src/main/java/com/msg/fillmap/event/service/EventVideoServiceImpl.java#L123) → [VideoServiceImpl.java:213](../../src/main/java/com/msg/fillmap/video/service/VideoServiceImpl.java#L213) | 호출자와 코어가 REQUIRED로 동일 TX에 합류, 뱃지·스트릭·알림 DB 기록도 연결됨 | MSG-239 응답 동봉, MSG-179 업무/알림 원자성 | 실패 시 함께 rollback되는 의도된 경계 | 정상 |
| [video/service/VideoStatusWriter.java:58,69,88](../../src/main/java/com/msg/fillmap/video/service/VideoStatusWriter.java#L58) 외 | 외부 워커는 별도 빈 REQUIRES_NEW로 상태 전이를 커밋. 내부 complete 호출은 같은 기존 TX 사용 | 클래스 주석:24, 인코딩 호출부:60 | 프록시 진입점이 분리돼 있음. 내부 호출의 REQUIRES_NEW가 추가 TX를 만든다고 읽지 않아야 함 | 정상 |
| [mission/service/impl/MissionQueryServiceImpl.java:186](../../src/main/java/com/msg/fillmap/mission/service/impl/MissionQueryServiceImpl.java#L186) | ApplicationReadyEvent 웜업만 NOT_SUPPORTED | :178 기동 시 TX 열기 실패가 try 바깥으로 나가는 것을 방지한 근거 | 명시된 정상 예외 | 정상 |
| [video/service/VideoServiceImpl.java:175,507](../../src/main/java/com/msg/fillmap/video/service/VideoServiceImpl.java#L175); [friend/service/FriendServiceImpl.java:63](../../src/main/java/com/msg/fillmap/friend/service/FriendServiceImpl.java#L63); [user/service/UserServiceImpl.java:109,127](../../src/main/java/com/msg/fillmap/user/service/UserServiceImpl.java#L109) | 영상·친구·사용자는 한 서비스가 쓰기와 조회를 함께 제공. mission의 Query/Registration/Award, region의 Query/StatsCommand, hotzone의 Query/HotScore는 이미 역할 분리 | 현재 서비스 선언과 공개 메서드 | 변경 이유가 생긴 부분부터 Command/Query 분리 여부 판단할 재료. 일괄 분리 요구 없음 | P3 후보 |

규칙 차단/한계: 그래프는 `service` 경로의 final Service 타입 주입 기준이며 Controller/Repository/Client/일반 Component 타입·리플렉션 동적 조회까지 완전한 호출 그래프라는 뜻은 아니다. 스케줄러/리스너는 추가로 @Scheduled·@KafkaListener·@EventListener를 검색하고 NotificationRelay/Consumer·EncodingJobPoller·AiBlurPoller·Mission 웜업의 TX 경계를 직접 확인했다. 시더 전수 런타임 호출/프록시 테스트는 미실측. 타 도메인 Repository import만으로 Owner 경계 위반이라 단정하지 않았다(규칙은 서비스 계층 접점, 기존 read-only 연관 참조 허용).


### 3.7 naming


요약: `View` 17개, `Projection` 31개, `ResponseDto` 117개를 재계수했다. Projection은 전부 repository, View는 전부 service에 있다. ResponseDto는 dto 116개와 response/ApiResponseDto 1개다. 전체가 무질서하게 섞인 상태는 아니며, 의미가 다른 View 1개와 규칙 문서 부재가 주요 P3 후보다.

| 파일:줄 | 관찰 사실 | 근거 문서 | 판단 | 우선순위 |
|---|---|---|---|---|
| `.claude/rules/project-conventions.md:33`; [grid/service/GridCellView.java:9](../../src/main/java/com/msg/fillmap/grid/service/GridCellView.java#L9); [grid/repository/OccupiedGridProjection.java:7](../../src/main/java/com/msg/fillmap/grid/repository/OccupiedGridProjection.java#L7) | Request/ResponseDto와 Service 명명은 정의돼 있지만 View/Projection 명명 정의는 없다. 실제 View 16개는 record, Projection 31개는 저장소 조회 모델이다. | [MSG-406:146](../../docs/spec/MSG-406.md), [MSG-388:142](../../docs/spec/MSG-388.md)에는 개별 모델 역할이 명시됨 | 기존 관례를 규칙 한 곳에 적을 후보. 모든 타입 이름을 통일할 이유는 확인되지 않음 | P3 |
| [event/submission/service/EventSubmissionLocationView.java:29](../../src/main/java/com/msg/fillmap/event/submission/service/EventSubmissionLocationView.java#L29) | 나머지 View 16개와 달리 record가 아니라 두 서비스를 주입받아 응답 DTO를 조립하는 @Component다 | [MSG-500:626](../../docs/spec/MSG-500.md): 운영자 상세와 공용 위치 조립기 추출 | 공용 조립 자체는 근거가 있다. 이름만 Assembler 등 역할이 드러나는 형태 검토 | P3 |
| [hotzone/service/HotZoneServiceImpl.java:88](../../src/main/java/com/msg/fillmap/hotzone/service/HotZoneServiceImpl.java#L88), `:119` | 같은 서비스에서 상세는 HotZoneView, 집계는 HotZoneRegionAggregateResponseDto를 반환한다 | [MSG-349 PRD:162](../../docs/prd/MSG-349-prd.md); [명명 규칙](../../.claude/rules/project-conventions.md)에는 서비스 반환 모델 강제 규칙 없음 | 반환 모델 역할의 일관성 검토. 기능 결함은 아니고 별도 View 계층 추가가 반드시 필요한 것도 아님 | P3 |
| [grid/service/impl/GridQueryServiceImpl.java:46](../../src/main/java/com/msg/fillmap/grid/service/impl/GridQueryServiceImpl.java#L46); [route/service/RouteRecommendServiceImpl.java:54](../../src/main/java/com/msg/fillmap/route/service/RouteRecommendServiceImpl.java#L54); [event/service/EventQueryServiceImpl.java:90](../../src/main/java/com/msg/fillmap/event/service/EventQueryServiceImpl.java#L90); [mission/service/impl/MissionQueryServiceImpl.java:102](../../src/main/java/com/msg/fillmap/mission/service/impl/MissionQueryServiceImpl.java#L102) | MAX_VIEWPORT_SPAN_DEG=0.5가 4곳에 있고 위경도 정의역 상수도 반복됨 | [MSG-457:102](../../docs/spec/MSG-457.md)은 미션과 같은 값이라고 명시. [grid/dto/ViewportBounds.java:5](../../src/main/java/com/msg/fillmap/grid/dto/ViewportBounds.java#L5)는 유효성 판정을 서비스 책임으로 둠 | 값 불일치 없음. 앞으로 동일 정책을 함께 변경할 때 공통 상수 검토. 경로별 오류코드와 허용 경계는 유지해야 함 | P3 |
| [badge/service/BadgeAwardServiceImpl.java:55](../../src/main/java/com/msg/fillmap/badge/service/BadgeAwardServiceImpl.java#L55); [search/service/impl/SearchKeywordCommandServiceImpl.java:46](../../src/main/java/com/msg/fillmap/search/service/impl/SearchKeywordCommandServiceImpl.java#L46) 외 | Asia/Seoul ZoneId 생성이 16개 파일에 반복된다. 전부 동일한 ID | [시각 처리 규칙](../../.claude/rules/project-conventions.md#L172), [MSG-483:186](../../docs/spec/MSG-483.md) | 날짜 정책을 찾기 쉽게 하는 공통 상수 후보. 현재 값 차이로 인한 결함 근거는 없음 | P3 |
| [video/service/VideoEncodingServiceImpl.java:57](../../src/main/java/com/msg/fillmap/video/service/VideoEncodingServiceImpl.java#L57) | encode 본문 103줄, if/for/while/switch/catch 합계 12개, 최대 중괄호 깊이 3. 파일 처리, 상태 전이, 실패 계측 분기를 함께 관리 | [MSG-494:335](../../docs/spec/MSG-494.md), [MSG-456:156](../../docs/spec/MSG-456.md) | 매체 처리 단계별 가독성 검토 후보. finally 정리, claim 유실, 실패 계측 1회 보장은 분리 후에도 유지해야 함 | P3 |
| [video/service/VideoServiceImpl.java:699](../../src/main/java/com/msg/fillmap/video/service/VideoServiceImpl.java#L699) | getVideoPlayback 본문 70줄, 조건/예외 분기 9개, 깊이 3. 접근 허용 후 URL 발급·조회수·표시명·닉네임 조립 | [MSG-206:281](../../docs/spec/MSG-206.md) | 접근 판정 분리 검토 후보. 현재 단계별 주석과 명시적 허용 분기가 있어 길이만으로 결함 판단하지 않음 | P3 |
| [route/service/RouteWalkPathServiceImpl.java:57](../../src/main/java/com/msg/fillmap/route/service/RouteWalkPathServiceImpl.java#L57) | walkPaths 본문 69줄, 조건/루프/예외 분기 8개, 깊이 3. 캐시, 외부 한도, 장애 후 단락 플래그가 루프에 존재 | [MSG-483:344](../../docs/spec/MSG-483.md) | 구간 결과 계산을 읽기 쉽게 할 후보. 한도 소진 뒤 캐시 성공 유지와 외부 장애 후 호출 중단은 요구사항이므로 삭제 불가 | P3 |
| [video/service/AiBlurPoller.java:137](../../src/main/java/com/msg/fillmap/video/service/AiBlurPoller.java#L137) | poll 본문 61줄, 조건/예외 분기 9개, 깊이 4. 404·명시 실패·사전 검사 실패·완료·알 수 없는 상태를 분기 | [MSG-283](../../docs/spec/MSG-283.md), [MSG-286](../../docs/spec/MSG-286.md) | 상태 처리 가독성 검토 후보. 이미 complete와 failIfTimedOut을 분리했으므로 추가 계층화의 이득은 검토 필요 | P3 |

규칙 차단 및 제외:
- 응답 타입 접미사를 Dto 밖으로 옮기면 `DtoTimeTypeGuardTest.java:69`의 endsWith("Dto") 검사에서 빠진다. 응답 이름 통일 시 이 검사를 약화시키면 안 된다.
- 페이지 최대 크기 50/100/5000은 서로 다른 API 정책이다. 같은 이름이라는 이유로 P2 값 충돌로 보고하지 않았다.
- RouteWalkPathServiceImpl의 위경도 33~39/124~132와 다른 조회의 ±90/±180은 국내 서비스 범위와 좌표 정의역이 달라 충돌로 보지 않았다.
- 익명 세션 헤더 중복은 [MSG-469:208](../../docs/spec/MSG-469.md)에 공통 상수로 만들지 않고 소비자가 셋째로 늘면 추출한다는 결정이 있어 제외했다.

한계: 메서드 길이는 주석과 문자열을 마스킹한 중괄호 기반 탐색으로 후보를 찾고 위 4개 본문을 직접 읽었다. 줄 수는 본문 시작부터 끝이며 선언 전 주석은 제외한다. 긴 코드의 오류·성능 저하를 실측한 결과가 아니다. 소스 경로는 `src/main/java/com/msg/fillmap/` 기준이다.


### 3.8 iface: 인터페이스 분리


요약: 인벤토리의 Service 인터페이스 52는 이름으로 센 파일 수다. 선언을 확인하면 **인터페이스 42개, 구체 Service 클래스 10개**이고, 42개 인터페이스 모두 구현체가 1개다. 계약/다른 도메인 사용과 테스트 목을 함께 보면 42개를 일괄 제거할 근거는 없다.

| 파일:줄 | 관찰 사실 | 근거 문서 | 판단 | 우선순위 |
|---|---|---|---|---|
| [route/service/RouteRecommendService.java:11](../../src/main/java/com/msg/fillmap/route/service/RouteRecommendService.java#L11); [route/controller/RouteController.java:38](../../src/main/java/com/msg/fillmap/route/controller/RouteController.java#L38) | 프로덕션 사용자는 같은 route의 컨트롤러뿐. 테스트는 인터페이스 타입으로 실제 구현체를 생성하고 인터페이스 목/페이크는 검색에서 발견되지 않음 | [MSG-457](../../docs/spec/MSG-457.md), [MSG-487:222](../../docs/spec/MSG-487.md) | 형식적 분리 검토 후보. 인터페이스 자체가 알고리즘 대체에 쓰인 증거는 없음 | P3 |
| [route/service/RouteWalkPathService.java:11](../../src/main/java/com/msg/fillmap/route/service/RouteWalkPathService.java#L11); [route/controller/RouteController.java:39](../../src/main/java/com/msg/fillmap/route/controller/RouteController.java#L39) | 프로덕션 사용자는 같은 route 컨트롤러뿐. RouteWalkPathServiceTest는 실제 구현체를 생성하고 외부 클라이언트를 대체 | [MSG-483:146](../../docs/spec/MSG-483.md) | 형식적 분리 검토 후보. 조건부 빈은 TmapWalkClient이며 이 인터페이스의 다중 구현 사유는 아님 | P3 |
| [event/service/EventViewerService.java:7](../../src/main/java/com/msg/fillmap/event/service/EventViewerService.java#L7); [event/controller/EventViewerController.java:39](../../src/main/java/com/msg/fillmap/event/controller/EventViewerController.java#L39) | 사용자는 event 컨트롤러뿐. 인터페이스 타입의 테스트 목/페이크 사용은 발견되지 않음 | [MSG-443:134](../../docs/spec/MSG-443.md)은 event 내부 인터페이스라고 명시 | 형식적 분리 검토 후보. 구현체 테스트 존재 여부와 인터페이스의 필요성은 별개 | P3 |
| [event/service/EventNotificationService.java:11](../../src/main/java/com/msg/fillmap/event/service/EventNotificationService.java#L11) | event 컨트롤러와 EventQueryServiceImpl이 소비. 테스트는 실제 구현체 생성 또는 @Autowired이며 인터페이스 목/페이크는 발견되지 않음 | [MSG-442](../../docs/spec/MSG-442.md); 전용 제거 근거 문서 없음 | 같은 event 도메인 내부의 형식적 분리 검토 후보 | P3 |
| [event/submission/service/EventSubmissionService.java:16](../../src/main/java/com/msg/fillmap/event/submission/service/EventSubmissionService.java#L16) | 프로덕션 사용자는 신청 컨트롤러. CommitBoundaryTest는 실제 빈을 주입받으며 목/페이크 사용은 발견되지 않음 | [MSG-498:711](../../docs/spec/MSG-498.md) | 형식적 분리 검토 후보. 트랜잭션 프록시 유지가 검증 조건 | P3 |

유지 사유 또는 검토 제외:
- GridQueryService, UserGridQueryService, ZoneNameQueryService, HotZoneService, RegionQueryService 등은 계약 또는 도메인 간 소비가 있다. [project-conventions:162](../../.claude/rules/project-conventions.md), [MSG-388:126](../../docs/spec/MSG-388.md), [MSG-341 PRD:137](../../docs/prd/MSG-341-prd.md) 근거로 단일 구현만으로 제거 대상으로 삼지 않는다.
- VideoService, VideoModerationService, FriendshipQueryService, StreakCommandService, PushTokenService, NotificationCommandService, ZoneQueryService, MissionQueryService, MissionRegistrationService, MissionAwardService, MissionVideoService, PlaceSearchService, RegionStatsCommandService, HotScoreCommandService, EventQueryService, BadgeAwardService는 다른 도메인 소비가 있어 이번 형식적 분리 목록에서 보수적으로 제외했다. Owner가 같다고 모두 공식 A/B 계약이라는 뜻은 아니다.
- HighlightPreviewService, RegionExploreService, FriendService, NotificationInboxService, NotificationPreferenceService, UserService, TrendingKeywordQueryService, SearchKeywordCommandService, RegionStatsQueryService, EventVideoInteractionService, EventVideoService, BadgeFeaturedService, BadgeQueryService, ReportService, AdminReportService, VideoEncodingService는 인터페이스 타입 목이 있다. 이는 유지 사유가 될 수 있으며 인터페이스가 없으면 테스트할 수 없다는 뜻은 아니다.
- 목 근거 예: `VideoControllerTest.java:55`(HighlightPreview), `RegionExploreControllerTest.java:57`, `FriendControllerTest.java:67`, `NotificationInboxControllerTest.java:55`, `NotificationConsumerTest.java:95`(Preference), `EncodingJobPollerTest.java:49`. 해당 파일은 src/test에서 파일명으로 재현 검색 가능하다.
- 구체 클래스 10개: AuthService, OidcLoginService, PasswordService, RefreshTokenService, OrgAccountIssueService, OrgAccountService, OrgAccountRequestService, AdminEmailChangeRequestService, AdminEventSubmissionService, AdminApprovedEventService. 혼재 자체는 결함으로 세지 않았다.

규칙 차단: [project-conventions:43](../../.claude/rules/project-conventions.md)은 XxxService/XxxServiceImpl 명명을 정한다. 후보를 제거하려면 컨벤션 적용 범위를 먼저 합의해야 한다. 보고서는 즉시 삭제 지시가 아닌 결정 재료다.

한계: Java 타입 이름 사용을 전체 프로덕션/테스트에 검색하고 후보의 실제 주입·생성 사용을 읽었다. 주석과 import만 있는 사용은 소비자로 세지 않았다. 리플렉션/외부 모듈 소비는 실행 확인하지 않았으며, 테스트는 실행하지 않았다. inventory.sh의 중도 종료로 누락된 후반을 별도 재계수했다.


## 4. 팀 설명 자료: 왜 이렇게 했나

| 핵심 흐름 | 진입점 | 결정 문서 | 팀이 설명할 내용 |
|---|---|---|---|
| 영상 저장 | VideoServiceImpl.saveVideo / confirmAtGrid | [MSG-247](../spec/MSG-247.md), [MSG-239](../spec/MSG-239.md), [MSG-494](../spec/MSG-494.md) | pending 키 클레임 후 S3 복사, rollback 보상. 점령·뱃지 응답은 동기이고 인코딩 작업은 DB에 저장 후 별도 처리 |
| 격자 뷰포트 조회 | GridQueryServiceImpl / GridRepository | [MSG-356](../spec/MSG-356.md), [MSG-347](../spec/MSG-347.md), [행정동 라벨 ADR](../../../LLM-WIKI/04-decisions/ADR%20격자%20행정동%20라벨%20grids.region_code.md) | 5179 격자 인덱스 범위로 조회하고 저장된 행정동 라벨을 사용. 단순 뷰포트와 줌아웃 집계를 구분 |
| 이벤트 영상 목록·집계 | EventVideoServiceImpl | [MSG-440](../spec/MSG-440.md), [MSG-441](../spec/MSG-441.md) | 공개·준비완료 영상 필터와 커서 조건, 영상 ID 묶음으로 댓글·도움돼요 2회 집계 |
| 뱃지 지급 | BadgeAwardServiceImpl | [MSG-239](../spec/MSG-239.md), [MSG-363](../spec/MSG-363.md), [MSG-179](../spec/MSG-179.md) | 업로드 응답에 획득 뱃지 동봉, 중복 지급 방지, 알림은 같은 DB 작업에 outbox[^4] 기록 |

위 네 흐름에는 근거 문서가 있다. 별도 근거를 못 찾은 것은 OSIV 활성 유지, OIDC 키 조회까지 트랜잭션으로 묶은 이유, View/Projection 전역 명명 기준이다. 네이티브 개별 선택 이유가 없는 후보도 있어 단순히 기존 ADR의 '전부 PostgreSQL 전용' 문구로 설명하지 않는다.

영속 계층의 [JPA 유지 ADR](../../../LLM-WIKI/04-decisions/ADR%20영속%20계층%20JPA%20유지%20MyBatis%20반려.md)은 2026-08-03 당시 결정이다. 현재 코드 사실은 108문장 분류가 기준이며, 신규 JPQL 우선·연관관계 허용은 2026-08-19 개정 컨벤션을 따른다.

## 5. 이전 감사 대비

해당 없음. `docs/audit/`에 이전 보고서가 없었다. 멘토링 당시 구두 지적과 이번 결과를 전후 결함 건수로 비교하지 않는다.

## 6. 후속 후보 (티켓 아님, 사람이 결정)

- 영상·프로필·행사 이미지 확정에서 SQL 구간과 S3 소요, DB 연결 점유를 나눠 측정한다. 비동기 전환 여부는 기존 rollback 보상과 응답 계약을 함께 검토한다.
- 도감·격자·이벤트 피드를 실제 규모의 로컬 데이터로 재측정한다. 필요하면 별도 승인 범위에서 dev 플랜을 확인한다. 이번 감사는 dev에 접속하지 않았다.
- OSIV=false 상태에서 이벤트 목록·상세·댓글·신청·관리자 승인 HTTP 요청과 직렬화를 테스트한다. 테스트 자체의 트랜잭션으로 실패가 가려지지 않게 한다.
- 네이티브 40개 검토군 중 현 매핑으로 가능한 단순 COUNT/단건 조회부터 비용을 비교한다. 매핑 추가가 필요한 최소 5개와 잠금·벌크 쓰기는 분리해 판단한다.
- 영상 복사 응답 유실 시 rollback 보상 등록 여부를 행사 이미지 경로와 비교한다. 데이터 유실 방지와 고아 정리의 범위를 구분한다.
- 로컬 DB 시간대 설정과 MSG-379 서버 반영 기록을 확인한다. 전체 저장값을 일괄 9시간 보정할 근거는 없다.
- View/Projection 역할을 규칙에 명시하고 형식적 인터페이스 후보 5개를 팀 코드 리뷰에서 설명한다. 메서드 길이나 구현체 수만으로 삭제·분리하지 않는다.

## 7. 한계

- 전수 검색과 대표 경로 실행을 결합한 감사다. 601개 Java 파일의 모든 실행 경로를 테스트한 것이 아니다. 서비스 그래프는 final Service 타입 주입 58간선이며 일반 Component·Client·동적 조회 전체를 포함하지 않는다.
- S3·AI·Kafka·메일의 실제 외부 왕복 및 트랜잭션 연결 점유 시간은 미실측이다. 긴 처리 전체가 DB 연결을 점유한다거나 풀 고갈이 발생했다고 단정하지 않는다. afterCommit[^5]도 자동 비동기 실행이나 연결 반환 완료를 뜻하지 않는다.
- EXPLAIN 시 테스트 데이터가 롤백된 상태였다. pg_stat 추정 행 수는 regions 3,558, grids 1, videos/user_grids/event_videos/helpfuls 0. 모든 SELECT 결과 rows=0이므로 대규모 성능과 인덱스 유효성의 근거로 쓸 수 없다.
- 테스트 1차: 격자 집계·이벤트 반응 저장소·도감 저장소 3클래스 성공, 정확한 사례 수는 후속 실행이 XML을 덮어 보존하지 못했다. 2차 37건, 3차(OSIV=false) 40건은 통과했다. 77회에는 이벤트 피드 23건의 재실행이 포함되며 고유 사례는 54건이다. 실행 명령·집계는 `/tmp/backend-audit/test-summary.txt`에 남겼다. 전체 테스트·전체 빌드는 실행하지 않았다.
- 스킬 inventory.sh는 구현체 검색 중 매치 없음으로 exit 1 중단됐다. 주석까지 센 @Query 162→실제161, @Transactional155→실제143(readOnly50), Service52→인터페이스42+구체10으로 보정했다. timestamptz3은 전부 SQL 주석이며 실제 업무 컬럼은0. JVM 설정 검색은 build.gradle을 빠뜨려 UTC 설정을 놓쳤다. 스킬 파일은 이번 범위에서 수정하지 않았다.
- capture-sql.sh는 내부 테스트 성공 뒤 요약 sort/head 파이프의 SIGPIPE로 exit141을 반환했다. Gradle의 성공과 구분했고 DB log_min_duration_statement=-1 복구를 확인했다. 기존 smoke/tx 로그는 이번 실측에서 제외했다.
- 레포 산출물은 이 보고서 하나다. 코드·설정·마이그레이션을 수정하지 않았고 티켓·커밋을 만들지 않았다. 임시 캡처는 `/tmp/backend-audit/`에 있다.

[^1]: 실행 계획은 DB가 어떤 순서로 테이블을 읽고 결합하는지 보여준다. EXPLAIN ANALYZE는 쿼리를 실제 실행하므로 여기서는 로컬 SELECT만 대상으로 삼았다.
[^2]: Seq Scan은 테이블을 순차로 읽는 방식이다. 작은 테이블에서는 인덱스보다 유리할 수 있어 이것만으로 잘못된 쿼리라고 판단하지 않는다.
[^3]: 시간대 없는 timestamp는 값에 UTC·KST 구분이 없다. 이 프로젝트는 값 자체를 UTC로 저장하고 API에서 Z를 붙이는 계약을 사용한다.
[^4]: outbox는 업무 데이터와 같은 DB 트랜잭션에 발송할 알림을 남기는 방식이다. 외부 발송은 별도로 재시도하며 업무 커밋과 메시지 전달 사이의 유실을 막는 근거다.
[^5]: afterCommit은 DB 커밋 뒤 실행하는 콜백이다. 별도 executor로 넘기지 않으면 같은 스레드에서 실행되므로 응답 지연은 남을 수 있다.
