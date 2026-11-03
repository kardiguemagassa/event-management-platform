#!/usr/bin/env bash
# OpenAPI documents of the services, as the front-end generates its API client from them (frontend/openapi).
#   ./scripts/openapi.sh update   fetch them from the running platform (gateway on localhost:8080)
#   ./scripts/openapi.sh check    fail if the running services no longer match the committed documents
set -euo pipefail

GATEWAY=${GATEWAY:-http://localhost:8080}
DIR=$(cd "$(dirname "$0")/../frontend/openapi" && pwd)
SERVICES=(user-service event-service registration-service)

# fetch SERVICE FILE: the document, formatted, written only once complete. Services that have just started are not
# routed by the gateway until Eureka lists them: retried for up to a minute.
fetch() {
  local tmp; tmp=$(mktemp)
  for _ in $(seq 1 30); do
    if curl -sf "$GATEWAY/api-docs/$1" | python3 -m json.tool --indent 2 > "$tmp" 2>/dev/null; then
      mv "$tmp" "$2"; return 0
    fi
    sleep 2
  done
  rm -f "$tmp"; echo "FAIL  $1: no OpenAPI document from $GATEWAY/api-docs/$1" >&2; return 1
}

case "${1:-}" in
  update)
    for s in "${SERVICES[@]}"; do fetch "$s" "$DIR/$s.json"; echo "updated frontend/openapi/$s.json"; done
    echo "Now run: (cd frontend && npm run api:generate)"
    ;;
  check)
    tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
    status=0
    for s in "${SERVICES[@]}"; do
      fetch "$s" "$tmp/$s.json" || { status=1; continue; }
      if diff -u "$DIR/$s.json" "$tmp/$s.json" > "$tmp/$s.diff"; then
        echo "PASS  $s: OpenAPI document unchanged"
      else
        echo "FAIL  $s: OpenAPI document changed, run ./scripts/openapi.sh update then npm run api:generate"
        head -40 "$tmp/$s.diff"; status=1
      fi
    done
    exit $status
    ;;
  *) echo "usage: $0 update|check" >&2; exit 2 ;;
esac
