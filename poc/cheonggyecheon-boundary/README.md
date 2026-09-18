# 청계천 경계 표시 PoC

청계천의 굽은 모양을 따라 면적이 있는 경계를 지도에 표시할 수 있는지 확인합니다.
독립 실험 화면이며 제품 요구사항, Spring API, DB 스키마를 바꾸지 않습니다.
이 범위는 `.claude/CLAUDE.md`의 요구사항 불변 기준으로 PRD를 면제했습니다.

## 실행

레포 루트에서 실행한 뒤 [실험 화면](http://127.0.0.1:8765)을 엽니다.

```bash
python3 -m http.server 8765 --bind 127.0.0.1 --directory poc/cheonggyecheon-boundary
```

- `굽은 구간`을 누르면 용두동 인근 굴곡을 확대합니다.
- 편측 폭을 5~80m로 바꿉니다. 편측 20m면 전체 폭은 40m입니다.
- `FillMap 100 m 격자와 비교`로 경계가 격자에 맞춰 꺾이지 않는지 확인합니다.
- 지도 클릭 또는 `지도 중심 확인`으로 표시한 경계의 안팎을 판정합니다.
- 모바일에서는 지도가 위에, 설정이 아래에 표시됩니다.

## 데이터와 구현

| 항목 | 내용 |
|---|---|
| 원본 | OpenStreetMap의 청계천 수로 중심선 2개를 공통 끝점에서 연결한 87좌표 |
| 범위 | 청계광장부터 중랑천 합류부까지, 저장된 중심선 길이 8.259km |
| 기준 시각 | OSM 데이터 시각 2026-09-12T15:46:21Z |
| 입력 저장 | `centerline.geojson`, GeoJSON[^1] 좌표 순서 `[경도, 위도]` |
| 경계 생성 | Turf 7.3.0의 미터 단위 버퍼[^2], 곡선 근사 steps=16 |
| 지도 | Leaflet 1.9.4, OSM 표준 지도 타일. 배경 지도에는 인터넷 필요 |
| 비교 격자 | `GridConstants.java`의 EPSG:5179[^3] 정의와 100m 크기를 읽어 생성 |
| 격자 저장 | `grid.geojson`, 청계천 주변 500m까지 111개 격자선 |
| 실행 위치 | 경계 계산과 안팎 판정은 브라우저 메모리. DB·Redis·S3 쓰기 없음 |

`boundaryFor`가 만든 동일한 Polygon을 지도에 채우고 클릭 판정에도 사용합니다.
선을 굵게 그려 경계처럼 보이게 만든 것이 아니므로, 확대해도 폭의 기준은 미터입니다.
폭을 바꾸면 영역을 다시 계산하며, 선택한 지점의 안팎 판정도 갱신합니다.
비교 격자는 현재 BE 좌표계를 사용합니다. 오래된 위키의 위경도 간격식은 사용하지 않습니다.

일정한 폭의 영역은 실제 하천 부지나 양쪽 산책로 경계와 다릅니다.
원본 지도 좌표의 측량 정확도와 현장 경계 일치는 검증하지 않았습니다.
행사 영상 조회, 내부 투어, 작도 도구, 제품 API 연동과 파일 다운로드는 이번 범위에 없습니다.

## 검증

```bash
node poc/cheonggyecheon-boundary/check.mjs
```

- 87좌표의 범위·경도/위도 순서와 두 원본의 연결점 확인.
- 편측 5~80m의 16종에서 기하 유효성·자기 교차 없음·폭에 따른 면적 증가 확인.
- 중심선 전체를 25m 간격으로 확인해 경계 안에 들어가는지 검사.
- 실제 직선 구간의 수직 방향에서 지정 폭의 80% 지점은 안, 120% 지점은 밖으로 판정.
- 비유한값·문자열·허용 범위 밖의 폭 거절. 비교 격자의 좌표계가 BE 상수와 일치하는지 검사.

격자는 기존 `scripts/requirements.txt`의 pyproj 3.6.1로 재생성합니다.

```bash
python3 poc/cheonggyecheon-boundary/build-grid.py
```

재생성 시 모든 꼭짓점을 미터 좌표로 역검증합니다. 이번 실측 최대 오차는 0.000056m입니다.
이는 변환·반올림 오차이며 지도 원본의 위치 정확도를 뜻하지 않습니다.
편측 20m의 표시 면적은 33.16ha입니다.

브라우저에서 배경 지도 로딩, 굽은 구간 확대, 폭 조절, 중심선 표시 전환,
지점 안팎 판정과 모바일 390×844 배치를 확인했습니다.
초기 지도 생성 순서에서 발생한 오류는 지도 시점을 먼저 설정하도록 수정했습니다.
파일 다운로드 이벤트는 내장 브라우저에서 확인되지 않아 최종 화면에서 해당 기능을 제외했습니다.

## 출처와 재현

- [상류 OSM way 368276771](https://www.openstreetmap.org/way/368276771)
- [하류 OSM way 769631455](https://www.openstreetmap.org/way/769631455)
- [OSM 데이터 이용 조건](https://www.openstreetmap.org/copyright), © OpenStreetMap contributors, ODbL-1.0.
- [Turf buffer](https://turfjs.org/docs/api/buffer), [Leaflet 배포](https://leafletjs.com/download.html).
- `vendor/`는 Leaflet 1.9.4(JS·CSS, BSD-2-Clause), Turf 7.3.0(JS, MIT)의 고정 사본과 원문 라이선스입니다.
  `unpkg.com/leaflet@1.9.4/dist/`와 `unpkg.com/@turf/turf@7.3.0/turf.min.js`에서 받았습니다.
  원본 데이터 이용 조건은 코드 라이선스와 별도로 적용됩니다.

좌표는 `https://overpass-api.de/api/interpreter`에서 아래 쿼리로 조회했습니다.
두 way의 공통 OSM node ID를 확인한 뒤 접합점 하나만 중복 제거했습니다.
새 스냅숏으로 교체하면 `check.mjs`의 원본 고정값도 검토해야 합니다.

```text
[out:json][timeout:25];
way["waterway"]["name"="청계천"](37.55,126.96,37.59,127.06);
out geom;
```

## 작업 기록

- 브랜치: `feature/cheonggyecheon-boundary-poc`. 티켓 미지정. 커밋·푸시 없음.
- 읽기 전용 로컬 서버는 이 폴더만 `127.0.0.1:8765`에 제공합니다.
- SQL, 서버 예외 코드, 빈 생명주기와 트랜잭션 경계 변경은 없습니다.
- 좌표·라이브러리 로딩 실패는 화면 상태 문구로 알립니다. 지도 타일 실패 시 경계 조절은 유지합니다.
- 적용 스킬: frontend-design, control-in-app-browser, korean-humanizer, tech-term-footnotes.
- 사용 도구: 로컬 파일·실행 도구, Browser, 웹 조회. 외부 시스템 기록 없음.

[^1]: GeoJSON은 지도 위 점·선·면을 JSON으로 표현하는 형식입니다. 이 화면은 원본을 선으로 저장하고 표시할 때 면으로 변환합니다.
[^2]: 버퍼는 선에서 지정한 거리 안의 영역입니다. 여기서는 수로 중심선의 양쪽에 같은 폭을 줍니다.
[^3]: EPSG:5179는 한국에서 사용하는 미터 단위 평면 좌표계입니다. 비교 격자를 BE와 같은 위치에 그리기 위해 사용합니다.

## 후속 기술검증

- [경계 표시 보고서](../../docs/reports/2026-09-13-cheonggyecheon-boundary-poc.md)
- [행사·투어 추가 도입 보고서](../../docs/reports/2026-09-13-event-tour-additive-poc.md)
- `python3 poc/cheonggyecheon-boundary/spatial-check.py`: 기존 로컬 `fillmap-postgres`의 `fillmap` DB에서 합성 10만 건으로 공간 조회를 검증합니다. 앱 테이블에 접근하지 않고 TEMP 테이블과 ROLLBACK만 사용합니다. 결과는 `spatial-results.json`입니다.
- `node poc/cheonggyecheon-boundary/tour-check.mjs`: 실제 수로 중심선과 가상 출입구·관람시간으로 6개 시간/출발점 조합, 폐쇄·접근성·지점·GPS 표본을 검증합니다. 결과는 `tour-results.json`입니다. 실제 산책로 추천이나 현장 접근성 검증이 아닙니다.
- `existing-test-results.json`: 기존 행사 신청·기간 가드·경로 정렬 테스트 56건의 재실행 요약입니다.
