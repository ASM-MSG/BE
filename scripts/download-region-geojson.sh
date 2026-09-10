#!/usr/bin/env bash
# 전국 행정동 경계 GeoJSON 다운로드 (MSG-154 · D3).
# 출처: 공개 데이터셋 vuski/admdongkor. 파일은 수십 MB라 저장소에 커밋하지 않고
# 외부 경로(data/region/)로 내려받아 게이트된 시더(RegionSeeder)가 읽는다.
#
# 사용:
#   scripts/download-region-geojson.sh                 # 기본 버전 다운로드
#   VERSION=20260401 scripts/download-region-geojson.sh  # 다른 버전
set -euo pipefail

VERSION="${VERSION:-20260701}"
FILE="HangJeongDong_ver${VERSION}.geojson"
URL="https://raw.githubusercontent.com/vuski/admdongkor/master/ver${VERSION}/${FILE}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST_DIR="${SCRIPT_DIR}/../data/region"
DEST="${DEST_DIR}/${FILE}"

mkdir -p "${DEST_DIR}"
echo "Downloading ${URL}"
curl -fSL --retry 3 -o "${DEST}" "${URL}"
echo "Saved to ${DEST} ($(du -h "${DEST}" | cut -f1))"
