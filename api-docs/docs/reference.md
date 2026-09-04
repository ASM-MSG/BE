---
title: API 레퍼런스
hide:
  - navigation
  - toc
---

<div id="scalar-app"></div>

<script src="https://cdn.jsdelivr.net/npm/@scalar/api-reference"></script>
<script>
  // openapi.json 은 빌드 시점에 dev 서버 /v3/api-docs 를 받아 둔 스냅샷이다 (scripts/fetch-openapi.sh).
  // proxyUrl 을 비워 Try it 요청이 제3자 프록시를 거치지 않고 api.fillmap.kr 로 직접 간다 (dev CORS 에 docs 오리진 허용됨).
  Scalar.createApiReference('#scalar-app', {
    url: '/openapi.json',
    proxyUrl: '',
    theme: 'default',
    layout: 'modern',
    hideDarkModeToggle: true,
    tagsSorter: 'alpha',        // 스펙 순서(비밀번호가 첫 태그)가 아니라 가나다순으로
    authentication: { preferredSecurityScheme: 'bearerAuth' },
  })
</script>
