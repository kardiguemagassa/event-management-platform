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
E2E_USER="e2e.$TS@example.com"        # organizer and attendee: drives the whole scenario
E2E_OTHER="e2e.other.$TS@example.com" # another organizer: must not change the events of the first one
E2E_ADMIN="e2e.admin.$TS@example.com" # administrator: reads every user profile
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
# Attendees 1 to 4 (default ATTENDEE role only): each registers with their own token, never a user id.
attendee() { echo "e2e.attendee.$1.$TS@example.com"; }
attendee_token() { kc_token event-platform "$E2E_CLIENT" "$(attendee "$1")" "$E2E_PASSWORD"; }
register() { AUTH=$(attendee_token "$1") call POST /api/registrations "{\"eventId\":\"$E\"}"; }

echo "== Test identity (Keycloak admin API)"
cleanup() {
  local id user n token
  # Profiles first, while their owners can still get a token (right to erasure: DELETE /api/users/me).
  for n in 1 2 3 4; do
    token=$(attendee_token "$n"); [ -n "$token" ] && AUTH=$token call DELETE /api/users/me
  done
  for user in "$E2E_USER" "$E2E_OTHER" "$E2E_ADMIN" "$(attendee 1)" "$(attendee 2)" "$(attendee 3)" "$(attendee 4)"; do
    id=$(first_id "$(admin GET "/users?email=$user&exact=true")"); [ -n "$id" ] && admin DELETE "/users/$id" >/dev/null
  done
  id=$(first_id "$(admin GET "/clients?clientId=$E2E_CLIENT")"); [ -n "$id" ] && admin DELETE "/clients/$id" >/dev/null
  return 0
}
# create_user EMAIL [REALM_ROLE] -> test user with the default ATTENDEE role, plus REALM_ROLE if given
create_user() {
  admin POST /users "{\"username\":\"$1\",\"email\":\"$1\",\"emailVerified\":true,\"enabled\":true,
    \"firstName\":\"E2E\",\"lastName\":\"Tester\",
    \"credentials\":[{\"type\":\"password\",\"value\":\"$E2E_PASSWORD\",\"temporary\":false}]}" >/dev/null
  [ $# -ge 2 ] || return 0
  local id; id=$(first_id "$(admin GET "/users?email=$1&exact=true")")
  admin POST "/users/$id/role-mappings/realm" "[$(admin GET "/roles/$2")]" >/dev/null
}
cleanup  # a previous interrupted run may have left its client behind
trap cleanup EXIT
# Public client with the password grant: test-only, never part of the realm file.
admin POST /clients "{\"clientId\":\"$E2E_CLIENT\",\"publicClient\":true,\"standardFlowEnabled\":false,
  \"directAccessGrantsEnabled\":true,\"protocolMappers\":[{\"name\":\"event-platform-api audience\",
  \"protocol\":\"openid-connect\",\"protocolMapper\":\"oidc-audience-mapper\",\"config\":{
  \"included.custom.audience\":\"event-platform-api\",\"access.token.claim\":\"true\",\"id.token.claim\":\"false\"}}]}" >/dev/null
create_user "$E2E_USER" ORGANIZER
create_user "$E2E_OTHER" ORGANIZER
create_user "$E2E_ADMIN" ADMIN
for n in 1 2 3 4; do create_user "$(attendee "$n")"; done
refresh_token
[ -n "$TOKEN" ] || { echo "FAIL  no access token for the test user"; exit 1; }
echo "      test users and client ready"

# Healthy containers are not routable yet: the gateway must first see each service in Eureka.
# Routed means answered by the service itself: any status but a 5xx (gateway fallback) or no answer at all.
echo "== Waiting for the gateway to route every service"
for route in /api/users/me /api/events /api/registrations/me; do
  for i in $(seq 1 60); do call GET "$route"; case "$CODE" in 5*|000) sleep 2 ;; *) break ;; esac; done
  case "$CODE" in 5*|000) echo "FAIL  the gateway does not route $route"; exit 1 ;; esac
done

echo "== Profiles (user-service)"
for n in 1 2 3 4; do
  AUTH=$(attendee_token "$n") call PUT /api/users/me "{\"fullName\":\"Attendee $n\"}"
  check "attendee $n creates their profile" 201
done
AUTH=$(attendee_token 1) call GET /api/users/me
[ "$(field email)" = "$(attendee 1)" ] && { PASS=$((PASS+1)); echo "PASS  profile e-mail comes from the token"; } \
                                      || { FAIL=$((FAIL+1)); echo "FAIL  profile e-mail body=$BODY"; }
AUTH=$(attendee_token 1) call GET /api/users; check "attendee cannot list the users" 403 ACCESS_DENIED
AUTH=$(kc_token event-platform "$E2E_CLIENT" "$E2E_ADMIN" "$E2E_PASSWORD") call GET /api/users
check "administrator lists the users" 200

echo "== Setup"
# registration-service needs its own view of event-service: an unknown event (404) proves it reaches it.
E=00000000-0000-0000-0000-000000000000
for i in $(seq 1 30); do register 1; [ "$CODE" = 404 ] && break; sleep 2; done
check "registration-service reaches event-service" 404
call POST /api/events '{"title":"Docker Day","location":"Paris","startAt":"2027-03-15T18:00:00Z","capacity":2}'
check "create event (capacity 2)" 201; E=$(field id)
AUTH=none call GET "/api/events/$E"; check "draft hidden from anonymous callers" 404 EVENT_NOT_FOUND
call POST "/api/events/$E/publish"; echo "      publish -> $CODE"
AUTH=$(kc_token event-platform "$E2E_CLIENT" "$E2E_OTHER" "$E2E_PASSWORD") call POST "/api/events/$E/cancel"
check "another organizer cannot cancel the event" 403 NOT_EVENT_ORGANIZER

echo "== Security at the gateway"
AUTH=none call GET /api/users; check "anonymous call to a protected route" 401 UNAUTHENTICATED
AUTH=not-a-jwt call GET /api/users; check "malformed access token" 401 UNAUTHENTICATED
AUTH=$(kc_token master admin-cli "$KEYCLOAK_ADMIN_USERNAME" "$KEYCLOAK_ADMIN_PASSWORD") call GET /api/users
check "token issued by another realm" 401 UNAUTHENTICATED
AUTH=none call GET "/api/events/$E"; check "anonymous read of the public catalog" 200
call POST "/api/events/$E/capacity/reserve" '{}'; check "internal capacity route closed at the edge" 403 ACCESS_DENIED

echo "== Business rules"
register 1; check "register attendee 1" 201; R1=$(field id)
register 1; check "duplicate attendee 1" 409 ALREADY_REGISTERED
register 2; check "register attendee 2" 201; R2=$(field id)
AUTH=$(attendee_token 2) call POST "/api/registrations/$R1/cancel"
check "another attendee cannot cancel the registration" 404 REGISTRATION_NOT_FOUND
call GET "/api/registrations/events/$E"; check "organizer lists the registrations of the event" 200
AUTH=$(kc_token event-platform "$E2E_CLIENT" "$E2E_OTHER" "$E2E_PASSWORD") call GET "/api/registrations/events/$E"
check "another organizer cannot list them" 403 NOT_EVENT_ORGANIZER
register 3; echo "      attendee 3 on full event -> $CODE $(field code)"
AUTH=$(attendee_token 1) call POST "/api/registrations/$R1/cancel"; echo "      cancel attendee 1 -> $CODE"
register 3; check "register attendee 3 on freed seat" 201
AUTH=$(attendee_token 2) call POST "/api/registrations/$R2/cancel"
echo "      cancel attendee 2 -> $CODE (1 seat left for the outage test)"

echo "== Outage: event-service stopped"
docker compose --profile app stop event-service >/dev/null 2>&1
for i in $(seq 1 12); do register 4; echo "      #$i -> $CODE $(field code) ${TIME}s"; done
call GET /api/events; echo "      gateway GET /api/events -> $CODE $(field code) instance=$(field instance)"

echo "== Recovery"
docker compose --profile app start event-service >/dev/null 2>&1
until [ "$(docker compose --profile app ps --format '{{.Health}}' event-service)" = healthy ]; do sleep 2; done
START=$(date +%s)
for i in $(seq 1 30); do register 4; [ "$CODE" = 201 ] && break; sleep 2; done
check "register attendee 4 after recovery ($(( $(date +%s) - START ))s after healthy)" 201

# The gateway discovers the restarted instance on its own schedule (Eureka fetch and load balancer cache).
START=$(date +%s)
for i in $(seq 1 30); do call GET "/api/events/$E"; [ "$CODE" = 200 ] && break; sleep 1; done
check "gateway routes event-service again ($(( $(date +%s) - START ))s)" 200

echo "== No seat leaked"
SEATS=$(field availableSeats)
[ "$SEATS" = 0 ] && { PASS=$((PASS+1)); echo "PASS  availableSeats=0 (capacity 2, attendees 3 and 4 confirmed)"; } \
                 || { FAIL=$((FAIL+1)); echo "FAIL  availableSeats=$SEATS body=$BODY"; }

echo "== Right to erasure"
AUTH=$(attendee_token 4) call DELETE /api/users/me; check "attendee 4 deletes their profile" 204

echo "== Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
