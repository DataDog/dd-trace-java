#!/bin/bash
# usage: fetch.sh <dest-root> group:artifact ...   (lays out <dest>/<group>/<artifact>/<version>/x/<artifact>-<version>.jar)
set -u
dest=$1; shift
for ga in "$@"; do
  g=${ga%%:*}; a=${ga##*:}; gp=${g//.//}
  meta=$(curl -sf "https://repo1.maven.org/maven2/$gp/$a/maven-metadata.xml") || { echo "FAIL meta $ga"; continue; }
  v=$(printf '%s' "$meta" | sed -n 's:.*<release>\(.*\)</release>.*:\1:p' | head -1)
  [ -n "$v" ] || { echo "FAIL version $ga"; continue; }
  d="$dest/$g/$a/$v/x"; mkdir -p "$d"
  if curl -sf -o "$d/$a-$v.jar" "https://repo1.maven.org/maven2/$gp/$a/$v/$a-$v.jar"; then
    echo "OK $ga:$v $(wc -c < "$d/$a-$v.jar") bytes"
  else
    echo "FAIL jar $ga:$v"; rm -rf "$dest/$g/$a/$v"
  fi
done
