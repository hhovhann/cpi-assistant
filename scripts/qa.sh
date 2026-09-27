#!/usr/bin/env bash
# Runs the QA checklist against a running app and checks the path of every answer.
#
#   ./scripts/qa.sh                       # app on http://localhost:8080
#   BASE=http://localhost:8081 ./scripts/qa.sh
#
# Needs: the app running (with LM Studio and pgvector), curl, jq, docker.
# Before the "download" cases it deletes the AS4 pages from the store, so the
# first ask must download them and the second must not — every run.
#

set -uo pipefail

BASE=${BASE:-http://localhost:8080}
DB_CONTAINER=${DB_CONTAINER:-cpi-assistant-pgvector}
# The AS4 pages: the overview and the ebMS3 channel pages (AS4 is ebMS3).
DOWNLOAD_PAGES_SQL="metadata->>'title' like 'AS4 %' or metadata->>'title' like '%ebMS3%'"

passed=0
failed=0

green() { printf '\033[32m%s\033[0m' "$1"; }
red() { printf '\033[31m%s\033[0m' "$1"; }

ask() {
  curl -sf -m 900 -G "$BASE/assist" --data-urlencode "question=$1"
}

# check <name> <question> <jq filter that must be true> <what it proves>
check() {
  local name=$1 question=$2 filter=$3 proves=$4
  printf '\n%s\n  Q: %s\n' "$name" "$question"
  local answer
  if ! answer=$(ask "$question"); then
    printf '  %s request failed — is the app running at %s?\n' "$(red '❌')" "$BASE"
    failed=$((failed + 1))
    return
  fi
  echo "$answer" | jq -r '.path[] | "  → " + .'
  printf '  %s\n' "$(echo "$answer" | jq -r '"\(.millis / 1000 | floor) s · unverified: \(.unverifiedCitations)"')"
  if echo "$answer" | jq -e "$filter" > /dev/null; then
    printf '  %s %s\n' "$(green '✅')" "$proves"
    passed=$((passed + 1))
  else
    printf '  %s expected: %s\n' "$(red '❌')" "$proves"
    printf '     answer: %s\n' "$(echo "$answer" | jq -r '.answer' | tr '\n' ' ' | cut -c1-300)"
    failed=$((failed + 1))
  fi
}

# jq helpers over .path (a list of strings)
has() { echo "any(.path[]; test(\"$1\"))"; }
lacks() { echo "(any(.path[]; test(\"$1\")) | not)"; }
no_unverified='(.unverifiedCitations | length == 0)'

if ! curl -sf -m 5 "$BASE/status" | jq -e '.ready' > /dev/null; then
  echo "$(red '❌') $BASE/status is not ready. Start the app and wait for 'Knowledge ready'."
  exit 1
fi
echo "App: $BASE — $(curl -s "$BASE/status")"

if docker exec "$DB_CONTAINER" psql -U cpi -d cpi -tAc \
    "delete from cpi_chunks where $DOWNLOAD_PAGES_SQL" > /dev/null 2>&1; then
  echo "Reset: removed the AS4 pages from the store, so the AS4 question has to download."
else
  echo "Reset skipped (no access to $DB_CONTAINER) — the download case may answer from the store."
fi

echo
echo "── The store, and SAP Help the first time ──"

check "1. Answered from the store" \
  "How do I configure a JDBC adapter?" \
  "$(has '^Database: [0-9]') and $(lacks 'downloaded') and $no_unverified" \
  "store hit, no download, every citation verified"

check "2. First time: downloaded from SAP Help and saved" \
  "How do I configure the AS4 receiver adapter?" \
  "$(has 'SAP Help: downloaded') and $no_unverified" \
  "a SAP page was downloaded and saved"

check "3. Second time: the same question comes from the store" \
  "How do I configure the AS4 receiver adapter?" \
  "$(has '^Database: [0-9]') and $(lacks 'downloaded')" \
  "no download — the page saved in 2 answered it"

check "4. Off-topic: nothing is downloaded" \
  "What is the capital of France?" \
  "$(lacks 'downloaded') and (.answer | test(\"not about|not related|I don.t know\"; \"i\"))" \
  "no download, a decline"

check "5. Technical, but not CPI: nothing is downloaded" \
  "How do I tune JVM garbage collection?" \
  "$(lacks 'downloaded')" \
  "no page matches well enough to download"

echo
echo "── Answer quality ──"

check "6. The exact page for a narrow question" \
  "How do I set up an SFTP receiver with known hosts?" \
  "$(has 'Known Hosts') and (.answer | test(\"known.hosts\"; \"i\")) and $no_unverified" \
  "the known hosts page is chosen, and the answer uses it"

check "7. Every answer cites a page it was given" \
  "How do I configure the Kafka receiver adapter?" \
  "any(.sources[]; .cited) and $no_unverified" \
  "at least one cited source, none unverified"

check "8. AS4 is not AS2" \
  "How do I configure the AS4 receiver adapter?" \
  "(.answer | test(\"AS4|ebMS\")) and (.sources | all(.title | test(\"AS2\") | not))" \
  "AS4 pages only, no AS2 page given"

echo
total=$((passed + failed))
if [ "$failed" -eq 0 ]; then
  echo "$(green "All $total checks passed.")"
else
  echo "$(red "$failed of $total checks failed.")"
fi
[ "$failed" -eq 0 ]
