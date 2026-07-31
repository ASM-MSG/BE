# MSG-259: 검수 규칙(큐레이션→시도별 합집합→경계조정)을 초안에 적용해 시딩 포맷 후보를 뽑는다
# 출력: zones-candidate.json (ZoneSeed 포맷 — 팀 검수 입력물, 확정 시드 아님)
#       zones-candidate.geojson (geojson.io 검수용)
# 사용법: python3 build-candidate.py   →   python3 validate-zones.py zones-candidate.json
import json
import re

GRID_LAT_STEP = 0.0009    # GridConstants 공유 상수
GRID_LNG_STEP = 0.00115

# 1단계: 유명 통칭 목록 (팀 스크럼에서 확정할 제안 — 통칭: zoneKey slug)
FAMOUS = {
    # 서울
    "홍대입구": "hongdae", "강남": "gangnam", "성수": "seongsu", "건대입구": "kondae",
    "이태원": "itaewon", "명동": "myeongdong", "신촌": "sinchon", "잠실": "jamsil",
    "종각": "jonggak", "압구정": "apgujeong", "여의도": "yeouido", "망원": "mangwon",
    "왕십리": "wangsimni", "노량진": "noryangjin", "문래": "mullae", "성신여대": "sungshin",
    "혜화": "hyehwa",
    # 부산
    "서면": "seomyeon", "전포": "jeonpo", "남포": "nampo", "사직": "sajik",
    "광안리해변": "gwangalli", "해운대해변": "haeundae",
}
# 목록에 있으나 공공데이터에 없어 수동 작도가 필요했던 곳 (FR-2a) — zones-manual.json 으로 작도 완료.
# 광복만 제외: 남포 bbox(공공데이터)가 광복로 전체를 이미 포함해 별도 zone 이 겹침만 만든다 — 검수 확인 대상
MANUAL_EXCLUDED = {"광복": "남포 bbox 가 광복로 전체 포함 — 별도 zone 불필요 제안"}

# 4단계: 경계 겹침 분리 — 침범한 쪽의 변 하나를 물린다 (지도 대조로 정한 값, 검수에서 재확인)
ADJUSTMENTS = {
    # 서면 동단이 전포 시작 열(112230)을 한 칸 침범 — 전포역 서쪽 경계에서 분리
    "seomyeon": {"maxGridX": 112229},
    # 홍대입구 동단(9번출구 bbox)이 신촌 시작 열(110376, 경도≈126.932 서교동/노고산동 경계)을
    # 한 칸 침범 — 그 열은 신촌에 주고 홍대입구를 물린다.
    # 북단은 홍대입구역 위도(37.5583, 행 41731)부터 연남(수동 작도)에 주고 홍대입구를 물린다
    "hongdae": {"maxGridX": 110375, "maxGridY": 41730},
    # 공공데이터 압구정 bbox 가 신사역까지 내려와 가로수길(수동 작도)과 전면 겹침 —
    # 남단을 37.5228(행 41692)로 물려 가로수길에 준다 (압구정로데오 37.527 은 유지)
    "apgujeong": {"minGridY": 41692},
    # (강남↔압구정 겹침은 통칭 정확 매칭으로 재계산하니 소멸 — 이전 실측은 부분문자열 과매칭이 원인)
}


def base(n):
    n = re.sub(r"\s*\d+\s*번\s*출구.*$", "", n)
    return re.sub(r"역$", "", n).strip() or n


draft = json.load(open("zones-draft.json"))

# 2단계: 통칭+시도 단위 합집합. zoneKey 도 시/도 격리 — 같은 유명 이름이 서울·부산 양쪽에 있으면
# 전역 slug 하나로는 zones.zone_key UNIQUE 를 깨므로, 복수 시도에 걸린 이름만 "-seoul"/"-busan" 접미
SIDO_SLUG = {"11": "seoul", "26": "busan"}
name_sidos = {}
for x in draft:
    n = base(x["name"])
    if n in FAMOUS:
        name_sidos.setdefault(n, set()).add(x["_review"]["sgg_cd"][:2])

merged = {}
for x in draft:
    name = base(x["name"])
    if name not in FAMOUS:
        continue
    key = (name, x["_review"]["sgg_cd"][:2])
    slug = FAMOUS[name] if len(name_sidos[name]) == 1 else f"{FAMOUS[name]}-{SIDO_SLUG[key[1]]}"
    m = merged.setdefault(key, {
        "zoneKey": slug, "name": name, "regionCode": None,
        "minGridY": x["min_grid_y"], "maxGridY": x["max_grid_y"],
        "minGridX": x["min_grid_x"], "maxGridX": x["max_grid_x"],
        "priority": 0, "_sources": [],
    })
    m["minGridY"] = min(m["minGridY"], x["min_grid_y"])
    m["maxGridY"] = max(m["maxGridY"], x["max_grid_y"])
    m["minGridX"] = min(m["minGridX"], x["min_grid_x"])
    m["maxGridX"] = max(m["maxGridX"], x["max_grid_x"])
    m["_sources"].append(x["name"])

zones = list(merged.values())
for z in zones:
    z.update(ADJUSTMENTS.get(z["zoneKey"], {}))

# FR-2a 수동 작도분 병합 — 공공데이터에 없는 유명지 (경계 근거는 각 항목 _basis)
zones = sorted(zones + json.load(open("zones-manual.json")), key=lambda z: z["zoneKey"])

found = {z["name"] for z in zones}
missing = [n for n in FAMOUS if n not in found]

# 표시명 다듬기 — 데이터 매칭용 통칭("광안리해변")과 화면 표시명("광안리 A-3")이 다른 경우 (검수 2026-07-31)
DISPLAY_RENAME = {
    "광안리해변": "광안리", "해운대해변": "해운대",       # 해변 접미 제거
    "홍대입구": "홍대", "건대입구": "건대",               # 역 이름이 아니라 부르는 말로
    "안목커피거리": "안목", "애월카페거리": "애월",       # 수식어 제거 — 단독으로 충분히 유명
}
for z in zones:
    z["name"] = DISPLAY_RENAME.get(z["name"], z["name"])

# 시딩 포맷(_sources 제거)으로 저장
out = [{k: v for k, v in z.items() if not k.startswith("_")} for z in zones]
json.dump(out, open("zones-candidate.json", "w"), ensure_ascii=False, indent=1)

# geojson.io 검수용 — 격자 인덱스를 위경도로 역변환한 사각형
features = []
for z in zones:
    s, n = z["minGridY"] * GRID_LAT_STEP, (z["maxGridY"] + 1) * GRID_LAT_STEP
    w, e = z["minGridX"] * GRID_LNG_STEP, (z["maxGridX"] + 1) * GRID_LNG_STEP
    features.append({
        "type": "Feature",
        "properties": {"name": z["name"], "zoneKey": z["zoneKey"],
                       "rows": z["maxGridY"] - z["minGridY"] + 1,
                       "cols": z["maxGridX"] - z["minGridX"] + 1,
                       "sources": z["_sources"]},
        "geometry": {"type": "Polygon",
                     "coordinates": [[[w, s], [e, s], [e, n], [w, n], [w, s]]]},
    })
json.dump({"type": "FeatureCollection", "features": features},
          open("zones-candidate.geojson", "w"), ensure_ascii=False)

print(f"후보 {len(zones)}건 생성 (원본 {sum(len(z['_sources']) for z in zones)}건 합침)")
for z in zones:
    rows, cols = z["maxGridY"] - z["minGridY"] + 1, z["maxGridX"] - z["minGridX"] + 1
    srcs = len(z["_sources"])
    print(f"  {z['zoneKey']:12s} {z['name']:8s} {rows:2d}행 × {cols:2d}열  (원본 {srcs}건)")
if missing:
    print(f"\n초안에 없어 후보 미포함(수동 작도 대상): {missing}")
for name, why in MANUAL_EXCLUDED.items():
    print(f"의도적 제외 — {name}: {why}")
