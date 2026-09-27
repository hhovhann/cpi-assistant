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
# The model decides whether to call a tool, so the tool cases can vary between
# runs. A ❌ there is worth a second run before calling it a bug.

set -uo pipefail

BASE=${BASE:-http://localhost:8080}
DB_CONTAINER=${DB_CONTAINER:-cpi-assistant-pgvector}
DOWNLOAD_PAGE_PATTERN='Configure % Channel with ebMS3%'

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
  printf '  %s\n' "$(echo "$answer" | jq -r '"\(.toolCalls | length) tool call(s) · \(.millis / 1000 | floor) s · unverified: \(.unverifiedCitations)"')"
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
    "delete from cpi_chunks where metadata->>'title' like '$DOWNLOAD_PAGE_PATTERN'" > /dev/null 2>&1; then
  echo "Reset: removed the ebMS3 pages from the store, so the AS4 question has to download."
else
  echo "Reset skipped (no access to $DB_CONTAINER) — the download case may answer from the store."
fi

echo
echo "── Documentation: the store, and SAP Help on a miss ──"

check "1. Answered from the store (RAG, no tool)" \
  "How do I configure a JDBC adapter?" \
  "$(has '^Database: [0-9]') and $(has 'no tool') and $(lacks 'downloaded') and $no_unverified" \
  "store hit, no download, no tool, every citation verified"

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
  "$(lacks 'downloaded') and (.toolCalls | length == 0)" \
  "no download, no tool, a decline"

check "5. Technical, but not CPI: nothing is downloaded" \
  "How do I tune JVM garbage collection?" \
  "$(lacks 'downloaded')" \
  "no page matches well enough to download"

echo
echo "── Live tenant: the model decides to call tools ──"

check "6. Tool calls: problem messages, then the error" \
  "Why did Order_Sync fail today and how do I fix it?" \
  "$(has 'Tool: getProblemMessages') and $(has 'Tool: getErrorDetails') and (.answer | test(\"JDBC|timeout|Hikari\"; \"i\"))" \
  "getProblemMessages → getErrorDetails, and the JDBC timeout in the answer"

check "7. A retrying message counts as a problem" \
  "Is Payment_Status_Poll failing?" \
  "$(has 'Tool: getProblemMessages') and (.answer | test(\"retry|SFTP|refused\"; \"i\"))" \
  "the RETRY message (SFTP connection refused) is found"

check "8. Which iFlows are not running" \
  "Which iFlows are not running?" \
  "$(has 'Tool: listIflows') and (.answer | test(\"Material_Master_Load\"))" \
  "listIflows, and Material_Master_Load (status ERROR)"

check "9. A typo in the iFlow name" \
  "Why did Order_Synk fail today?" \
  "(.toolCalls | length > 0) and (.answer | test(\"Order_Sync\"))" \
  "a tenant tool is asked, and Order_Sync is suggested"

check "10. An iFlow that does not exist" \
  "Why did Unknown_Flow fail?" \
  "(.toolCalls | length > 0) and (.answer | test(\"Hikari|SQLTransient\") | not)" \
  "a tenant tool is asked, and no failure is invented"

echo
total=$((passed + failed))
if [ "$failed" -eq 0 ]; then
  echo "$(green "All $total checks passed.")"
else
  echo "$(red "$failed of $total checks failed.") Tool cases (6–10) depend on the model's choices: re-run once before calling it a bug."
fi
[ "$failed" -eq 0 ]
