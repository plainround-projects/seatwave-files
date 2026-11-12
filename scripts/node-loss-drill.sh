#!/usr/bin/env bash
# The node-loss drill (Module 29, Section 8, Command 5) against the four-node store of compose.yaml.
#
# Usage: scripts/node-loss-drill.sh [--size small|large|huge] [--runs N] [--run] [--yes]
#   --size small  200 tickets of 200,000 bytes and 3 posters of 4,200,000 bytes: 52,600,000 bytes,
#                 the lab's data set (default)
#          large  200 tickets and 50 posters of 20,000,000 bytes: 1,040,000,000 bytes
#          huge   200 tickets and 200 posters of 20,000,000 bytes: 4,040,000,000 bytes, so the
#                 heal lasts long enough for a rate (needs about 8 GB on Docker's disk)
#   --runs N      repeat the drill N times (default 1), each on a fresh stack
#   default       a dry run: print the plan, change nothing; --run does it, --yes skips the question
#
# Each run: start the stack (scripts/stack.sh up), write the data with SHA-256 values recorded,
# read it all back; remove node 3 with its volume (a lost server and its drive) and read again;
# start node 3 on a blank volume and time the heal, until node 3's bucket folders hold as many
# files and bytes as node 4's; read again; then remove node 1 as well (a second loss, after the
# heal) and read again; then remove node 2 too, leaving 2 of 4 nodes, and try a read and a write.
# The stack is brought down (down -v) on every path. A run in which any of the first four reads
# finds fewer matching objects than it wrote fails the drill (exit status 3).
set -euo pipefail
readonly PROJECT="seatwave-files"
readonly TICKETS=200
readonly TICKET_BYTES=200000
readonly POLL_SECONDS=0.5
readonly HEAL_TIMEOUT_SECONDS=900
readonly BUCKET="seatwave-tickets"

usage() {
  echo "usage: $0 [--size small|large|huge] [--runs N] [--run] [--yes]" >&2
  exit 2
}

size="small"
runs=1
execute="no"
confirmed="no"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --size)
      [[ $# -ge 2 && ( "$2" == "small" || "$2" == "large" || "$2" == "huge" ) ]] || usage
      size="$2"
      shift
      ;;
    --runs)
      [[ $# -ge 2 && "$2" =~ ^[1-9][0-9]?$ ]] || { echo "--runs takes a number from 1 to 99" >&2; exit 2; }
      runs=$((10#$2))
      shift
      ;;
    --run) execute="yes" ;;
    --yes) confirmed="yes" ;;
    *) usage ;;
  esac
  shift
done
case "$size" in
  small) posters=3; poster_bytes=4200000 ;;
  large) posters=50; poster_bytes=20000000 ;;
  huge) posters=200; poster_bytes=20000000 ;;
esac
total_bytes=$(( TICKETS * TICKET_BYTES + posters * poster_bytes ))
objects=$(( TICKETS + posters ))

cd "$(dirname "$0")/.."
compose=(docker compose -p "$PROJECT")
echo "plan: $runs run(s) of the node-loss drill on $PROJECT, $objects objects, $total_bytes bytes each run"
echo "  each run: stack up, write, read; lose node 3; heal it (timed); read; lose node 1; read;"
echo "  lose node 2 (2 of 4 left): read and write; stack down -v"
if [[ "$execute" != "yes" ]]; then
  echo "dry run: nothing changed. Add --run to do it."
  exit 0
fi
if [[ "$confirmed" != "yes" ]]; then
  read -r -p "Go ahead with $runs drill run(s) on $PROJECT? [y/N] " answer < /dev/tty
  [[ "$answer" == "y" ]] || { echo "not confirmed; nothing changed" >&2; exit 1; }
fi

mkdir -p build/drill
# rc keeps its alias in a home folder; the containers' root file system is read-only, /tmp is not.
in_node() {
  local node="$1" script="$2"
  "${compose[@]}" exec -T -e HOME=/tmp "$node" sh -c \
    'rc alias set l http://127.0.0.1:9000 "$(cat /run/secrets/store_access_key)" "$(cat /run/secrets/store_secret_key)" > /dev/null && '"$script"
}

# Writes the data from node 1 and prints "<sha256> <key>" per object, sorted.
write_data() {
  in_node node1 "
    for number in \$(seq 1 $TICKETS); do
      head -c $TICKET_BYTES /dev/urandom > /tmp/object
      key=tickets/1/ticket-\$number.pdf
      rc put -q /tmp/object l/$BUCKET/\$key && echo \"\$(sha256sum /tmp/object | cut -c1-64) \$key\"
    done
    for number in \$(seq 1 $posters); do
      head -c $poster_bytes /dev/urandom > /tmp/object
      key=posters/1/poster-\$number.jpg
      rc put -q /tmp/object l/$BUCKET/\$key && echo \"\$(sha256sum /tmp/object | cut -c1-64) \$key\"
    done
    rm -f /tmp/object" | sort
}

# Reads every recorded key through one node and prints how many objects match their SHA-256.
read_back() {
  local node="$1" label="$2" read_file="build/drill/read-$1.txt"
  cut -d' ' -f2 build/drill/expected.txt | in_node "$node" '
    while read -r key; do
      echo "$(rc object show "l/'"$BUCKET"'/$key" 2> /dev/null | sha256sum | cut -c1-64) $key"
    done' 2> /dev/null | sort > "$read_file" || true
  local matched
  matched="$(comm -12 build/drill/expected.txt "$read_file" | wc -l | tr -d ' ')"
  echo "$label: $matched of $objects objects read back with their SHA-256" >&2
  echo "$matched"
}

# Files and kilobytes in the bucket folders of one node's drive (not the store's own .rustfs.sys).
drive_usage() {
  "${compose[@]}" exec -T "$1" sh -c \
    'echo "$(find /data/seatwave-* -type f | wc -l) $(du -sk /data/seatwave-* | awk "{total += \$1} END {print total}")"'
}

lose() {
  "${compose[@]}" rm -sf "$1" > /dev/null 2>&1
  docker volume rm "${PROJECT}_drive${1#node}" > /dev/null
}

now() {
  python3 -I -c 'import time; print(f"{time.time():.3f}")'
}

drill_run() {
  local run="$1" failed=0 matched
  scripts/stack.sh up --run --yes > build/drill/up.txt 2>&1 || { cat build/drill/up.txt >&2; return 1; }
  write_data > build/drill/expected.txt
  [[ "$(wc -l < build/drill/expected.txt | tr -d ' ')" == "$objects" ]] || { echo "the write stored fewer than $objects objects" >&2; return 1; }
  matched="$(read_back node2 "  before the loss (4 of 4 nodes)")"; [[ "$matched" == "$objects" ]] || failed=1
  lose node3
  matched="$(read_back node2 "  node 3 and its drive lost (3 of 4)")"; [[ "$matched" == "$objects" ]] || failed=1
  local started healthy finished usage reference elapsed
  started="$(now)"
  "${compose[@]}" up -d --wait --wait-timeout 120 node3 > /dev/null 2>&1
  healthy="$(now)"
  reference="$(drive_usage node4)"
  finished=""
  while python3 -I -c "import sys,time; sys.exit(0 if time.time() - $started < $HEAL_TIMEOUT_SECONDS else 1)"; do
    usage="$(drive_usage node3)"
    if [[ "$usage" == "$reference" ]]; then
      finished="$(now)"
      break
    fi
    sleep "$POLL_SECONDS"
  done
  if [[ -z "$finished" ]]; then
    echo "  heal: node 3 did not reach node 4's $reference (files, KB) within $HEAL_TIMEOUT_SECONDS s; it had $usage" >&2
    failed=1
  else
    elapsed="$(python3 -I -c "print(f'{$finished - $healthy:.1f}')")"
    echo "  heal: node 3 healthy $(python3 -I -c "print(f'{$healthy - $started:.1f}')") s after its start; its drive matched node 4's (${reference% *} files, ${reference#* } KB) $elapsed s later" >&2
    echo "run $run: $size, $total_bytes bytes, $objects objects, ${reference#* } KB per drive, heal $elapsed s" >> build/drill/heal-times.txt
  fi
  matched="$(read_back node3 "  after the heal, read through node 3")"; [[ "$matched" == "$objects" ]] || failed=1
  lose node1
  matched="$(read_back node3 "  node 1 lost as well, after the heal (3 of 4)")"; [[ "$matched" == "$objects" ]] || failed=1
  lose node2
  read_back node3 "  node 2 lost too (2 of 4)" > /dev/null
  if in_node node3 'head -c 1000 /dev/urandom > /tmp/write && rc put -q /tmp/write l/'"$BUCKET"'/incoming/write-test.bin' > /dev/null 2>&1; then
    echo "  a write with 2 of 4 nodes: accepted" >&2
  else
    echo "  a write with 2 of 4 nodes: refused" >&2
  fi
  return "$failed"
}

# A drill stopped half-way (an error, Ctrl-C) still removes its stack and volumes.
trap 'scripts/stack.sh down --run --yes > /dev/null 2>&1 || true' EXIT
status=0
rm -f build/drill/heal-times.txt
for (( run = 1; run <= runs; run++ )); do
  echo "run $run of $runs ($(uptime | sed 's/.*load averages*: //'), 1, 5 and 15-minute load)"
  run_status=0
  drill_run "$run" || run_status=$?
  scripts/stack.sh down --run --yes > build/drill/down.txt 2>&1 || { cat build/drill/down.txt >&2; status=1; }
  if [[ "$run_status" -ne 0 ]]; then
    echo "run $run: FAILED" >&2
    status=3
  fi
done
cat build/drill/heal-times.txt 2> /dev/null || true
exit "$status"
