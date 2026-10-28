#!/usr/bin/env bash
# End-to-end smoke test of the containerised platform, through the gateway only.
# Requires the full stack: docker compose --profile app up -d --build
# Run from the repository root: ./scripts/e2e-docker.sh (it stops and restarts event-service).
set -uo pipefail
G=http://127.0.0.1:8080
TS=$(date +%s)
PASS=0; FAIL=0

# call METHOD PATH [JSON] -> sets CODE, TIME, BODY
call() {
  local args=(-s -w $'\n%{http_code} %{time_total}' -X "$1" "$G$2")
  [ $# -ge 3 ] && args+=(-H 'Content-Type: application/json' -d "$3")
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

echo "== No seat leaked"
call GET "/api/events/$E"; SEATS=$(field availableSeats)
[ "$SEATS" = 0 ] && { PASS=$((PASS+1)); echo "PASS  availableSeats=0 (capacity 2, users 3 and 4 confirmed)"; } \
                 || { FAIL=$((FAIL+1)); echo "FAIL  availableSeats=$SEATS body=$BODY"; }

echo "== Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
