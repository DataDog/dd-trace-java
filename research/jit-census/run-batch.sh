#!/bin/bash
# Fetch a batch of libraries from Maven Central, run the census on them, and refresh the totals.
#
#   research/jit-census/run-batch.sh <batchN> group:artifact [group:artifact ...]
#
# Needs: bash, curl, python3, and a JDK 17+ `java` on PATH (or JAVA=/path/to/java).
# Downloads go to ${CENSUS_DL:-/tmp/ddjit/central}/<batchN>; outputs to research/jit-census/out-<batchN>/.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
batch=$1; shift
dl=${CENSUS_DL:-/tmp/ddjit/central}
java=${JAVA:-java}

asm="$dl/asm-9.8.jar"
if [ ! -f "$asm" ]; then
  mkdir -p "$dl"
  curl -sf -o "$asm" https://repo1.maven.org/maven2/org/ow2/asm/asm/9.8/asm-9.8.jar
fi

"$here/fetch.sh" "$dl/$batch" "$@"

mkdir -p "$here/out-$batch"
"$java" -Xmx4g -cp "$asm" "$here/Census.java" "$dl/$batch" "$here/out-$batch" > "$here/out-$batch/run.log" 2>&1 \
  || { echo "census failed, see out-$batch/run.log"; exit 1; }

# record exact coordinates (replace any earlier entry for this batch)
python3 -I - "$here/batches.txt" "$batch" "$dl/$batch" <<'EOF'
import os, re, sys
path, batch, root = sys.argv[1:]
coords = []
for dirpath, _, files in os.walk(root):
    for f in files:
        if f.endswith(".jar"):
            rel = os.path.relpath(dirpath, root).split(os.sep)
            coords.append(f"{rel[0]}:{rel[1]}:{rel[2]}")
text = open(path).read() if os.path.exists(path) else ""
text = re.sub(rf"(?ms)^# {re.escape(batch)}\n.*?(?=^# |\Z)", "", text)
text = text.rstrip("\n") + ("\n" if text.strip() else "") + f"# {batch}\n" + "\n".join(sorted(set(coords))) + "\n"
open(path, "w").write(text)
EOF

python3 -I "$here/aggregate.py" "$here"/out-batch* > "$here/totals-central.md"
echo "done: out-$batch/by-artifact.txt, totals-central.md, batches.txt"
