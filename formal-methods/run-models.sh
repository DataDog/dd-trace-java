#!/usr/bin/env bash

set -u

repo_root=$(cd "$(dirname "$0")/.." && pwd)
manifest="$repo_root/formal-methods/models.tsv"
tla_jar=${TLA2TOOLS_JAR:-/tmp/tla2tools-1.7.4.jar}
metadata_root=${TLC_METADATA_ROOT:-/tmp/dd-trace-java-tlc/tier1}
failures=0

if [[ ! -f "$tla_jar" ]]; then
  echo "TLA+ tools jar not found: $tla_jar" >&2
  echo "Set TLA2TOOLS_JAR or install version 1.7.4 at /tmp/tla2tools-1.7.4.jar." >&2
  exit 2
fi

contract_output=$(mktemp "${TMPDIR:-/tmp}/dd-trace-tla-contracts.XXXXXX")
(
  cd "$repo_root/formal-methods/contracts" || exit 1
  java -cp "$tla_jar" tla2sany.SANY OwnershipContracts.tla BarrierContracts.tla
) >"$contract_output" 2>&1
contract_status=$?

if [[ $contract_status -ne 0 ]] || grep -Eq "Semantic errors:|Fatal errors:|Parse Error|Lexical error" "$contract_output"; then
  echo "FAIL  shared-contracts (parse or semantic error)" >&2
  sed -n '1,160p' "$contract_output" >&2
  failures=$((failures + 1))
else
  echo "PASS  shared-contracts"
fi
rm -f "$contract_output"

while IFS=$'\t' read -r id expectation directory module invariant; do
  if [[ -z "$id" || "$id" == \#* ]]; then
    continue
  fi

  model_dir="$repo_root/formal-methods/$directory"
  output_file=$(mktemp "${TMPDIR:-/tmp}/dd-trace-tlc-${id}.XXXXXX")
  metadata_dir="$metadata_root/$id"
  mkdir -p "$metadata_dir"

  java -XX:+UseParallelGC -jar "$tla_jar" \
    -config "$model_dir/$module.cfg" \
    -metadir "$metadata_dir" \
    "$model_dir/$module.tla" >"$output_file" 2>&1
  status=$?

  if [[ "$expectation" == "pass" ]]; then
    if [[ $status -eq 0 ]] && grep -q "Model checking completed. No error has been found." "$output_file"; then
      echo "PASS  $id"
    else
      echo "FAIL  $id (expected model-check success)" >&2
      sed -n '1,160p' "$output_file" >&2
      failures=$((failures + 1))
    fi
  elif [[ "$expectation" == "violation" ]]; then
    if grep -q "Invariant $invariant is violated" "$output_file"; then
      echo "XFAIL $id ($invariant)"
    else
      echo "FAIL  $id (expected violation of $invariant)" >&2
      sed -n '1,160p' "$output_file" >&2
      failures=$((failures + 1))
    fi
  else
    echo "FAIL  $id (unknown expectation: $expectation)" >&2
    failures=$((failures + 1))
  fi

  rm -f "$output_file"
done < "$manifest"

if [[ $failures -ne 0 ]]; then
  echo "$failures model expectation(s) failed." >&2
  exit 1
fi

echo "All Tier-1 model expectations matched."
