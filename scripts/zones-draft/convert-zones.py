# MSG-234 스모크: 공공데이터 상권 → FillMap zones.json 초안 변환 (2소스 병합)
# 1차: 소진공 주요상권현황 CSV (WGS84 경위도 원본 — 좌표계 모호성 없음, 2024-01 기준 1,227건)
# 2차: 소진공 소상공인365 회식상권 SHP (EPSG:5181 — 주요상권현황과 서면 bbox 교차 검증으로 5181 확정, 2025-08 기준 904건)
#      CSV 에 없는 상권명만 보충한다.
# 출력: zones-draft.json (서울 11·부산 26 필터) / zones-draft.geojson (geojson.io 검수용)
# 사용법: python3 convert-zones.py <주요상권현황.csv> <회식상권SHP경로(확장자 제외)> <상가정보_부산.csv>
#   데이터 출처·다운로드 방법: LLM-WIKI 06-research "zone 표시명 데이터 파이프라인 해설"
import csv
import json
import math
import os
import re
import sys

import shapefile
from pyproj import Transformer

csv.field_size_limit(sys.maxsize)

GRID_LAT_STEP = 0.0009    # GridConstants 공유 상수
GRID_LNG_STEP = 0.00115
ROW_CAP = 25              # zones CHECK: max_grid_y - min_grid_y <= 25

if len(sys.argv) != 4:
    sys.exit("사용법: python3 convert-zones.py <주요상권현황.csv> <회식상권SHP경로> <상가정보_부산.csv>\n"
             "  (공공데이터포털 원본 3종 — 출처는 위키 06-research 'zone 표시명 데이터 파이프라인 해설')")
CSV_PATH, SHP_PATH, STORE_CSV = sys.argv[1], sys.argv[2], sys.argv[3]
for p in (CSV_PATH, STORE_CSV):
    if not os.path.exists(p):
        sys.exit(f"입력 파일 없음: {p}")


def to_zone(key, name, min_lat, min_lng, max_lat, max_lng, source, sgg, dong=""):
    min_gy, max_gy = math.floor(min_lat / GRID_LAT_STEP), math.floor(max_lat / GRID_LAT_STEP)
    min_gx, max_gx = math.floor(min_lng / GRID_LNG_STEP), math.floor(max_lng / GRID_LNG_STEP)
    return {
        "zone_key": key, "name": name,
        "min_grid_y": min_gy, "max_grid_y": max_gy,
        "min_grid_x": min_gx, "max_grid_x": max_gx,
        "priority": 0,
        "_review": {
            "source": source, "rows": max_gy - min_gy + 1, "cols": max_gx - min_gx + 1,
            "over_row_cap": max_gy - min_gy > ROW_CAP, "sgg_cd": sgg, "dong_cd": dong,
            "bbox_wgs84": [round(min_lat, 5), round(min_lng, 5), round(max_lat, 5), round(max_lng, 5)],
        },
    }


zones = []

# ── 1차: 주요상권현황 CSV (WGS84) ──
for r in csv.DictReader(open(CSV_PATH)):
    if r["시도코드"] not in ("11", "26"):
        continue
    # 일부 행은 WKT 잔재 괄호가 섞여 있다 — 숫자만 추출해 (lng, lat) 쌍으로 복원
    nums = re.findall(r"-?\d+\.?\d*", r["상권좌표"])
    pts = [(float(nums[i]), float(nums[i + 1])) for i in range(0, len(nums) - 1, 2)]
    lngs, lats = zip(*pts)
    zones.append(to_zone(f"draft-major-{r['상권번호']}", r["상권명"],
                         min(lats), min(lngs), max(lats), max(lngs), "주요상권현황", r["시군구코드"]))

# ── 2차: 회식상권 SHP (EPSG:5181, CSV 에 없는 이름만 보충) ──
# 중복 판정은 (이름, 시도) 쌍 — 이름만으로 걸면 서울 "중앙시장"이 부산 동명 상권을 잘못 삭제한다
# (시/도 격리 원칙: 시/도가 다르면 동명이라도 별개 상권)
seen = {(z["name"], z["_review"]["sgg_cd"][:2]) for z in zones}
t = Transformer.from_crs("EPSG:5181", "EPSG:4326", always_xy=True)
sf = shapefile.Reader(SHP_PATH, encoding="utf-8")
fields = [f[0] for f in sf.fields[1:]]
for rec in sf.iterRecords():
    d = dict(zip(fields, rec))
    sgg = str(d["SGG_CD"] or "")
    if not (sgg.startswith("11") or sgg.startswith("26")) or (d["MJR_BIZON_"], sgg[:2]) in seen:
        continue
    min_lng, min_lat = t.transform(float(d["MIN_X_CRDN"]), float(d["MIN_Y_CRDN"]))
    max_lng, max_lat = t.transform(float(d["MAX_X_CRDN"]), float(d["MAX_Y_CRDN"]))
    zones.append(to_zone(f"draft-hoesik-{d['MRKT_CD']}", d["MJR_BIZON_"],
                         min_lat, min_lng, max_lat, max_lng, "회식상권", sgg, str(d["DONG_CD"] or "")))

# ── 3차: 해변 관광 상권 갭필 — 상가(상권)정보 점포 밀집 파생 (상권 데이터셋 전무 지역 한정) ──
# 해변 도로변 점포의 p5~p95 bbox. 근거는 실점포 분포지만 상권 경계 실측이 아니므로 검수 우선 대상.
GAP_FILL = {"광안리해변": ["광안해변로"], "해운대해변": ["해운대해변로", "구남로"]}
gap_pts = {k: [] for k in GAP_FILL}
with open(STORE_CSV, encoding="utf-8-sig") as f:
    for r in csv.DictReader(f):
        road = r["도로명"] or ""
        for gz, roads in GAP_FILL.items():
            if any(road.endswith(rd) for rd in roads):
                try:
                    gap_pts[gz].append((float(r["위도"]), float(r["경도"])))
                except ValueError:
                    pass


def pctl(v, p):
    v = sorted(v)
    return v[min(len(v) - 1, int(len(v) * p))]


for gz, ps in gap_pts.items():
    if not ps:
        # 도로명 매칭 0점포 = 원본 데이터 변경 신호 — 조용히 IndexError 로 죽지 않고 원인을 말한다
        print(f"경고: 갭필 '{gz}' 도로명 매칭 점포 0건 — 생략. GAP_FILL 도로명({GAP_FILL[gz]})과 "
              f"상가정보 CSV '도로명' 컬럼을 대조하라", file=sys.stderr)
        continue
    lats, lngs = [p[0] for p in ps], [p[1] for p in ps]
    zones.append(to_zone(f"draft-gapfill-{gz}", gz,
                         pctl(lats, .05), pctl(lngs, .05), pctl(lats, .95), pctl(lngs, .95),
                         f"상가밀집파생({len(ps)}점포)", "26"))

zones.sort(key=lambda z: (z["_review"]["sgg_cd"], z["name"]))
with open("zones-draft.json", "w") as f:
    json.dump(zones, f, ensure_ascii=False, indent=1)
with open("zones-draft.geojson", "w") as f:
    json.dump({"type": "FeatureCollection", "features": [{
        "type": "Feature",
        "properties": {"name": z["name"], "source": z["_review"]["source"], "sgg": z["_review"]["sgg_cd"]},
        "geometry": {"type": "Polygon", "coordinates": [[
            [z["_review"]["bbox_wgs84"][1], z["_review"]["bbox_wgs84"][0]],
            [z["_review"]["bbox_wgs84"][3], z["_review"]["bbox_wgs84"][0]],
            [z["_review"]["bbox_wgs84"][3], z["_review"]["bbox_wgs84"][2]],
            [z["_review"]["bbox_wgs84"][1], z["_review"]["bbox_wgs84"][2]],
            [z["_review"]["bbox_wgs84"][1], z["_review"]["bbox_wgs84"][0]],
        ]]},
    } for z in zones]}, f, ensure_ascii=False)

major = sum(1 for z in zones if z["_review"]["source"] == "주요상권현황")
seoul = sum(1 for z in zones if z["_review"]["sgg_cd"].startswith("11"))
over = [z["name"] for z in zones if z["_review"]["over_row_cap"]]
print(f"초안 {len(zones)}건 = 주요상권현황 {major} + 회식상권 보충 {len(zones) - major} | 서울 {seoul} · 부산 {len(zones) - seoul} | 26행 캡 초과 {len(over)}건 {over[:5]}")
