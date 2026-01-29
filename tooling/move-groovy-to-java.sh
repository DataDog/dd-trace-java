#!/usr/bin/env bash
# move-groovy-to-java.sh
# Usage: ./move-groovy-to-java.sh <start-folder>
#
# Finds all directories matching */src/test/groovy under the start folder,
# ensures corresponding src/test/java exists, mirrors missing subdirs,
# moves files with `git mv` (preserving history) and commits the changes.

set -o pipefail

if [ $# -ne 1 ]; then
  echo "Usage: $0 <start-folder>"
  exit 2
fi

START_DIR="$1"

if [ ! -d "$START_DIR" ]; then
  echo "Error: start folder '$START_DIR' does not exist or is not a directory."
  exit 3
fi

# Resolve absolute path for START_DIR
START_DIR="$(cd "$START_DIR" && pwd)"

# Determine git repo root (must be inside a git repo)
REPO_ROOT="$(git -C "$START_DIR" rev-parse --show-toplevel 2>/dev/null || true)"
if [ -z "$REPO_ROOT" ]; then
  echo "Error: '$START_DIR' is not inside a git repository (or git not available)."
  exit 4
fi

echo "Repository root: $REPO_ROOT"
echo "Scanning under: $START_DIR"
echo

# Find all src/test/groovy directories (bash 3.2 compatible for macOS)
GROOVY_DIRS=()
while IFS= read -r -d '' dir; do
  GROOVY_DIRS+=("$dir")
done < <(find "$START_DIR" -type d -path '*/src/test/groovy' -print0)

if [ ${#GROOVY_DIRS[@]} -eq 0 ]; then
  echo "No 'src/test/groovy' directories found under $START_DIR. Nothing to do."
  exit 0
fi

echo "Found ${#GROOVY_DIRS[@]} groovy module(s)."
echo

# Track whether we made staged changes
CHANGES_MADE=0

for GROOVY_DIR in "${GROOVY_DIRS[@]}"; do
  echo "Processing: $GROOVY_DIR"

  # base is the parent '.../src/test'
  BASE_DIR="$(dirname "$GROOVY_DIR")"   # .../src/test
  JAVA_DIR="$BASE_DIR/java"

  # Ensure src/test/java exists
  if [ ! -d "$JAVA_DIR" ]; then
    echo "  Creating java dir: $JAVA_DIR"
    mkdir -p "$JAVA_DIR" || { echo "  Failed to create $JAVA_DIR"; continue; }
    # stage new directories (optional, git will stage movements below)
    git -C "$REPO_ROOT" add -- "$JAVA_DIR" >/dev/null 2>&1 || true
  else
    echo "  java dir exists: $JAVA_DIR"
  fi

  # Mirror missing subdirectories from groovy -> java
  echo "  Mirroring directory structure..."
  # find all directories under groovy dir
  while IFS= read -r -d '' subdir; do
    # relative path inside groovy tree (empty for the top-level groovy dir)
    rel="${subdir#$GROOVY_DIR}"
    # remove leading slash if any
    rel="${rel#/}"
    target_dir="$JAVA_DIR/$rel"
    if [ ! -d "$target_dir" ]; then
      echo "    mkdir -p $target_dir"
      mkdir -p "$target_dir" || { echo "    Failed to create $target_dir"; continue; }
      git -C "$REPO_ROOT" add -- "$target_dir" >/dev/null 2>&1 || true
    fi
  done < <(find "$GROOVY_DIR" -type d -print0)

  # Move .groovy files recursively using git mv
  echo "  Moving .groovy files..."
  while IFS= read -r -d '' groovy_file; do
    # relative path inside groovy tree
    rel_file="${groovy_file#$GROOVY_DIR/}"
    dest_file="$JAVA_DIR/${rel_file%.groovy}.java"

    # Ensure destination dir exists (should from the mirroring step, but double-check)
    dest_dir="$(dirname "$dest_file")"
    if [ ! -d "$dest_dir" ]; then
      echo "    (creating dest dir) mkdir -p '$dest_dir'"
      mkdir -p "$dest_dir" || { echo "    Failed to create $dest_dir"; continue; }
    fi

    if [ -e "$dest_file" ]; then
      echo "    SKIP: destination already exists: $dest_file"
      continue
    fi

    # Perform git mv (stages the rename). Use -f to overwrite if git allows (shouldn't be needed).
    echo "    git mv '$groovy_file' '$dest_file'"
    if git -C "$REPO_ROOT" mv -- "$groovy_file" "$dest_file"; then
      CHANGES_MADE=1
    else
      echo "    ERROR: git mv failed for: $groovy_file -> $dest_file"
      # attempt fallback: plain mv then git add/rm (less ideal but tries to continue)
      if mv -- "$groovy_file" "$dest_file"; then
        git -C "$REPO_ROOT" add -- "$dest_file" >/dev/null 2>&1 || true
        git -C "$REPO_ROOT" rm --cached --ignore-unmatch -- "$groovy_file" >/dev/null 2>&1 || true
        CHANGES_MADE=1
      else
        echo "    FATAL: fallback mv failed for $groovy_file"
      fi
    fi
  done < <(find "$GROOVY_DIR" -type f -name '*.groovy' -print0)

  echo
done

# If there are staged changes, commit them
if [ "$CHANGES_MADE" -eq 1 ]; then
  # check that there is something staged
  if git -C "$REPO_ROOT" diff --cached --quiet; then
    echo "No staged changes to commit."
  else
    echo "Committing changes..."
    git -C "$REPO_ROOT" commit --no-verify -m "Moving groovy to java to keep history"
    echo "Commit created."
  fi
else
  echo "No files moved; nothing to commit."
fi

echo "Done."
