#!/usr/bin/env bash
# Starts, tests and stops Module 29's stack (four RustFS nodes and PostgreSQL 18.6) under the
# Compose project name seatwave-files, with its two ports on 127.0.0.1 only.
#
# Usage: scripts/stack.sh <up|down|test|status> [--run] [--yes]
#   default   a dry run: print the plan, change nothing (and need no Docker)
#   --run     do it; asks for confirmation on the terminal first
#   --yes     skip the question
#
#   up      generate the store's root key and the two database passwords into .secrets, start the
#           stack, wait until every service is healthy, create the four buckets (./gradlew
#           setupBuckets)
#   down    remove the project's containers, network and volumes (down -v) and the credentials of
#           the stack that is now gone, then check none is left
#   test    up, ./gradlew stackTest stackCoverage, then down, on every path
#   status  list the project's containers (read-only)
set -euo pipefail

readonly PROJECT="seatwave-files"
readonly WAIT_SECONDS=120
readonly S3_ENDPOINT="http://127.0.0.1:22900"
readonly DOCKER_TIMEOUT_SECONDS=10
# An S3 access key id of 20 characters and a secret key of 40, the lengths AWS uses.
readonly ACCESS_KEY_BYTES=10
readonly SECRET_KEY_BYTES=20
readonly PASSWORD_BYTES=24
readonly SECRET_FILES=(store-access-key store-secret-key postgres.password owner.password)

usage() {
  echo "usage: $0 <up|down|test|status> [--run] [--yes]" >&2
  exit 2
}

[[ $# -ge 1 ]] || usage
action="$1"
shift
mode="dry-run"
confirmed="no"
for option in "$@"; do
  case "$option" in
    --run) mode="run" ;;
    --yes) confirmed="yes" ;;
    *) usage ;;
  esac
done
case "$action" in up|down|test|status) ;; *) usage ;; esac

cd "$(dirname "$0")/.."
compose=(docker compose -p "$PROJECT")

echo "plan: $action the Compose project $PROJECT"
case "$action" in
  up) echo "  write .secrets/{${SECRET_FILES[*]}} if missing; ${compose[*]} up -d --wait; ./gradlew -q setupBuckets" ;;
  down) echo "  ${compose[*]} down -v --remove-orphans; remove the credentials; check nothing of $PROJECT is left" ;;
  test) echo "  up, then ./gradlew stackTest stackCoverage, then down -v (always)" ;;
  status) echo "  ${compose[*]} ps --all" ;;
esac
# The dry run comes before the Docker check: it changes nothing, so it needs nothing.
if [[ "$mode" == "dry-run" && "$action" != "status" ]]; then
  echo "dry run: nothing changed. Add --run to do it."
  exit 0
fi

# macOS has no timeout command, so the check runs in the background and is killed if it hangs.
# "docker info --format" exits 0 even when the engine does not answer (Docker 24.0.6), so the
# check also needs the version it printed.
docker_answers() {
  local answer_file="build/stack/docker-version"
  mkdir -p build/stack
  rm -f "$answer_file"
  docker info --format '{{.ServerVersion}}' > "$answer_file" 2> /dev/null &
  local checker=$!
  local waited
  for (( waited = 0; waited < DOCKER_TIMEOUT_SECONDS; waited++ )); do
    if ! kill -0 "$checker" 2> /dev/null; then
      wait "$checker" || return 1
      grep -q "[0-9]" "$answer_file"
      return $?
    fi
    sleep 1
  done
  kill "$checker" 2> /dev/null || true
  return 1
}
if ! command -v docker > /dev/null || ! docker_answers; then
  echo "Docker is not running (or does not answer within ${DOCKER_TIMEOUT_SECONDS} s); nothing was changed." >&2
  exit 1
fi
if [[ "$action" == "status" ]]; then
  "${compose[@]}" ps --all
  exit 0
fi
if [[ "$confirmed" != "yes" ]]; then
  read -r -p "Go ahead with '$action' for $PROJECT? [y/N] " answer < /dev/tty
  [[ "$answer" == "y" ]] || { echo "not confirmed; nothing changed" >&2; exit 1; }
fi

mkdir -p build/stack
audit() {
  echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $1" >> build/stack/audit.log
}

write_secret() {
  local file="$1" bytes="$2"
  if [[ ! -s ".secrets/$file" ]]; then
    # Readable by all, not owner-only: a Compose secret is a bind mount that keeps the file's owner
    # and mode, and the store runs as its own user (uid 10001) inside the container. Any account on
    # this machine can read these throwaway lab credentials; down deletes them.
    ( umask 022; openssl rand -hex "$bytes" > ".secrets/$file" )
  fi
}

secrets() {
  # Not under build/: "./gradlew clean" would delete the credentials while the stack uses them.
  mkdir -p .secrets
  write_secret store-access-key "$ACCESS_KEY_BYTES"
  write_secret store-secret-key "$SECRET_KEY_BYTES"
  write_secret postgres.password "$PASSWORD_BYTES"
  write_secret owner.password "$PASSWORD_BYTES"
}

up() {
  secrets
  # --wait returns when every healthcheck passes, or fails after the timeout.
  # Called in "up || status=$?", where set -e is off, so each failure returns on its own.
  "${compose[@]}" up -d --wait --wait-timeout "$WAIT_SECONDS" --quiet-pull || return 1
  audit "up: started $PROJECT"
  # A node is healthy as soon as it serves HTTP; the store answers S3 requests only once the four
  # nodes have formed their erasure set, with 503 until then. An anonymous request is refused with
  # 403 from that moment, so 403 is the sign that the store is ready.
  local waited answer
  for (( waited = 0; waited < WAIT_SECONDS; waited++ )); do
    answer="$(curl -s -o /dev/null -w '%{http_code}' "$S3_ENDPOINT/seatwave-tickets" || true)"
    [[ "$answer" == "403" ]] && break
    sleep 1
  done
  [[ "$answer" == "403" ]] || { echo "the store still answered $answer after $WAIT_SECONDS s" >&2; return 1; }
  ./gradlew -q setupBuckets || return 1
  audit "up: buckets and lifecycle rules in place"
}

down() {
  "${compose[@]}" down -v --remove-orphans || return 1
  # The credentials belonged to the stack just removed; the next up makes new ones.
  local file
  for file in "${SECRET_FILES[@]}"; do
    rm -f ".secrets/$file"
  done
  audit "down: removed $PROJECT and its credentials"
  # Each listing must succeed: an engine that does not answer lists nothing, which is not "clean".
  local left="" listed
  listed="$(docker ps -aq --filter "label=com.docker.compose.project=$PROJECT")" || return 1
  left+="$listed "
  listed="$(docker network ls -q --filter "label=com.docker.compose.project=$PROJECT")" || return 1
  left+="$listed "
  listed="$(docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT")" || return 1
  left+="$listed"
  if [[ -n "${left// /}" ]]; then
    echo "left behind by $PROJECT: $left" >&2
    return 1
  fi
  echo "nothing of $PROJECT is left"
}

case "$action" in
  up) up ;;
  down) down ;;
  test)
    status=0
    up || status=$?
    if [[ "$status" -eq 0 ]]; then
      ./gradlew stackTest stackCoverage || status=$?
    fi
    down || status=$?
    exit "$status"
    ;;
esac
