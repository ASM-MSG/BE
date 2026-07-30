# MSG-259: zones.json 시딩 전 검증 — 캡·겹침·키 중복을 기계로 잡는다 (통과 전 시딩 금지)
# 사용법: python3 validate-zones.py <zones.json>   (초안·확정본 모두 동일 포맷 가정, _review 있으면 무시)
import itertools
import json
import sys

zones = json.load(open(sys.argv[1]))
errors = []


def field(z, snake, camel):
    return z[snake] if snake in z else z[camel]


rects = []
for z in zones:
    key = field(z, "zone_key", "zoneKey")
    r = (key, z.get("name"),
         field(z, "min_grid_y", "minGridY"), field(z, "max_grid_y", "maxGridY"),
         field(z, "min_grid_x", "minGridX"), field(z, "max_grid_x", "maxGridX"))
    rects.append(r)
    if r[3] < r[2] or r[5] < r[4]:
        errors.append(f"[범위역전] {key}: min > max")
    if r[3] - r[2] > 25:  # V8 CHECK와 동일 — 알파벳 26행(A~Z) 한계
        errors.append(f"[26행캡] {key} '{r[1]}': {r[3] - r[2] + 1}행 (남북 {(r[3] - r[2] + 1) * 0.1:.1f}km)")

keys = [r[0] for r in rects]
for k in {k for k in keys if keys.count(k) > 1}:
    errors.append(f"[키중복] zone_key '{k}' {keys.count(k)}건")

for a, b in itertools.combinations(rects, 2):
    if not (a[3] < b[2] or b[3] < a[2] or a[5] < b[4] or b[5] < a[4]):
        oy = min(a[3], b[3]) - max(a[2], b[2]) + 1
        ox = min(a[5], b[5]) - max(a[4], b[4]) + 1
        errors.append(f"[겹침] '{a[1]}'({a[0]}) ↔ '{b[1]}'({b[0]}): {oy}행 × {ox}열")

if errors:
    print(f"FAIL — {len(zones)}건 중 위반 {len(errors)}건:")
    for e in errors:
        print(f"  {e}")
    sys.exit(1)
print(f"PASS — {len(zones)}건, 캡·겹침·키중복 0")
