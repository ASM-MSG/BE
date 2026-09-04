# FillMap API 문서 사이트 (docs.fillmap.kr)

팀 전용 API 문서. 가이드 다섯 장은 `docs/*.md` 에 손으로 쓰고, API 레퍼런스는 dev 서버의
OpenAPI 스펙 스냅샷을 Scalar 로 그린다. 에러 코드 표는 `*ErrorCode` enum 에서 생성한다.
배포는 `.github/workflows/cd-dev.yml` 의 docs 잡이 dev 배포 뒤에 자동으로 한다. 인프라와
접근 계정은 `.claude/docs/deploy.md` 의 "API 문서 사이트" 절 참고 (MSG-568).

## 로컬에서 보기

```bash
cd api-docs
python3 -m pip install -r requirements.txt
DOCS_BASIC_AUTH=user:pass ./scripts/fetch-openapi.sh     # dev 스펙 스냅샷 → docs/openapi.json
python3 scripts/gen-error-codes.py                        # enum → docs/error-codes.md
python3 -m mkdocs serve                                   # http://127.0.0.1:8000
```

로컬 앱에서 스펙을 받으려면 `./scripts/fetch-openapi.sh http://localhost:8080/v3/api-docs`.
