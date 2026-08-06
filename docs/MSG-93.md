# MSG-93: 위치 검색 API (Geocoding)

**Owner**: A (`com.msg.fillmap.region.*`) — 순수 Owner A(KangJeong). 단, `RegionQueryService`를 신설하고
Owner B(MSG-66 업로드 라벨러)가 소비하므로 §계약 변경은 Owner B 확인 필수.

> **선행: MSG-154 (구현 진행 중)** — `region` 패키지(entity·repository·seeder)와 `regions` 시딩 데이터가 develop에
> merge된 **뒤에야 착수 가능**하다. 이 워크트리(develop 기준)에는 아직 `region` 패키지가 없다.
> MSG-154가 만드는 `Region`·`RegionRepository`·시더를 **전제로** 쓰고, 그것들과 **중복될 코드(엔티티·리포지토리·시더)를
> 다시 만들지 않는다**. 본 티켓은 그 위에 **읽기(공간 조회) 경로 + 서비스 + 컨트롤러**만 얹는다.
>
> Jira MSG-93 설명·코멘트가 비어 있어, 제목("위치 검색 API (Geocoding)")·PO 구두 설명·IA·기존 ADR·MSG-154/66 실코드에서
> 도출했다. 제목(geocoding=정방향)과 구두 설명(좌표→행정동=역방향)이 갈려 **검색 방향은 미확정**이다(§Open Q1, 권고 있음).
>
> 형제/전후 경계: MSG-154(시딩·선행) / MSG-66(업로드 시 `region_code` 라벨링 — D4로 **NULL 유예**, 미구현 백로그) /
> MSG-155·156(수집률·`GET /api/regions/stats`·정방향 검색 — 스코프 밖) / [스파이크] 폴리곤 vs 컬럼레이블 성능비교(§D2로 분리).

---

## 개요

지도 위 한 좌표가 **어느 행정동인지** 판별하는 역지오코딩(reverse geocoding) 조회 API를 제공한다.
MSG-154가 `regions.boundary_geom`(MULTIPOLYGON)에 전국 행정동 경계를 시딩해 두면, 본 티켓은 그 경계에 대해
**단건 point-in-polygon(GIST 인덱스)** 을 쳐서 포함 행정동(`region_code`·`region_name`)을 돌려준다.

```
[MSG-154] regions.boundary_geom 시딩 (선행)
[MSG-93]  (lat, lon) ──ST_Covers(GIST)──▶ 포함 행정동 1건 { region_code, region_name, parent_code }
          없으면 → 결과 없음(바다/국외). 서비스 범위 밖 좌표 → 400
```

이 단건 조회는 두 곳에서 재사용된다: (1) 본 티켓의 검색 API, (2) MSG-66이 **NULL로 유예한** 업로드 시
`videos.region_code` 라벨링(Owner B 백로그). 그래서 조회 로직을 `RegionQueryService.resolveByPoint(...)`
계약으로 노출한다(§계약 변경) — 한 쿼리, 두 소비처.

---

## 배경 · 목표

- **사용자/제품 관점**: "이 지점이 성수동인지 서초동인지"를 서버가 우리 **자체 행정동 마스터(`regions`)** 기준으로
  답할 수 있어야 한다. 클라이언트가 카카오 지도의 주소를 쓰더라도, **우리 `region_code` 체계**(수집률 "강남구 25%",
  영상 행정동 라벨)와 정합하려면 우리 경계로 판정하는 단일 원천이 필요하다.
- **목표**:
  1. `regions.boundary_geom` 대상 **역지오코딩 조회**를 제공(좌표 → 포함 행정동 1건).
  2. 그 조회를 `RegionQueryService` 계약으로 노출해, MSG-66이 유예한 업로드 라벨러(Owner B)가 재사용하도록 한다.
  3. 이 조회가 기존 ADR("실시간/조회 경로 geospatial 금지")과 **어떻게 정합하는지 못박는다**(§D1).
  4. 멘토가 요구한 "폴리곤 geospatial vs 컬럼 레이블" 성능 비교의 **범위를 확정**한다(§D2).

---

## 선행 상태 (MSG-154 전제 + V1__init.sql 실스키마)

MSG-93은 아래를 **주어진 것으로 전제**한다. 본 티켓에서 만들지 않는다.

| 사실 | 근거 | MSG-93에서 |
|---|---|---|
| `regions` 테이블·`idx_regions_boundary` GIST 존재 | `V1__init.sql` L53–61 (이 워크트리에도 존재) | 마이그레이션·스키마 변경 없음 |
| `boundary_geom GEOGRAPHY(MULTIPOLYGON, 4326) NOT NULL` | 위 스키마 | 역지오코딩 대상. **GEOGRAPHY**이므로 인덱스 사용 연산은 `ST_Covers`/`ST_Intersects`(§도메인 로직) |
| `Region` 엔티티·`RegionRepository`·시더 | **MSG-154 산출물**(merge 대기) | 재생성 금지. 리포지토리에 **native 조회 메서드만 추가** |
| 시딩된 전국 행정동 ≈ 3,558 row | MSG-154 성공 기준 1 | 조회 대상 데이터. 없으면 API가 빈 결과만 반환 |
| `videos.region_code` **nullable, 현재 전부 NULL** | MSG-66 §D4·`Video.java` L41 (`region_code` 별도 판정 티켓 전까지 null) | 업로드 라벨러(라벨 쓰기)는 **여전히 미구현 백로그**. 본 티켓은 read만; 라벨러 배선은 MSG-66/Owner B |
| `GridEncoder`/`GridConstants` | `com.msg.fillmap.grid` | 격자 열거 방식(성능비교 대안 arm)의 재료 — 본 티켓 아님(§D2) |

> ⚠️ ADR 정합의 실상: 기존 ADR은 "업로드 시 1회 판정해 `videos.region_code` 저장"을 전제하지만, **그 라벨러는
> MSG-66 D4에서 유예돼 아직 없다**(컬럼만 nullable로 존재). 즉 "사전 레이블링" 축은 스키마만 있고 구현이 비어 있다 —
> 이 사실이 §D1(예외 허용)·§D2(성능비교 범위)의 판단 근거다.

---

## 성공 기준 (관찰 가능)

1. `GET /api/regions/reverse-geocode?lat=..&lng=..` 가 그 좌표를 포함하는 행정동 1건
   (`regionCode`, `regionName`, `parentCode`)을 `SuccessResponse`(200)로 반환한다.
2. 강남역 좌표 등 **알려진 지점**이 그 지점의 실제 행정동으로 매핑된다(시드 데이터 기준 sanity).
3. **어느 행정동에도 속하지 않는 좌표**(바다·국외 등, 단 서비스 좌표 범위 내)는 **200 + `region: null`**
   로 응답한다(§D3 — 예외 아님). *(200+null vs 404는 Open Q2로 최종 확정)*
4. **서비스 좌표 범위 밖**(lat/lon 무효 또는 한국 밖) → `RegionErrorCode.INVALID_COORDINATE`(6400)로 400.
5. 조회는 **단건 point-in-polygon**이며 `idx_regions_boundary`(GIST)를 사용한다(EXPLAIN에 인덱스 스캔 — §D2 스파이크에서 계량).
6. `RegionQueryService.resolveByPoint(lat, lon)` 계약이 노출되고, region 컨트롤러가 이를 소비한다. 시그니처는 §계약 변경대로.
7. `region` 패키지의 **entity·repository·seeder를 새로 만들지 않는다**(MSG-154 것을 사용; 리포지토리에 조회 메서드만 추가).
   스키마 변경 없음(`V1__init.sql` 그대로). `videos`·`user`·`grid` 런타임 코드 미변경. 전체 테스트 green.

---

## 스코프

**하는 것**
- `RegionRepository`(MSG-154)에 native **역지오코딩 조회** 메서드 추가(`ST_Covers`, GIST, `LIMIT 1`).
- `RegionQueryService`(+`Impl`) 신설 — `resolveByPoint(lat, lon) → Optional<RegionView>` + 좌표 plausibility 검증.
- `RegionController` — `GET /api/regions/reverse-geocode`.
- `region/dto/RegionResponseDto`, 서비스 내부 뷰(`RegionView`).
- `region/exception/RegionErrorCode`(6xxx 대역) — `INVALID_COORDINATE(6400)`. **6404는 MSG-156 예약이라 쓰지 않음**.
- 테스트: 조회·검증·no-match·컨트롤러(모듈 단위).

**스코프 밖**

| 항목 | 소관 |
|---|---|
| `Region` 엔티티·`RegionRepository`·시더·시딩 데이터 | **MSG-154**(선행). 본 티켓은 read만 |
| 업로드 시 `videos.region_code` 라벨링(쓰기 배선) | **MSG-66 백로그 / Owner B**. 본 티켓은 `resolveByPoint` 계약만 제공 |
| 수집률·`GET /api/regions/stats`·`region_stats` 집계 | **MSG-155 / 156** |
| 정방향 검색(행정동 이름 → 위치/경계) | **미확정(Open Q1)**. 채택 시 별도 엔드포인트 |
| **폴리곤 geospatial vs 컬럼 레이블 성능 비교(포트폴리오)** | **§D2로 별도 스파이크 티켓 분리** — 본 티켓은 폴리곤 arm(동작 baseline)만 |
| Redis 캐시 | 없음(저빈도·사용자 트리거라 MVP 캐시 불요. 필요 시 후속) |

---

## 결정 (Decisions)

> D-쟁점 매핑: **D1 = D-쟁점 2(ADR 정합)** · **D2 = D-쟁점 3(성능비교 범위)** · **D3 = 부수 결정(에러/no-match)**.
> **D-쟁점 1(검색 방향)은 미확정** → Open Q1(권고: 역방향).

### D1 — 조회 경로 단건 ST_Covers는 ADR **예외로 허용** (D-쟁점 2)

- **결정**: 역지오코딩 조회는 요청당 **단일 point-in-polygon 1회**(`ST_Covers`, GIST `idx_regions_boundary`)를
  실행하며, **기존 ADR("실시간/조회 경로 geospatial 금지")을 위반하지 않는다**. 예외로 허용한다.
- **근거**:
  1. **ADR이 겨눈 대상이 다르다.** ADR은 *고빈도 핫패스에서 루프 안 geospatial*(뷰포트 렌더링의 셀×폴리곤,
     영상마다 반복 라벨링)을 금지한 것이다(멘토 피드백·MSG-154 §멘토 정합). 본 조회는 **사용자가 명시적으로 트리거하는
     저빈도 단건**이고, 입력이 **좌표 1점**이라 루프가 없다.
  2. **사전계산이 불가능한 입력**이다. 역지오코딩의 입력은 **임의의 좌표**라 컬럼으로 미리 라벨해 둘 수 없다
     (라벨은 *저장된 엔티티*에만 가능). MSG-154는 region↔grid 매핑 테이블도 명시적으로 반려했다. 따라서 사전 레이블로
     대체할 방법 자체가 없다 — 조회 시 판정이 유일한 수단.
  3. **인덱스가 존재**한다. `idx_regions_boundary`(GIST) + GEOGRAPHY `ST_Covers`는 단건 point-in-polygon을
     인덱스로 처리한다(수천 폴리곤에서 sub-ms~저-ms). 뷰포트급 트래픽이 아니다.
- **한계·튜닝 노브**: 만약 이 조회가 예상 밖 고빈도가 되면(예: 실시간 지도 이동마다 호출) → 클라 디바운스 또는
  좌표 스냅 캐시로 흡수. MVP는 캐시 없이 착수. `ponytail: 단건 GIST 조회, 고빈도화되면 좌표 스냅 캐시 추가.`

### D2 — 성능 비교(폴리곤 vs 컬럼 레이블)는 **별도 스파이크 티켓으로 분리** (D-쟁점 3)

- **결정**: 멘토가 요구한 "폴리곤 geospatial 처리 vs 쿼리 컬럼 레이블 처리 성능 비교"는 **MSG-93 범위에서 빼고
  별도 스파이크 티켓**(예: `[스파이크] region 판정: 폴리곤 ST_Covers vs region_code 컬럼 레이블 성능비교`)으로 만든다.
  MSG-93은 **폴리곤 arm(동작하는 역지오코딩 baseline)** 만 구현한다.
- **근거**:
  1. **선례 정합**: 부하/임계점 비교는 이미 **스파이크 티켓 패턴**으로 처리했다(MSG-134, k6). 성능비교는 사용자
     기능이 아니라 **연구·포트폴리오 산출물**이라 배포 티켓과 성격이 다르다.
  2. **양쪽 arm이 아직 다 없다.** 공정한 비교는 (A) 폴리곤 조회(본 티켓) **와** (B) 컬럼 레이블 읽기 둘 다 필요한데,
     (B)의 라벨 **쓰기**(업로드 시 `videos.region_code` 채우기)는 MSG-66 D4로 **유예된 미구현 백로그**다. 지금 93 안에
     비교를 넣으면 남의 도메인(Owner B) 라벨러까지 끌고 와야 해 스코프가 터진다.
  3. **데이터 의존**: 의미 있는 벤치마크는 MSG-154의 **전국 시드**가 있어야 한다. 93 착수 시점(154 merge 직후)에
     seed·라벨 데이터가 갓 들어와 즉시 정밀 벤치는 이르다.
- **스파이크가 받게 될 것**: (A) arm = 본 티켓 `ST_Covers` 조회, (B) arm = MSG-66 라벨러가 채운 `videos.region_code`
  단순 읽기. PostGIS 폴리곤 제공 방식 조사(아래) + k6 부하 + EXPLAIN을 스파이크에서 계량. MSG-134를 방법론 템플릿으로.
- **PostGIS 폴리곤 방식 조사(스파이크 인계 메모)**: GEOGRAPHY + GIST에서 point-in-polygon은 `ST_Covers`/`ST_Intersects`가
  인덱스를 탄다. `ST_Contains`는 GEOMETRY 전용이라 `boundary_geom::geometry` 캐스트가 필요하고 **캐스트가 GIST를 우회**
  하므로 표현식 인덱스 없이는 느리다 → 서비스 매칭: **역지오코딩 = GEOGRAPHY `ST_Covers` 단건**이 정석.

### D3 — no-match·좌표검증 처리 + 에러코드 6xxx (부수)

- **no-match(포함 행정동 없음)** = **예외 아님**. `resolveByPoint`는 `Optional.empty()` 반환, 컨트롤러는 **200 + `region: null`**.
  근거: "이 점이 어느 동?"의 정당한 답이 "없음(바다·국외)"일 수 있고, 업로드 라벨러도 예외 대신 null 저장을 원한다
  (MSG-66 nullable). *(최종 200+null vs 404 → Open Q2)*
- **좌표 plausibility**는 서비스가 검증(MSG-66 D7과 동일 정책): lat/lon 유효 범위 + 서비스 범위(한국 대략 lat 33~39,
  lon 124~132) 밖이면 `RegionErrorCode.INVALID_COORDINATE(6400)` → 400. 싸고 확실 → no-match(200)와 구분.
- **에러코드**: region 도메인 신규 `RegionErrorCode`(6xxx). 본 티켓은 `INVALID_COORDINATE(6400)`만.
  **`6404`는 MSG-156 `REGION_NOT_FOUND` 예약이므로 쓰지 않는다.**

---

## API 명세

인증: 지도 화면 뒤 조회이므로 **로그인 필요**(`@AuthenticationPrincipal`, 기존 방식). 사용자별 데이터는 아님(좌표→행정동).

### `GET /api/regions/reverse-geocode` (역지오코딩 — 권고 방향, Open Q1)

| 파라미터 | 타입 | 필수 | 의미 |
|---|---|---|---|
| `lat` | double | ✅ | 위도 |
| `lon` | double | ✅ | 경도 |

- 성공 200 `body` = `RegionResponseDto`(포함 행정동 없으면 `null`):

| 필드 | 타입 | 의미 |
|---|---|---|
| `regionCode` | String | `regions.region_code`(=adm_cd2) |
| `regionName` | String | `regions.region_name`(=adm_nm) |
| `parentCode` | String \| null | `regions.parent_code`(시군구) |

> `boundary_geom`(경계 폴리곤)은 응답에 넣지 않는다 — 무겁고 검색 UX에 불필요(YAGNI). 경계가 필요한 화면이
> 생기면 그때 GeoJSON opt-in 파라미터 추가.

**요청/응답 예시**
```
GET /api/regions/reverse-geocode?lat=37.4979&lng=127.0276
```
```json
{ "developCode": 200, "httpStatus": "OK", "message": "성공",
  "body": { "regionCode": "1168051500", "regionName": "서울특별시 강남구 역삼1동", "parentCode": "11680" } }
```
**포함 행정동 없음**: `"body": null` (200).

**에러**

| 조건 | 코드 |
|---|---|
| `lat`/`lon` 누락 | `INVALID_COORDINATE`(6400) — 검증 실패 |
| lat/lon 유효범위·서비스범위 밖 | `INVALID_COORDINATE`(6400, 400) |

> 정방향 검색(행정동 이름 → 위치)을 채택할 경우(Open Q1-b/c) 별도 엔드포인트
> `GET /api/regions/search?query=성수동`(name LIKE → 후보 리스트 + 대표점/`bbox`)을 추가한다. 이 경우 "이름 없음"은
> 빈 리스트(200)로, MSG-156 `REGION_NOT_FOUND(6404)`는 *코드 지정 조회*에서만 쓴다. 본 명세는 역방향 기준으로 작성.

---

## 도메인 로직

### 역지오코딩 조회 (`RegionRepository` native + `RegionQueryServiceImpl`)

1. **좌표 검증**(서비스): lat/lon null·유효범위·서비스범위(한국) 밖 → `INVALID_COORDINATE`(6400). (MSG-66 D7 정책 재사용)
2. **native 조회**: 포함 행정동 1건.
   ```sql
   SELECT region_code, region_name, parent_code
   FROM regions
   WHERE ST_Covers(boundary_geom, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography)
   LIMIT 1
   ```
   - **GEOGRAPHY이므로 `ST_Covers`**(GIST `idx_regions_boundary` 사용). `ST_Contains(boundary_geom::geometry, ...)`는
     캐스트로 인덱스를 우회하므로 쓰지 않는다(§D2 조사 메모).
   - 좌표 순서 **`ST_MakePoint(lon, lat)`**(PostGIS X=경도) — `video.geom` 저장 시 `(lon, lat)` 관례와 동일.
   - `LIMIT 1`: 경계선에 정확히 걸린 극소수 케이스의 다중 매칭을 단일화(행정동 경계는 상호 배타라 실질적으로 1건).
3. 결과 있으면 `RegionView`로, 없으면 `Optional.empty()`(no-match, 예외 아님 — §D3).

### 계약 노출 (`RegionQueryService`)

- `Optional<RegionView> resolveByPoint(double lat, double lon)` — 컨트롤러와 **미래 MSG-66 업로드 라벨러**가 공유.
- 라벨러는 이 결과의 `regionCode`를 `videos.region_code`에 저장(현재 NULL 유예분을 채움) — **본 티켓은 그 배선을 하지 않고
  계약만 제공**(Owner B가 소비 시점 결정).

### 멘토 피드백 정합

- 본 조회는 **저빈도·단건·사용자 트리거**의 예외적 조회 geospatial(§D1). 고빈도 루프 geospatial은 여전히 금지.
- 폴리곤 vs 컬럼레이블 정량 비교는 스파이크로 분리(§D2), PostGIS 폴리곤 방식(GEOGRAPHY GIST `ST_Covers`)을 서비스에 매칭 완료.

---

## 데이터 모델

**Flyway 마이그레이션 불필요.** `V1__init.sql`의 `regions`·`idx_regions_boundary`(GIST)가 이미 조회에 필요한 전부를 보유.

- 신규 컬럼/인덱스/엔티티 **없음**. `Region` 엔티티·`RegionRepository`는 **MSG-154 것을 사용**(재정의 금지).
- MSG-93은 `RegionRepository`에 **native 조회 메서드 1개**(`findContainingRegion` 성격, `@Query(nativeQuery=true)`)만 추가.
  MSG-154 엔티티가 `boundary_geom`을 미매핑해도 무방(조회는 projection 컬럼만 select, geometry는 WHERE에서만 사용).

---

## 계약 변경

**Owner B 확인 필요.** `RegionQueryService`는 신규 계약 인터페이스(Owner A 제공 → region 컨트롤러 + Owner B 업로드 라벨러 소비).

### `RegionQueryService` 신설 (Owner A)

```
// region/service/RegionQueryService
Optional<RegionView> resolveByPoint(double lat, double lon);
```
- `RegionView`: 서비스 간 내부 뷰 record `{ String regionCode, String regionName, String parentCode }`.
  HTTP DTO(`RegionResponseDto`) 변환은 컨트롤러 책임(MSG-90/73 패턴 일치).
- **소비처**: (1) `RegionController`(본 티켓), (2) MSG-66 업로드 라벨러(Owner B, 소비 시점 미정 — 계약만 제공).
- **조율 필요**: MSG-154는 "Region 소비 계약(RegionQueryService)은 API 티켓(MSG-155/156)에서 신설" 이라고 적었다.
  MSG-155/156이 stats 조회용으로 같은 인터페이스를 손대면 **인터페이스가 둘로 갈릴 위험** → 하나로 합칠지 리뷰에서 조율(Open Q3).

`GridQueryService`·`HotZoneService`·`UserGridQueryService`·`UserOidcCommandService` 시그니처는 **전부 불변**.

### `RegionErrorCode` 신규 (6xxx 대역)

| 상수 | code | HttpStatus | message |
|---|---|---|---|
| `INVALID_COORDINATE` | 6400 | BAD_REQUEST | 서비스 지역 범위를 벗어난 좌표입니다 |

- `ApiException(RegionErrorCode.XXX)`로만 던진다(response-pattern.md). 컨트롤러/서비스에서 응답 직접 조립 금지.
- **`6404`는 MSG-156 `REGION_NOT_FOUND` 예약** — 본 티켓에서 정의·사용하지 않는다.
- `INVALID_COORDINATE`의 semantic은 video의 `3400`과 동일하나, **도메인별 대역 원칙**상 region은 자체 6400을 둔다(코드 공유 안 함).

---

## 테스트 시나리오 (JUnit5 + AssertJ · 한국어 백틱 메서드명 · 모듈 단위)

> **테스트 격리(MEMORY 'shared local DB' · MSG-154 §격리 원칙)**: 조회 테스트는 **합성 region fixture**
> (예: `region_code = "TESTDONG01"` + 알려진 소형 폴리곤) + `@Transactional` 롤백만 사용한다. `regions`를
> **truncate하지 않는다**(`videos.region_code`·`region_stats.region_code` FK 참조 — 전체 삭제 시 타 테스트 NonUniqueResult).
> 전국 시드 데이터에 의존하는 sanity(강남역 등)는 시드가 있는 로컬에서만 도는 조건부/수동 검증으로 둔다.

### 모듈 1 — `RegionRepository` (native 역지오코딩, DB 통합 · 합성 fixture)
- `폴리곤_내부_좌표는_그_행정동을_반환한다`
- `폴리곤_외부_좌표는_결과가_없다` (no-match)
- `GEOGRAPHY_ST_Covers는_경계선_좌표도_포함해_단일건을_반환한다` (LIMIT 1)
- `조회는_idx_regions_boundary_GIST를_사용한다` (EXPLAIN 인덱스 스캔 — 선택/스파이크 연동)

### 모듈 2 — `RegionQueryServiceImpl` (`resolveByPoint`, DB 통합)
- `유효좌표는_포함_행정동뷰를_반환한다`
- `포함_행정동이_없으면_Optional_empty를_반환한다` (예외 아님 — §D3)
- `서비스범위_밖_좌표는_INVALID_COORDINATE를_던진다` (6400)
- `위경도_순서를_lon_lat로_바인딩한다` (좌표 뒤집힘 회귀 방지)

### 모듈 3 — `RegionController` (MockMvc)
- `reverse_geocode는_200과_regionCode_regionName을_반환한다`
- `포함_행정동이_없으면_200과_null_body를_반환한다` (§D3 권고 — Open Q2 확정 시 조정)
- `서비스범위_밖_좌표는_400과_6400을_반환한다`
- `lat_또는_lon이_없으면_400이다` (검증)

### (조건부) 모듈 4 — 정방향 검색 — **Open Q1이 (b)/(c)로 확정될 때만**
- `행정동_이름으로_검색하면_후보와_대표점을_반환한다`
- `일치하는_행정동이_없으면_빈_리스트를_반환한다`

### DoD 수동 검증 (전국 시드 존재 시)
- 강남역(37.4979, 127.0276) → 강남구 소속 행정동. 시청 앞 → 중구 소속. sanity.
- 바다 좌표(예: 동해상) → `body: null`. 국외/서비스 밖 → 400·6400.

---

## 미해결 질문 (Open Questions)

1. **검색 방향 — 미확정, 권고 = (a) 역방향** (D-쟁점 1). 최종 확정은 리뷰(154에 막혀 시간 있음).
   - **(a) 좌표 → 행정동(역방향)** — PO 구두 설명 그대로. **권고.**
   - (b) 행정동 이름 → 위치/경계(정방향) — 지도 검색바 "성수동" → 이동.
   - (c) 둘 다.
   - **권고 근거**: ① PO 구두 설명이 역방향. ② IA v2 "지도 기반 격자 탐색"은 **뷰포트·bbox·필터·핫구역** 중심이고
     **장소명 검색바가 명시돼 있지 않다**(ia.md 표) — 정방향 place 검색 수요의 IA 근거가 약하다. ③ **정방향 place-name
     검색은 이미 클라이언트의 카카오 지도 SDK가 제공**(infrastructure.md 외부연동)하는 **네이티브 플랫폼 기능**이라
     백엔드가 재구현하면 중복. ④ 백엔드의 고유 가치는 **우리 `region_code` 체계로의 매핑**(역방향)이고, 이는 MSG-66이
     유예한 업로드 라벨링·수집률과 직접 이어진다. → **(a) 채택 권고, (b)는 클라 SDK에 위임.**
2. **no-match 응답 = 200+null vs 404** (§D3). 권고 **200+null**(정당한 "없음"·라벨러 재사용 친화). RESTful하게 404를
   원하면 `REGION_NOT_FOUND`가 아닌 **별도 코드**를 6400 외에 신설(6404 예약 회피). 리뷰에서 택일.
3. **`RegionQueryService` 소유·통합** (§계약 변경). 본 티켓이 `resolveByPoint`로 신설. MSG-155/156이 stats 조회로 같은
   인터페이스를 손대면 **하나로 합칠지/분리할지** 조율. 권고: 인터페이스 1개로 통합, 메서드만 티켓별 추가.
4. **인증 필요 여부**. 역지오코딩은 사용자 데이터가 아니라 **비인증 공개**로 열 수도 있다. 지도 화면이 로그인 뒤라
   MVP는 로그인 필요로 두되, 공개 전환은 리뷰에서 판단.

## 작업 로그

### 2026-07-21
- Open Q 리더 확정: Q1=(a) 역방향만 · Q2=no-match 200+null · Q3=RegionQueryService 신설 · Q4=인증 필수(SecurityConfig 무변경).
- 모듈 1~3 구현 완료 (RegionRepository.findContainingRegion ST_Covers/GIST → RegionQueryServiceImpl.resolveByPoint → RegionController `GET /api/regions/reverse-geocode`). 신규 테스트 11건, region 스코프 30/30, 풀 빌드 그린.
- MSG-154 산출물 무변경(RegionRepository는 메서드 가산만). RegionErrorCode 6400만 신설(6404는 MSG-156 예약).
- EXPLAIN GIST 스캔 테스트는 D2대로 성능 스파이크 티켓으로 위임(플래너 비결정성으로 flaky — 리뷰어 타당성 확인). 좌표 null 체크는 계약 시그니처(primitive) 우선으로 컨트롤러 담당(GridController 패턴).
- convention-reviewer 리뷰 통과 (위반 0건, 1차 통과). 선택 제안 1건 미반영: 401 미인증 컨트롤러 테스트(스펙 미요구, 152와의 패리티용) — 후속 판단.
- status.md `region` 행·계약 인터페이스 표(RegionQueryService) 갱신 완료.
- 제안 커밋: `MSG-93 feat: 위치 검색 API — 좌표→행정동 역지오코딩 (ST_Covers·RegionQueryService 계약 신설)` / `MSG-93 docs: 스펙 문서 및 status.md region 행 갱신`
