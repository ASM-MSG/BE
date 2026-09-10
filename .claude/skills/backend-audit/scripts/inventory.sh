#!/usr/bin/env bash
# 정적 인벤토리 — 렌즈 에이전트가 같은 grep을 8번 반복하지 않도록 한 번에 센다.
# 숫자는 "얼마나 있나"의 기준선일 뿐이다. 판단(불가피/전환 후보)은 렌즈 에이전트가 파일을 열어서 한다.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
M=src/main/java/com/msg/fillmap
Y=src/main/resources

echo "## 기준 커밋: $(git rev-parse --short HEAD) ($(git branch --show-current))"
echo
echo "## 쿼리"
echo "- @Query 전체: $(grep -rn '@Query' $M | wc -l | tr -d ' ')"
echo "- nativeQuery=true: $(grep -rn 'nativeQuery *= *true' $M | wc -l | tr -d ' ')"
echo "- 리포지토리별 native 수 (상위 15):"
grep -rl 'nativeQuery *= *true' $M | while read -r f; do echo "$(grep -c 'nativeQuery *= *true' "$f") ${f#$M/}"; done | sort -rn | head -15 | sed 's/^/  /'
echo "- PostgreSQL 전용 기능 키워드가 있는 native (불가피 후보):"
grep -rln 'nativeQuery *= *true' $M | xargs grep -lE 'ST_[A-Za-z]+|ON CONFLICT|pg_advisory|SKIP LOCKED|::geometry|::geography|jsonb|DISTINCT ON|LATERAL' | sed "s#$M/#  #"
echo
echo "## 트랜잭션"
echo "- @Transactional: $(grep -rn '@Transactional' $M | wc -l | tr -d ' ') (readOnly: $(grep -rn 'readOnly *= *true' $M | wc -l | tr -d ' '))"
echo "- afterCommit/TransactionSynchronization 사용 파일:"
grep -rl 'afterCommit\|TransactionSynchronization' $M | sed "s#$M/#  #"
echo "- 외부 I/O 클라이언트를 주입받는 서비스 (트랜잭션 안에서 부르는지 확인 대상):"
grep -rlE 'S3Client|S3Template|S3Presigner|RestClient|WebClient|KafkaTemplate|JavaMailSender|MailSender|StringRedisTemplate|RedisTemplate' $M | grep -i 'service' | sed "s#$M/#  #"
echo "- @Async: $(grep -rn '@Async' $M | wc -l | tr -d ' ')  @Scheduled: $(grep -rn '@Scheduled' $M | wc -l | tr -d ' ')  @KafkaListener: $(grep -rn '@KafkaListener' $M | wc -l | tr -d ' ')"
echo
echo "## OSIV · 시간대 · 커넥션"
if grep -rhE '^\s*open-in-view:' $Y/*.yml >/dev/null; then echo "- open-in-view: $(grep -rhE '^\s*open-in-view:' $Y/*.yml | tr -d ' ')"; else echo "- open-in-view: 활성 설정 없음 → 스프링 기본값 true(OSIV 켜짐). 주석 흔적: $(grep -rhn 'open-in-view' $Y/*.yml | tr -d ' ' || echo 없음)"; fi
echo "- hikari 설정: $(grep -rhn 'maximum-pool-size\|connection-timeout' $Y/*.yml | tr '\n' ';')"
echo "- Clock @Bean 정의: $(grep -rln 'Clock clock()' $M | sed "s#$M/##" | tr '\n' ' ')"
echo "- Clock.systemDefaultZone() 직접 사용(서버 TZ 의존 — 검토 대상): $(grep -rn 'Clock.systemDefaultZone' $M | wc -l | tr -d ' ')  Clock.systemUTC(): $(grep -rn 'Clock.systemUTC' $M | wc -l | tr -d ' ')"
echo "- 맨 LocalDateTime.now(): $(grep -rn 'LocalDateTime\.now()' $M | wc -l | tr -d ' ')  Instant.now(): $(grep -rn 'Instant\.now()' $M | wc -l | tr -d ' ')  ZonedDateTime: $(grep -rln 'ZonedDateTime' $M | wc -l | tr -d ' ')개 파일"
echo "- JVM/JDBC 타임존 설정: $(grep -rhn 'user.timezone\|jdbc.time_zone\|serverTimezone\|TimeZone.setDefault' $Y/*.yml $M 2>/dev/null | head -3 || echo '없음')"
echo "- timestamptz 컬럼: $(grep -rhoi 'timestamptz\|timestamp with time zone' src/main/resources/db/migration | wc -l | tr -d ' ')  timestamp(무존): $(grep -rhoiE 'timestamp(\(6\))?( without time zone)?[ ,]' src/main/resources/db/migration | wc -l | tr -d ' ')"
echo
echo "## 계층 · 명명"
echo "- Service 인터페이스: $(find $M -name '*Service.java' | wc -l | tr -d ' ')  ServiceImpl: $(find $M -name '*ServiceImpl.java' | wc -l | tr -d ' ')"
echo "- 인터페이스 1개당 구현체 1개인 것 (형식적 분리 후보 — 계약 인터페이스는 제외해서 볼 것):"
find $M -name '*Service.java' | while read -r i; do n=$(basename "$i" .java); c=$(grep -rl "implements .*\b$n\b" $M | wc -l | tr -d ' '); if [ "$c" = "1" ]; then echo "  $n"; fi; done
echo "- 서비스가 다른 서비스를 주입받는 곳 (호출 방향 그래프 재료):"
grep -rhoE 'private final [A-Z][A-Za-z]*Service [a-zA-Z]+;' $M --include='*ServiceImpl.java' --include='*Service.java' -r 2>/dev/null | wc -l | tr -d ' ' | sed 's/^/  주입 건수: /'
echo "- 명명: *View $(find $M -name '*View.java' | wc -l | tr -d ' ')  *Projection $(find $M -name '*Projection.java' | wc -l | tr -d ' ')  *ResponseDto $(find $M -name '*ResponseDto.java' | wc -l | tr -d ' ')  *Summary $(find $M -name '*Summary.java' | wc -l | tr -d ' ')  *Row $(find $M -name '*Row.java' | wc -l | tr -d ' ')"
echo "- 100줄 넘는 서비스 메서드는 렌즈 에이전트가 직접 센다 (grep으로 못 셈)"
