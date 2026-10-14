#!/usr/bin/env bash
# CRM showcase: Twenty CRM + Abada, lead to opportunity.
#
#   ./showcase.sh up              start Twenty, crm-mcp and crm-worker next to the dev
#                                 stack, seed the CRM, provision identities, deploy the process
#   ./showcase.sh lead <name>     start an instance: mongodb | zendesk | bakery
#   ./showcase.sh status          latest instances, their lane and opportunity link
#   ./showcase.sh logs            follow crm-mcp, crm-worker and the agent worker
#   ./showcase.sh compose <args>  docker compose with the showcase files, e.g. `compose ps`
#   ./showcase.sh down            stop the showcase containers (the dev stack keeps running)
#   ./showcase.sh reset           down + delete the CRM data and the generated .env
#
# Prerequisite: the Abada dev stack (./scripts/dev/up.sh) with an AI provider
# serving gemini-3.6-flash (Studio > Settings > AI Providers).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
ENV_DEV="${ABADA_ENV_FILE:-$ROOT/.env.dev}"
ENV_SHOWCASE="$HERE/.env"
REALM=abada-dev
TWENTY_WORKSPACE_ID=20202020-1c25-4d02-bf25-6aeccf7ea419   # the seeded "Apple" workspace

compose() {
  docker compose --project-directory "$ROOT" --env-file "$ENV_DEV" --env-file "$ENV_SHOWCASE" \
    -f "$ROOT/compose.yaml" -f "$ROOT/compose.dev.yaml" -f "$HERE/compose.yaml" \
    --profile agent --profile crm-apps "$@"
}

say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

env_value() { awk -v key="$1" 'index($0, key "=") == 1 { sub(/^[^=]*=/, ""); print; exit }' "$ENV_SHOWCASE"; }

set_env_value() {
  local tmp
  tmp="$(mktemp "$ENV_SHOWCASE.XXXXXX")"
  { grep -v "^$1=" "$ENV_SHOWCASE" || true; echo "$1=$2"; } >"$tmp"
  mv "$tmp" "$ENV_SHOWCASE"
  chmod 600 "$ENV_SHOWCASE"
}

ensure_secrets() {
  umask 077
  touch "$ENV_SHOWCASE"
  [[ -n "$(env_value TWENTY_APP_SECRET)" ]] || set_env_value TWENTY_APP_SECRET "$(openssl rand -hex 32)"
  [[ -n "$(env_value TWENTY_ENCRYPTION_KEY)" ]] || set_env_value TWENTY_ENCRYPTION_KEY "$(openssl rand -base64 32)"
  [[ -n "$(env_value CRM_MCP_TOKEN)" ]] || set_env_value CRM_MCP_TOKEN "$(openssl rand -hex 24)"
  [[ -n "$(env_value CRM_WORKER_CLIENT_SECRET)" ]] || set_env_value CRM_WORKER_CLIENT_SECRET "$(openssl rand -hex 32)"
}

wait_healthy() {
  local container="$1" waited=0
  printf '   waiting for %s' "$container"
  until [[ "$(docker inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null)" == healthy ]]; do
    sleep 5; waited=$((waited + 5)); printf '.'
    if (( waited > 600 )); then echo; echo "Error: $container is not healthy after 10 minutes" >&2; exit 1; fi
  done
  echo " ok"
}

twenty_sql() { docker exec "$(compose ps -q twenty-db)" psql -U twenty -d twenty -Atc "$1"; }

seed_twenty() {
  if [[ "$(twenty_sql "select count(*) from core.workspace")" != "0" ]]; then
    echo "   the CRM workspace exists, keeping its data"
  else
    echo "   seeding the demo workspace (about a minute)..."
    docker exec "$(compose ps -q twenty-server)" yarn command:prod workspace:seed:dev --light >/dev/null
  fi
  if [[ -z "$(env_value TWENTY_API_KEY)" ]]; then
    # Twenty's own key generator is a development command; this is a dev stack.
    local key
    key="$(docker exec -e NODE_ENV=development "$(compose ps -q twenty-server)" yarn command:prod \
      workspace:generate-api-key -w "$TWENTY_WORKSPACE_ID" -n abada-showcase 2>&1 \
      | sed -n 's/.*TOKEN:\(eyJ[A-Za-z0-9._-]*\).*/\1/p' | head -1)"
    [[ -n "$key" ]] || { echo "Error: could not generate a Twenty API key" >&2; exit 1; }
    set_env_value TWENTY_API_KEY "$key"
    echo "   generated the Twenty API key (stored in examples/showcase/crm-leads/.env)"
  fi
}

provision_identities() {
  local kc_container kc
  kc_container="$(compose ps -q keycloak)"
  kc() { docker exec "$kc_container" /opt/keycloak/bin/kcadm.sh "$@"; }
  kc config credentials --server http://127.0.0.1:8080 --realm master \
    --user "${KEYCLOAK_ADMIN_USERNAME:-admin}" --password "${KEYCLOAK_ADMIN_PASSWORD:-admin}" >/dev/null

  group_id() {
    kc get groups -r "$REALM" -q search="$1" --fields id,name --format csv --noquotes \
      | awk -F, -v name="$1" '$2 == name { print $1; exit }'
  }
  user_id() {
    kc get users -r "$REALM" -q username="$1" --fields id,username --format csv --noquotes \
      | awk -F, -v user="$1" '$2 == user { print $1; exit }'
  }

  # The `sales` group owns the review task (human-input assignees: [sales]).
  [[ -n "$(group_id sales)" ]] || kc create groups -r "$REALM" -s name=sales >/dev/null
  kc update "users/$(user_id alice)/groups/$(group_id sales)" -r "$REALM" -n >/dev/null
  echo "   alice is in group sales"

  # crm-worker: a confidential client whose service account is an Abada worker.
  local client secret
  secret="$(env_value CRM_WORKER_CLIENT_SECRET)"
  client="$(kc get clients -r "$REALM" -q clientId=crm-worker --fields id --format csv --noquotes 2>/dev/null || true)"
  if [[ -z "$client" ]]; then
    kc create clients -r "$REALM" -s clientId=crm-worker -s enabled=true -s publicClient=false \
      -s standardFlowEnabled=false -s serviceAccountsEnabled=true -s secret="$secret" >/dev/null
    client="$(kc get clients -r "$REALM" -q clientId=crm-worker --fields id --format csv --noquotes)"
    kc create "clients/$client/protocol-mappers/models" -r "$REALM" -s name=abada-audience \
      -s protocol=openid-connect -s protocolMapper=oidc-audience-mapper \
      -s 'config."included.client.audience"=abada-frontend' -s 'config."access.token.claim"=true' >/dev/null
    kc create "clients/$client/protocol-mappers/models" -r "$REALM" -s name=abada-groups \
      -s protocol=openid-connect -s protocolMapper=oidc-group-membership-mapper \
      -s 'config."claim.name"=groups' -s 'config."full.path"=false' -s 'config."access.token.claim"=true' >/dev/null
  else
    kc update "clients/$client" -r "$REALM" -s secret="$secret" >/dev/null
  fi
  kc update "users/$(user_id service-account-crm-worker)/groups/$(group_id abada-worker)" -r "$REALM" -n >/dev/null
  echo "   crm-worker client is in group abada-worker"
}

up() {
  [[ -f "$ENV_DEV" ]] || { echo "Error: $ENV_DEV not found; start the dev stack first (./scripts/dev/up.sh)" >&2; exit 1; }
  ensure_secrets

  say "Starting Twenty CRM and recreating the engine and agent worker with the showcase settings"
  compose up -d abada-engine abada-agent-worker twenty-db twenty-redis twenty-server twenty-worker
  wait_healthy "$(compose ps -q twenty-server)"
  wait_healthy "$(compose ps -q abada-engine)"

  say "Preparing the CRM"
  seed_twenty

  say "Provisioning identities in Keycloak"
  provision_identities

  say "Starting crm-mcp and crm-worker"
  compose up -d --build crm-mcp crm-worker
  wait_healthy "$(compose ps -q crm-mcp)"

  say "Creating the Abada project and deploying the process"
  CRM_MCP_TOKEN="$(env_value CRM_MCP_TOKEN)" python3 "$HERE/abada_project.py" setup

  cat <<EOF

Ready.
  Twenty CRM  http://crm.localhost       tim@apple.dev / tim@apple.dev
  Studio      http://studio.localhost    alice / alice (sign out and in again to pick up group 'sales')

Send a lead:   $0 lead mongodb    (or zendesk, bakery)
EOF
}

case "${1:-}" in
  up) up ;;
  lead) python3 "$HERE/abada_project.py" lead "${2:-}" ;;
  status) python3 "$HERE/abada_project.py" status ;;
  logs) compose logs -f --tail 20 crm-mcp crm-worker abada-agent-worker ;;
  compose) shift; compose "$@" ;;
  down) compose stop crm-worker crm-mcp twenty-worker twenty-server twenty-redis twenty-db ;;
  reset)
    compose rm -sf crm-worker crm-mcp twenty-worker twenty-server twenty-redis twenty-db
    for volume in twenty_db twenty_storage; do
      for id in $(docker volume ls -q --filter "label=com.docker.compose.volume=$volume"); do
        docker volume rm "$id" >/dev/null
      done
    done
    rm -f "$ENV_SHOWCASE"
    echo "Removed the CRM data and $ENV_SHOWCASE. Run '$0 up' to start over."
    ;;
  *) sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
