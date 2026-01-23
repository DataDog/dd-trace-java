#!/usr/bin/env bash
set -euo pipefail

# Usage: cleanup-gradle-module.sh [--backup]
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
BACKUP_MODE=0
BACKUP_DIR="$SCRIPT_DIR/backup"

# Parse arguments
while [[ $# -gt 0 ]]; do
  case $1 in
    --backup)
      BACKUP_MODE=1
      shift
      ;;
    *)
      echo "Usage: $0 [--backup]" >&2
      exit 1
      ;;
  esac
done


if [[ $BACKUP_MODE -eq 1 ]]; then
  mkdir -p "$BACKUP_DIR"
fi

echo "Searching for old Gradle modules in $ROOT_DIR..."
find "$ROOT_DIR" -type d | sort -r | while read -r dir; do
  # Skip the root directory itself
  [ "$dir" = "$ROOT_DIR" ] && continue
  # Check if the directory contains only a 'build' directory
  if [ -d "$dir/build" ]; then
    # List all items except 'build'
    OTHERS=$(find "$dir" -mindepth 1 -maxdepth 1 ! -name 'build' ! -name '.*')
    if [ -z "$OTHERS" ]; then
      if [[ $BACKUP_MODE -eq 1 ]]; then
        # Move to backup dir, preserving relative path
        RELATIVE_PATH="${dir#$ROOT_DIR/}"
        DEST="$BACKUP_DIR/$RELATIVE_PATH"
        mkdir -p "$(dirname "$DEST")"
        mv "$dir" "$DEST"
        echo "Moved to backup: $dir -> $DEST"
      else
        rm -rf "$dir"
        echo "Removed: $dir"
      fi
    fi
  fi
done

# At the end, if in backup mode and backup folder exists, prompt to remove it
if [[ $BACKUP_MODE -eq 1 && -d "$BACKUP_DIR" ]]; then
  read -r -p "Remove backup folder '$BACKUP_DIR'? [y/N] " confirm
  case $confirm in
    [yY][eE][sS]|[yY])
      rm -rf "$BACKUP_DIR"
      echo "Removed backup folder."
      ;;
    *)
      echo "Backup folder retained at $BACKUP_DIR."
      ;;
  esac
fi
