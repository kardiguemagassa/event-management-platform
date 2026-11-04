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
# TRACEPARENT=<W3C trace context> starts the call in a given trace (distributed tracing check).
call() {
  local args=(-s -w $'\n%{http_code} %{time_total}' -X "$1" "$G$2")
  [ $# -ge 3 ] && args+=(-H 'Content-Type: application/json' -d "$3")
  [ -n "${TRACEPARENT:-}" ] && args+=(-H "traceparent: $TRACEPARENT")
  case "${AUTH:-user}" in
    user) refresh_token; args+=(-H "Authorization: Bearer $TOKEN") ;;
    none) ;;
    *) args+=(-H "Authorization: Bearer $AUTH") ;;
  esac
  local out; out=$(curl "${args[@]}")
  BODY=${out%$'\n'*}; local last=${out##*$'\n'}; CODE=${last% *}; TIME=${last#* }
  [ "$BODY" = "$out" ] && BODY=""
}
field() { python3 -c 'import json,sys;v=json.loads(sys.argv[1]).get(sys.argv[2]);print("" if v is None else v)' "$BODY" "$1" 2>/dev/null; }
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
# The seat is reserved asynchronously (saga over Kafka): a registration is accepted PENDING (202), then answered.
# answered ID ATTENDEE [SECONDS] -> polls the registration until it leaves PENDING; sets CODE, BODY and STATUS.
answered() {
  local token i; token=$(attendee_token "$2")
  for i in $(seq 1 "${3:-30}"); do
    AUTH=$token call GET "/api/registrations/$1"; STATUS=$(field status)
    [ "$STATUS" != PENDING ] && return 0; sleep 1
  done
}
# expect_answer LABEL ID ATTENDEE STATUS [REJECTION_REASON]
expect_answer() {
  answered "$2" "$3"
  local reason; reason=$(field rejectionReason)
  if [ "$STATUS" = "$4" ] && { [ $# -lt 5 ] || [ "$reason" = "$5" ]; }; then
    PASS=$((PASS+1)); echo "PASS  $1 -> $STATUS ${reason}"
  else
    FAIL=$((FAIL+1)); echo "FAIL  $1 -> $STATUS (expected $4 ${5:-}) body=$BODY"
  fi
}

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
# Three answers in a row: a single one passed while the gateway breaker was still recording startup failures, and
# the scenario then hit its fallback (issue #37).
for route in /api/users/me /api/events /api/registrations/me; do
  ROUTED=0
  for i in $(seq 1 120); do
    call GET "$route"
    case "$CODE" in 5*|000) ROUTED=0 ;; *) ROUTED=$((ROUTED+1)) ;; esac
    [ "$ROUTED" -ge 3 ] && break; sleep 1
  done
  [ "$ROUTED" -ge 3 ] || { echo "FAIL  the gateway does not route $route steadily (last: $CODE)"; exit 1; }
done

echo "== API documentation (Swagger UI login)"
# Each document declares the Keycloak scheme: the Swagger UI "Authorize" button signs in with it.
DOCS_OK=1
for service in user-service event-service registration-service; do
  AUTH=none call GET "/api-docs/$service"
  [[ "$CODE" = 200 && "$BODY" == *'"securitySchemes"'* && "$BODY" == *"/protocol/openid-connect/auth"* ]] \
    || { DOCS_OK=0; echo "      $service -> $CODE ${BODY:0:200}"; }
done
# What a browser does: /swagger-ui.html leads to a served UI, and Keycloak redirects back to a served page.
UI_STATUS=$(curl -s -o /dev/null -w '%{http_code}' -L "$G/swagger-ui.html")
AUTH=none call GET /v3/api-docs/swagger-config; REDIRECT_URL=$(field oauth2RedirectUrl)
REDIRECT_STATUS=$(curl -s -o /dev/null -w '%{http_code}' "$REDIRECT_URL")
[ "$UI_STATUS" = 200 ] && [ "$REDIRECT_STATUS" = 200 ] \
  || { DOCS_OK=0; echo "      swagger-ui.html -> $UI_STATUS, $REDIRECT_URL -> $REDIRECT_STATUS"; }
[ "$DOCS_OK" = 1 ] && { PASS=$((PASS+1)); echo "PASS  every API document declares the Keycloak OAuth2 scheme, Swagger UI and its OAuth2 redirect served"; } \
                   || { FAIL=$((FAIL+1)); echo "FAIL  Swagger UI login: scheme missing or page not served"; }

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
# The whole saga on an unknown event: registration-service, the outbox, Kafka, event-service, and back.
E=00000000-0000-0000-0000-000000000000
for i in $(seq 1 30); do register 1; [ "$CODE" = 202 ] && break; sleep 2; done
check "registration accepted, waiting for its seat" 202; R0=$(field id)
expect_answer "event-service answers through Kafka: unknown event refused" "$R0" 1 REJECTED EVENT_NOT_FOUND
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

echo "== Catalog filters"
# The search texts are percent-encoded by hand: call() passes the path as is. count: elements of a page.
count() { python3 -c 'import json,sys;print(len(json.loads(sys.argv[1])["content"]))' "$BODY" 2>/dev/null; }
AUTH=none call GET "/api/events?q=docker%20day&from=2027-03-15T18:00:00Z&ids=$E"
[ "$CODE" = 200 ] && [ "$(count)" = 1 ] && { PASS=$((PASS+1)); echo "PASS  search by text, start and ids finds the event"; } \
                                       || { FAIL=$((FAIL+1)); echo "FAIL  catalog search -> $CODE body=$BODY"; }
AUTH=none call GET "/api/events?q=docker%20day&from=2027-03-15T18:00:01Z"
[ "$CODE" = 200 ] && [ "$(count)" = 0 ] && { PASS=$((PASS+1)); echo "PASS  events starting earlier are left out"; } \
                                       || { FAIL=$((FAIL+1)); echo "FAIL  catalog from filter -> $CODE body=$BODY"; }
call GET "/api/events?organizer=me&status=DRAFT"; check "organizer lists their own drafts" 200
AUTH=$(attendee_token 1) call GET "/api/events?organizer=me"; check "own events need the ORGANIZER role" 403 ACCESS_DENIED
AUTH=none call GET "/api/events?from=tomorrow"; check "malformed filter" 400 VALIDATION_FAILED

# The web application (nginx) is the browser's single origin: it serves the Angular build and relays the gateway paths.
echo "== Web application (nginx, same origin as the API)"
W=http://127.0.0.1:4200
headers=$(curl -s -D - -o /tmp/e2e-web.html "$W/catalogue?q=paris")
grep -q '<app-root' /tmp/e2e-web.html && echo "$headers" | grep -qi "^content-security-policy: default-src 'self'" \
  && { PASS=$((PASS+1)); echo "PASS  application served on every client-side route, with its CSP"; } \
  || { FAIL=$((FAIL+1)); echo "FAIL  web application not served: $(echo "$headers" | head -1)"; }
G=$W AUTH=none call GET /bff/me; check "session endpoint relayed to the gateway" 401
G=$W AUTH=none call GET "/api/events/$E"; check "API relayed to the gateway" 200

# What a browser does, scripted: sign in on the Keycloak page through the gateway (BFF), then call the API with the
# session cookie only. On localhost: the address registered as redirect URI of the event-platform-bff client.
echo "== Browser session (BFF)"
B=http://localhost:8080
JAR=$(mktemp)
bff() { curl -s -c "$JAR" -b "$JAR" "$@"; }
LOGIN_PAGE=$(bff -o /dev/null -w '%{redirect_url}' "$B/oauth2/authorization/keycloak")
LOGIN_HTML=$(bff "$LOGIN_PAGE")
# The realm's login theme (keycloak/themes): its stylesheet, and the French title of the page.
[[ "$LOGIN_HTML" == *"/event-platform/css/event-platform.css"* && "$LOGIN_HTML" == *"Connexion"* ]] \
  && { PASS=$((PASS+1)); echo "PASS  login page served with the platform theme, in French"; } \
  || { FAIL=$((FAIL+1)); echo "FAIL  login page theme: ${LOGIN_HTML:0:300}"; }
LOGIN_ACTION=$(printf '%s' "$LOGIN_HTML" | python3 -c '
import html, re, sys
form = re.search(r"<form[^>]*kc-form-login[^>]*>", sys.stdin.read())
action = re.search(r"action=\"([^\"]+)\"", form.group(0)) if form else None
print(html.unescape(action.group(1)) if action else "")')
CALLBACK=$(bff -o /dev/null -w '%{redirect_url}' --data-urlencode "username=$E2E_USER" \
  --data-urlencode "password=$E2E_PASSWORD" "$LOGIN_ACTION")
bff -o /dev/null "$CALLBACK"   # code exchanged by the gateway: session cookie, then redirect to the front-end
ME=$(bff "$B/bff/me")
[[ "$ME" == *"\"email\":\"$E2E_USER\""* && "$ME" == *ORGANIZER* ]] \
  && { PASS=$((PASS+1)); echo "PASS  browser login through the gateway: session cookie, /bff/me with the realm roles"; } \
  || { FAIL=$((FAIL+1)); echo "FAIL  browser login (callback ${CALLBACK:0:80}) /bff/me: ${ME:0:200}"; }
SESSION_CODE=$(bff -o /dev/null -w '%{http_code}' "$B/api/registrations/me")
[ "$SESSION_CODE" = 200 ] \
  && { PASS=$((PASS+1)); echo "PASS  API call with the session cookie only: the gateway relays the access token"; } \
  || { FAIL=$((FAIL+1)); echo "FAIL  API call with the session cookie -> $SESSION_CODE"; }
NO_CSRF=$(bff -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -d '{}' "$B/api/registrations")
XSRF=$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$JAR" | tail -1)
LOGOUT=$(bff -X POST -H "X-XSRF-TOKEN: $XSRF" "$B/logout")
AFTER=$(bff -o /dev/null -w '%{http_code}' "$B/bff/me")
[[ "$NO_CSRF" = 403 && -n "$XSRF" && "$LOGOUT" == *"/protocol/openid-connect/logout?"* && "$AFTER" = 401 ]] \
  && { PASS=$((PASS+1)); echo "PASS  session write refused without CSRF token; logout ends the session and points to Keycloak"; } \
  || { FAIL=$((FAIL+1)); echo "FAIL  CSRF ($NO_CSRF, token ${XSRF:+present}) logout (${LOGOUT:0:120}) then /bff/me -> $AFTER"; }
rm -f "$JAR"

echo "== Business rules"
register 1; check "register attendee 1" 202; R1=$(field id)
register 1; check "duplicate attendee 1, while its seat is requested" 409 ALREADY_REGISTERED
expect_answer "attendee 1 confirmed" "$R1" 1 CONFIRMED
register 2; check "register attendee 2" 202; R2=$(field id)
expect_answer "attendee 2 confirmed" "$R2" 2 CONFIRMED
AUTH=$(attendee_token 2) call POST "/api/registrations/$R1/cancel"
check "another attendee cannot cancel the registration" 404 REGISTRATION_NOT_FOUND
call GET "/api/registrations/events/$E"; check "organizer lists the registrations of the event" 200
# Attendee names come from user-service (internal names route, PROFILE_READER service account).
names() { python3 -c 'import json,sys;print(",".join(sorted(str(r.get("attendeeName")) for r in json.loads(sys.argv[1])["content"])))' "$BODY" 2>/dev/null; }
call GET "/api/registrations/events/$E?status=CONFIRMED"
[ "$CODE" = 200 ] && [ "$(names)" = "Attendee 1,Attendee 2" ] \
  && { PASS=$((PASS+1)); echo "PASS  confirmed registrations carry the attendee names"; } \
  || { FAIL=$((FAIL+1)); echo "FAIL  attendee names -> $CODE body=$BODY"; }
call GET "/api/users/names?ids=$(python3 -c 'import uuid;print(uuid.uuid4())')"
check "internal names route closed at the edge" 403 ACCESS_DENIED
AUTH=$(kc_token event-platform "$E2E_CLIENT" "$E2E_OTHER" "$E2E_PASSWORD") call GET "/api/registrations/events/$E"
check "another organizer cannot list them" 403 NOT_EVENT_ORGANIZER
register 3; R3=$(field id); expect_answer "attendee 3 refused on the full event" "$R3" 3 REJECTED EVENT_FULL
AUTH=$(attendee_token 1) call POST "/api/registrations/$R1/cancel"; check "attendee 1 cancels their registration" 200
# Same event, same partition: the release of attendee 1 reaches event-service before this reservation.
register 3; check "register attendee 3 on the freed seat" 202; R3=$(field id)
expect_answer "attendee 3 confirmed" "$R3" 3 CONFIRMED
AUTH=$(attendee_token 3) call GET "/api/registrations/me?eventId=$E&status=CONFIRMED"
[ "$CODE" = 200 ] && [ "$(count)" = 1 ] && { PASS=$((PASS+1)); echo "PASS  attendee finds their registration to the event"; } \
                                       || { FAIL=$((FAIL+1)); echo "FAIL  registration to the event -> $CODE body=$BODY"; }
# Started in a known trace: its log line is looked up in Loki below.
LOG_TRACE_ID=$(openssl rand -hex 16)
TRACEPARENT="00-$LOG_TRACE_ID-$(openssl rand -hex 8)-01" AUTH=$(attendee_token 2) call POST "/api/registrations/$R2/cancel"
check "attendee 2 cancels their registration (1 seat left for the outage test, trace $LOG_TRACE_ID)" 200

# Before the outage: these checks are about the content of traces, on a stable platform.
# One request through gateway, registration-service and event-service (organizer lookup with the service
# account): started in a known trace, which must reach Tempo with the spans of the three services.
echo "== Distributed tracing and logs (OpenTelemetry, Grafana Tempo and Loki)"
TRACE_ID=$(openssl rand -hex 16)
TRACEPARENT="00-$TRACE_ID-$(openssl rand -hex 8)-01" call GET "/api/registrations/events/$E"
echo "      traced request -> $CODE, trace $TRACE_ID"
complete() { [[ "$TRACE" == *api-gateway* && "$TRACE" == *registration-service* && "$TRACE" == *event-service* ]]; }
# Spans are exported in batches and indexed by Tempo within seconds.
for i in $(seq 1 30); do
  TRACE=$(curl -s "http://127.0.0.1:3200/api/traces/$TRACE_ID"); complete && break; sleep 2
done
if complete; then
  PASS=$((PASS+1)); echo "PASS  one trace across api-gateway, registration-service and event-service"
else
  FAIL=$((FAIL+1)); echo "FAIL  trace $TRACE_ID incomplete in Tempo: ${TRACE:0:300}"
fi
# The SQL query of registration-service is a span of the same trace (JDBC observation).
for i in $(seq 1 5); do
  [[ "$TRACE" == *"from registrations"* ]] && break; sleep 2; TRACE=$(curl -s "http://127.0.0.1:3200/api/traces/$TRACE_ID")
done
[[ "$TRACE" == *"from registrations"* ]] && { PASS=$((PASS+1)); echo "PASS  SQL query span in the trace"; } \
                                       || { FAIL=$((FAIL+1)); echo "FAIL  no SQL query span in trace $TRACE_ID"; }
# An error answered by a service carries the id of its trace: what a client reports to support.
ERROR_TRACE_ID=$(openssl rand -hex 16)
TRACEPARENT="00-$ERROR_TRACE_ID-$(openssl rand -hex 8)-01" AUTH=none call GET /api/events/00000000-0000-0000-0000-000000000001
[ "$CODE" = 404 ] && [ "$(field traceId)" = "$ERROR_TRACE_ID" ] \
  && { PASS=$((PASS+1)); echo "PASS  problem response carries the trace id -> $CODE $(field code)"; } \
  || { FAIL=$((FAIL+1)); echo "FAIL  problem trace id (expected $ERROR_TRACE_ID) -> $CODE body=$BODY"; }

# The log of a request reaches Loki with the id of its trace: Grafana goes from a trace to its logs.
logs_of_trace() {
  curl -s -G http://127.0.0.1:3100/loki/api/v1/query_range --data-urlencode since=15m \
    --data-urlencode "query={service_name=\"registration-service\"} | trace_id=\"$LOG_TRACE_ID\""
}
for i in $(seq 1 15); do LOGS=$(logs_of_trace); [[ "$LOGS" == *"cancelled on event"* ]] && break; sleep 2; done
[[ "$LOGS" == *"cancelled on event"* ]] && { PASS=$((PASS+1)); echo "PASS  log of the request in Loki with its trace id"; } \
                                     || { FAIL=$((FAIL+1)); echo "FAIL  no log for trace $LOG_TRACE_ID in Loki: ${LOGS:0:300}"; }

# The dashboards are provisioned from the repository, and the metrics they read reach Prometheus with the names and
# labels they use. Series, not rates: metrics are pushed every 10 s, a rate needs two pushes (a fast run has one).
echo "== Dashboards (Grafana, Prometheus)"
DASHBOARDS=$(curl -s "http://127.0.0.1:3000/api/search?tag=event-platform")
metric_services() {
  curl -s -G http://127.0.0.1:3000/api/datasources/proxy/uid/prometheus/api/v1/query \
    --data-urlencode 'query=count by (service_name) (http_server_requests_milliseconds_count)' \
    | python3 -c 'import json,sys;print(len(json.load(sys.stdin)["data"]["result"]))' 2>/dev/null || echo 0
}
for i in $(seq 1 20); do SERVICES=$(metric_services); [ "$SERVICES" -ge 4 ] && break; sleep 2; done
[[ "$DASHBOARDS" == *event-platform-services* && "$DASHBOARDS" == *event-platform-resilience* \
   && "$DASHBOARDS" == *event-platform-business* && "$SERVICES" -ge 4 ]] \
  && { PASS=$((PASS+1)); echo "PASS  dashboards provisioned, HTTP metrics of $SERVICES services in Prometheus"; } \
  || { FAIL=$((FAIL+1)); echo "FAIL  dashboards or metrics missing (services: $SERVICES) ${DASHBOARDS:0:200}"; }

# Business metrics (registrations confirmed and rejected by reason, counted above) and the alert rules.
prom_series() {
  curl -s -G http://127.0.0.1:3000/api/datasources/proxy/uid/prometheus/api/v1/query --data-urlencode "query=$1" \
    | python3 -c 'import json,sys;print(len(json.load(sys.stdin)["data"]["result"]))' 2>/dev/null || echo 0
}
for i in $(seq 1 20); do
  CONFIRMED=$(prom_series 'registrations_confirmed_total{service_name="registration-service"}')
  FULL=$(prom_series 'registrations_rejected_total{service_name="registration-service",reason="EVENT_FULL"}')
  [ "$CONFIRMED" -ge 1 ] && [ "$FULL" -ge 1 ] && break; sleep 2
done
RULES=$(curl -s http://127.0.0.1:3000/api/prometheus/grafana/api/v1/rules)
[[ "$CONFIRMED" -ge 1 && "$FULL" -ge 1 && "$RULES" == *"Server errors above 5 %"* && "$RULES" == *"Circuit breaker rejecting calls"* ]] \
  && { PASS=$((PASS+1)); echo "PASS  business metrics (confirmed, rejected EVENT_FULL) and alert rules provisioned"; } \
  || { FAIL=$((FAIL+1)); echo "FAIL  business metrics ($CONFIRMED confirmed, $FULL EVENT_FULL) or alert rules: ${RULES:0:200}"; }
# Health of the seat saga: the outbox of both services and the registrations waiting for their seat, with the alerts.
for i in $(seq 1 20); do
  OUTBOX=$(prom_series 'messaging_outbox_pending')
  STALE=$(prom_series 'registrations_pending_stale{service_name="registration-service"}')
  [ "$OUTBOX" -ge 2 ] && [ "$STALE" -ge 1 ] && break; sleep 2
done
[[ "$OUTBOX" -ge 2 && "$STALE" -ge 1 && "$RULES" == *"Outbox not published"* \
   && "$RULES" == *"Registrations waiting for their seat"* && "$RULES" == *"Message sent to a dead letter topic"* ]] \
  && { PASS=$((PASS+1)); echo "PASS  saga metrics (outbox of $OUTBOX services, registrations waiting) and their alert rules"; } \
  || { FAIL=$((FAIL+1)); echo "FAIL  saga metrics (outbox: $OUTBOX series, waiting: $STALE) or alert rules"; }

# Registering does not depend on event-service any more: the request waits in Kafka, and is answered on its return.
echo "== Outage: event-service stopped"
docker compose --profile app stop event-service >/dev/null 2>&1
register 4; check "attendee 4 registers while event-service is down" 202; R4=$(field id)
sleep 5; AUTH=$(attendee_token 4) call GET "/api/registrations/$R4"
[ "$(field status)" = PENDING ] && { PASS=$((PASS+1)); echo "PASS  the registration waits for its seat during the outage"; } \
                                || { FAIL=$((FAIL+1)); echo "FAIL  registration during the outage -> body=$BODY"; }
call GET /api/events; echo "      gateway GET /api/events -> $CODE $(field code) instance=$(field instance)"

echo "== Recovery"
docker compose --profile app start event-service >/dev/null 2>&1
until [ "$(docker compose --profile app ps --format '{{.Health}}' event-service)" = healthy ]; do sleep 2; done
START=$(date +%s)
answered "$R4" 4 90
[ "$STATUS" = CONFIRMED ] \
  && { PASS=$((PASS+1)); echo "PASS  registration made during the outage confirmed $(( $(date +%s) - START ))s after recovery"; } \
  || { FAIL=$((FAIL+1)); echo "FAIL  registration made during the outage -> $STATUS body=$BODY"; }

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
call GET "/api/registrations/events/$E?status=CONFIRMED"
[ "$CODE" = 200 ] && [ "$(names)" = "Attendee 3,None" ] \
  && { PASS=$((PASS+1)); echo "PASS  a deleted profile leaves the registration listed without a name"; } \
  || { FAIL=$((FAIL+1)); echo "FAIL  name of a deleted profile -> $CODE body=$BODY"; }

# Moderation: an administrator sees every event, reads who registered, finds and erases a profile, cancels the event.
echo "== Administration"
ADMIN_TOKEN=$(kc_token event-platform "$E2E_CLIENT" "$E2E_ADMIN" "$E2E_PASSWORD")
call GET "/api/events?scope=all&ids=$E"; check "only administrators list every event" 403 ACCESS_DENIED
AUTH=$ADMIN_TOKEN call GET "/api/events?scope=all&ids=$E"
[ "$CODE" = 200 ] && [ "$(count)" = 1 ] && { PASS=$((PASS+1)); echo "PASS  administrator lists every event"; } \
                                       || { FAIL=$((FAIL+1)); echo "FAIL  every event -> $CODE body=$BODY"; }
AUTH=$ADMIN_TOKEN call GET "/api/registrations/events/$E?status=CONFIRMED"
[ "$CODE" = 200 ] && [ "$(names)" = "Attendee 3,None" ] \
  && { PASS=$((PASS+1)); echo "PASS  administrator reads the registrations of any event, with the names"; } \
  || { FAIL=$((FAIL+1)); echo "FAIL  registrations for the administrator -> $CODE body=$BODY"; }
AUTH=$ADMIN_TOKEN call GET "/api/users?q=$(attendee 3)"   # e-mail of this run: unique
A3=$(python3 -c 'import json,sys;c=json.loads(sys.argv[1])["content"];print(c[0]["id"] if len(c)==1 else "")' "$BODY" 2>/dev/null)
[ "$CODE" = 200 ] && [ -n "$A3" ] && { PASS=$((PASS+1)); echo "PASS  administrator finds a profile by e-mail"; } \
                                 || { FAIL=$((FAIL+1)); echo "FAIL  profile search -> $CODE body=$BODY"; }
AUTH=$ADMIN_TOKEN call DELETE "/api/users/$A3"; check "administrator erases the profile" 204

# Each run leaves its event cancelled: it no longer shows in the catalogue of published events.
echo "== Cleanup"
AUTH=$ADMIN_TOKEN call POST "/api/events/$E/cancel"; check "administrator cancels the event of an organizer" 200
AUTH=none call GET "/api/events?status=PUBLISHED&ids=$E"
[ "$CODE" = 200 ] && [ "$(count)" = 0 ] && { PASS=$((PASS+1)); echo "PASS  cancelled event left the catalogue"; } \
                                       || { FAIL=$((FAIL+1)); echo "FAIL  cancelled event still listed -> $CODE body=$BODY"; }

echo "== Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
