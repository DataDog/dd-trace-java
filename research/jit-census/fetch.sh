#!/bin/bash
# usage: fetch.sh <dest-root> group:artifact ...   (lays out <dest>/<group>/<artifact>/<version>/x/<artifact>-<version>.jar)
set -u
dest=$1; shift
for ga in "$@"; do
  g=${ga%%:*}; a=${ga##*:}; gp=${g//.//}
  meta=$(curl -sf "https://repo1.maven.org/maven2/$gp/$a/maven-metadata.xml") || { echo "FAIL meta $ga"; continue; }
  # Latest *stable* version: Central's <release> can be a pre-release (ognl 3.5.0-BETA10,
  # netty-codec-http 5.0.0.Alpha2), so pick the highest version that isn't alpha/beta/RC/milestone/snapshot.
  v=$(printf '%s' "$meta" | python3 -I -c '
import re, sys
vs = re.findall(r"<version>([^<]+)</version>", sys.stdin.read())
pre = re.compile(r"(?i)(alpha|beta|[.-]rc|[.-]cr\d|[.-]m\d|milestone|snapshot|preview|[.-]ea\b|incubat)")
def key(v):
    return [(0, int(p), "") if p.isdigit() else (1, 0, p.lower()) for p in re.split(r"[.\-_]", v)]
stable = [x for x in vs if not pre.search(x)]
stable = [x for x in stable if x[:1].isdigit()] or stable  # e.g. guava "r09"
print(max(stable or vs, key=key) if vs else "")')
  [ -n "$v" ] || { echo "FAIL version $ga"; continue; }
  d="$dest/$g/$a/$v/x"; mkdir -p "$d"
  if curl -sf -o "$d/$a-$v.jar" "https://repo1.maven.org/maven2/$gp/$a/$v/$a-$v.jar"; then
    echo "OK $ga:$v $(wc -c < "$d/$a-$v.jar") bytes"
  else
    echo "FAIL jar $ga:$v"; rm -rf "$dest/$g/$a/$v"
  fi
done
