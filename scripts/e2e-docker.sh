#!/usr/bin/env bash
# End-to-end smoke test of the containerised platform, through the gateway only.
# Requires the full stack: docker compose --profile app up -d --build
# Run from the repository root: ./scripts/e2e-docker.sh (it stops and restarts event-service).
# Test identities are created through the Keycloak admin API (credentials from .env) and deleted on exit.
set -uo pipefail
set -a; . ./.env; set +a
G=http://127.0.0.1:8080
KC=http://127.0.0.1:${KEYCLOAK_PORT:-8180}
TS=$(date +%s)
PASS=0; FAIL=0

json() { python3 -c 'import json,sys;print(json.loads(sys.argv[1]).get(sys.argv[2],""))' "$1" "$2" 2>/dev/null; }
first_id() { python3 -c 'import json,sys;a=json.loads(sys.argv[1]);print(a[0]["id"] if a else "")' "$1" 2>/dev/null; }

# kc_token REALM CLIENT USERNAME PASSWORD -> prints an access token (resource owner password grant, tests only)
kc_token() {
  json "$(curl -s --data-urlencode grant_type=password --data-urlencode "client_id=$2" \
    --data-urlencode "username=$3" --data-urlencode "password=$4" \
    "$KC/realms/$1/protocol/openid-connect/token")" access_token
}
# admin METHOD PATH [JSON] -> Keycloak admin REST call on the event-platform realm (admin tokens live 60 s)
admin() {
  local token; token=$(kc_token master admin-cli "$KEYCLOAK_ADMIN_USERNAME" "$KEYCLOAK_ADMIN_PASSWORD")
  local args=(-s -X "$1" -H "Authorization: Bearer $token" "$KC/admin/realms/event-platform$2")
  [ $# -ge 3 ] && args+=(-H 'Content-Type: application/json' -d "$3")
  curl "${args[@]}"
}

E2E_CLIENT=e2e-tests
E2E_USER="e2e.$TS@example.com"
E2E_PASSWORD=$(openssl rand -hex 16)
TOKEN=""; TOKEN_AT=0
# Access tokens live 5 minutes and a run can last longer: renew after 4.
refresh_token() {
  if [ $(( $(date +%s) - TOKEN_AT )) -ge 240 ]; then
    TOKEN=$(kc_token event-platform "$E2E_CLIENT" "$E2E_USER" "$E2E_PASSWORD"); TOKEN_AT=$(date +%s)
  fi
}

# call METHOD PATH [JSON] -> sets CODE, TIME, BODY
# Authenticated as the test user by default; AUTH=none for an anonymous call, AUTH=<token> for a given token.
call() {
  local args=(-s -w $'\n%{http_code} %{time_total}' -X "$1" "$G$2")
  [ $# -ge 3 ] && args+=(-H 'Content-Type: application/json' -d "$3")
  case "${AUTH:-user}" in
    user) refresh_token; args+=(-H "Authorization: Bearer $TOKEN") ;;
    none) ;;
    *) args+=(-H "Authorization: Bearer $AUTH") ;;
  esac
  local out; out=$(curl "${args[@]}")
  BODY=${out%$'\n'*}; local last=${out##*$'\n'}; CODE=${last% *}; TIME=${last#* }
  [ "$BODY" = "$out" ] && BODY=""
}
field() { python3 -c 'import json,sys;print(json.loads(sys.argv[1]).get(sys.argv[2],""))' "$BODY" "$1" 2>/dev/null; }
check() { # LABEL EXPECTED_STATUS [EXPECTED_CODE]
  local got_code=""; [ $# -ge 3 ] && got_code=$(field code)
  if [ "$CODE" = "$2" ] && { [ $# -lt 3 ] || [ "$got_code" = "$3" ]; }; then
    PASS=$((PASS+1)); echo "PASS  $1 -> $CODE ${got_code}"
  else
    FAIL=$((FAIL+1)); echo "FAIL  $1 -> $CODE (expected $2 ${3:-}) body=$BODY"
  fi
}
register() { call POST /api/registrations "{\"eventId\":\"$E\",\"userId\":\"$1\"}"; }

echo "== Test identity (Keycloak admin API)"
cleanup() {
  local id
  id=$(first_id "$(admin GET "/users?email=$E2E_USER&exact=true")"); [ -n "$id" ] && admin DELETE "/users/$id" >/dev/null
  id=$(first_id "$(admin GET "/clients?clientId=$E2E_CLIENT")"); [ -n "$id" ] && admin DELETE "/clients/$id" >/dev/null
  return 0
}
cleanup  # a previous interrupted run may have left its client behind
trap cleanup EXIT
# Public client with the password grant: test-only, never part of the realm file.
admin POST /clients "{\"clientId\":\"$E2E_CLIENT\",\"publicClient\":true,\"standardFlowEnabled\":false,
  \"directAccessGrantsEnabled\":true,\"protocolMappers\":[{\"name\":\"event-platform-api audience\",
  \"protocol\":\"openid-connect\",\"protocolMapper\":\"oidc-audience-mapper\",\"config\":{
  \"included.custom.audience\":\"event-platform-api\",\"access.token.claim\":\"true\",\"id.token.claim\":\"false\"}}]}" >/dev/null
admin POST /users "{\"username\":\"$E2E_USER\",\"email\":\"$E2E_USER\",\"emailVerified\":true,\"enabled\":true,
  \"firstName\":\"E2E\",\"lastName\":\"Tester\",
  \"credentials\":[{\"type\":\"password\",\"value\":\"$E2E_PASSWORD\",\"temporary\":false}]}" >/dev/null
refresh_token
[ -n "$TOKEN" ] || { echo "FAIL  no access token for the test user"; exit 1; }
echo "      test user and client ready"

# Healthy containers are not routable yet: the gateway must first see each service in Eureka.
echo "== Waiting for the gateway to route every service"
for route in /api/users /api/events /api/registrations/users/00000000-0000-0000-0000-000000000000; do
  for i in $(seq 1 60); do call GET "$route"; [ "$CODE" = 200 ] && break; sleep 2; done
  [ "$CODE" = 200 ] || { echo "FAIL  the gateway does not route $route"; exit 1; }
done

echo "== Setup"
for u in 1 2 3 4; do
  call POST /api/users "{\"fullName\":\"Docker User $u\",\"email\":\"docker.$u.$TS@example.com\"}"
  check "create user $u" 201; eval "U$u=$(field id)"
done
# registration-service needs its own view of both dependencies: an unknown event (404) proves it reaches them.
E=00000000-0000-0000-0000-000000000000
for i in $(seq 1 30); do register "$U1"; [ "$CODE" = 404 ] && break; sleep 2; done
check "registration-service reaches user-service and event-service" 404
call POST /api/events '{"title":"Docker Day","location":"Paris","startAt":"2027-03-15T18:00:00Z","capacity":2}'
check "create event (capacity 2)" 201; E=$(field id)
call POST "/api/events/$E/publish"; echo "      publish -> $CODE"

echo "== Security at the gateway"
AUTH=none call GET /api/users; check "anonymous call to a protected route" 401 UNAUTHENTICATED
AUTH=not-a-jwt call GET /api/users; check "malformed access token" 401 UNAUTHENTICATED
AUTH=$(kc_token master admin-cli "$KEYCLOAK_ADMIN_USERNAME" "$KEYCLOAK_ADMIN_PASSWORD") call GET /api/users
check "token issued by another realm" 401 UNAUTHENTICATED
AUTH=none call GET "/api/events/$E"; check "anonymous read of the public catalog" 200
call POST "/api/events/$E/capacity/reserve" '{}'; check "internal capacity route closed at the edge" 403 ACCESS_DENIED

echo "== Business rules"
register "$U1"; check "register user 1" 201; R1=$(field id)
register "$U1"; check "duplicate user 1" 409 ALREADY_REGISTERED
register "$U2"; check "register user 2" 201; R2=$(field id)
register "$U3"; echo "      user 3 on full event -> $CODE $(field code)"
call POST "/api/registrations/$R1/cancel"; echo "      cancel user 1 -> $CODE"
register "$U3"; check "register user 3 on freed seat" 201
call POST "/api/registrations/$R2/cancel"; echo "      cancel user 2 -> $CODE (1 seat left for the outage test)"

echo "== Outage: event-service stopped"
docker compose --profile app stop event-service >/dev/null 2>&1
for i in $(seq 1 12); do register "$U4"; echo "      #$i -> $CODE $(field code) ${TIME}s"; done
call GET /api/events; echo "      gateway GET /api/events -> $CODE $(field code) instance=$(field instance)"

echo "== Recovery"
docker compose --profile app start event-service >/dev/null 2>&1
until [ "$(docker compose --profile app ps --format '{{.Health}}' event-service)" = healthy ]; do sleep 2; done
START=$(date +%s)
for i in $(seq 1 30); do register "$U4"; [ "$CODE" = 201 ] && break; sleep 2; done
check "register user 4 after recovery ($(( $(date +%s) - START ))s after healthy)" 201

# The gateway discovers the restarted instance on its own schedule (Eureka fetch and load balancer cache).
START=$(date +%s)
for i in $(seq 1 30); do call GET "/api/events/$E"; [ "$CODE" = 200 ] && break; sleep 1; done
check "gateway routes event-service again ($(( $(date +%s) - START ))s)" 200

echo "== No seat leaked"
SEATS=$(field availableSeats)
[ "$SEATS" = 0 ] && { PASS=$((PASS+1)); echo "PASS  availableSeats=0 (capacity 2, users 3 and 4 confirmed)"; } \
                 || { FAIL=$((FAIL+1)); echo "FAIL  availableSeats=$SEATS body=$BODY"; }

echo "== Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
