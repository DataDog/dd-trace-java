#!/usr/bin/env bash
# Installs the toolchain required to build remote-config/remote-config-wasm from the
# libdatadog submodule. Idempotent: caches under $TOOLCHAIN_ROOT (default
# $REPO_ROOT/.ci-wasm-toolchain, which CI caches per runner architecture) and exits
# quickly when everything is already in place.
#
# Installs:
#   - rustup with the channel from the crate's rust-toolchain.toml + wasm32-wasip1 target
#   - Binaryen wasm-opt v133 (exact version; the AOT compiler is sensitive to its output)
#   - clang / llvm-ar / node from the system package manager when missing (Linux CI images)
#
# Exports for the calling shell (Gradle's wasm tasks honor all of them):
#   RUSTUP_HOME, CARGO_HOME, WASM_OPT
#
# The script must be sourced (not executed) for the exports to reach the caller:
#   . .gitlab/setup-wasm-remote-config-toolchain.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CRATE="$REPO_ROOT/remote-config/remote-config-wasm/libdatadog/bindings/wasm/remote-config-poc"
TOOLCHAIN_ROOT="${TOOLCHAIN_ROOT:-$REPO_ROOT/.ci-wasm-toolchain}"
export RUSTUP_HOME="${RUSTUP_HOME:-$TOOLCHAIN_ROOT/rustup}"
export CARGO_HOME="${CARGO_HOME:-$TOOLCHAIN_ROOT/cargo}"
BINARYEN_VERSION=133

# --- rust toolchain (channel from the crate, never hardcode) -------------------
RUST_CHANNEL="$(sed -n 's/^channel = "\(.*\)"/\1/p' "$CRATE/rust-toolchain.toml")"
if [ -z "$RUST_CHANNEL" ]; then
  echo "ERROR: could not read rust channel from $CRATE/rust-toolchain.toml" >&2
  exit 1
fi

# generate.mjs formats the generated Rust through a nightly rustfmt it pins itself;
# parse the pin instead of hardcoding it, so this keeps working when upstream moves it
GEN_MJS="$CRATE/scripts/generate.mjs"
GENERATE_RUSTFMT_TOOLCHAIN="$(sed -n "s/.*--toolchain', '\([^']*\)', 'rustfmt.*/\1/p" "$GEN_MJS")"
if [ -z "$GENERATE_RUSTFMT_TOOLCHAIN" ]; then
  echo "ERROR: could not read the rustfmt toolchain pin from $GEN_MJS" >&2
  exit 1
fi

WASM_OPT="${WASM_OPT:-$TOOLCHAIN_ROOT/binaryen-$BINARYEN_VERSION/bin/wasm-opt}"

# Slim Debian images ship sha256sum, not perl's shasum (and vice versa on macOS)
verify_sha256() {
  local digest
  digest="$(awk '{print $1}' "$1.sha256")"
  echo "$digest  $1" | sha256sum --check --status 2>/dev/null \
    || echo "$digest  $1" | shasum -a 256 --check --status
}

# --- fast path: everything cached and working ----------------------------------
# The stamp covers the rust channel, the generate.mjs rustfmt pin and the Binaryen
# version, so any of them moving invalidates the cached provision.
stamp="$TOOLCHAIN_ROOT/complete-$RUST_CHANNEL-$GENERATE_RUSTFMT_TOOLCHAIN-binaryen$BINARYEN_VERSION"
if [ -f "$stamp" ] \
    && [ -x "$WASM_OPT" ] \
    && "$WASM_OPT" --version 2>/dev/null | grep -q "^wasm-opt version $BINARYEN_VERSION\b" \
    && (export PATH="$CARGO_HOME/bin:$PATH"
        rustup which --toolchain "$RUST_CHANNEL" rustc >/dev/null 2>&1 \
          && rustup which --toolchain "$GENERATE_RUSTFMT_TOOLCHAIN" rustfmt >/dev/null 2>&1); then
  export PATH="$CARGO_HOME/bin:$PATH"
  echo ">>> wasm toolchain already provisioned (rust $RUST_CHANNEL, rustfmt from $GENERATE_RUSTFMT_TOOLCHAIN, wasm-opt v$BINARYEN_VERSION)"
  return 0 2>/dev/null || exit 0
fi

# --- system prerequisites ------------------------------------------------------
need_apt_install() {
  local missing=()
  command -v clang >/dev/null 2>&1 || missing+=(clang)
  command -v llvm-ar >/dev/null 2>&1 || missing+=(llvm)
  command -v node >/dev/null 2>&1 || missing+=(nodejs)
  [ ${#missing[@]} -eq 0 ] && return 1 || true
  command -v apt-get >/dev/null 2>&1 || return 1
  echo ">>> installing missing system packages: ${missing[*]}"
  sudo apt-get update -qq || apt-get update -qq
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "${missing[@]}" \
    || DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "${missing[@]}"
}

# Install whatever the system package manager can provide; anything still missing
# fails below with a clear message (e.g. macOS relies on brew-installed tools).
need_apt_install || true

command -v clang >/dev/null 2>&1 && command -v llvm-ar >/dev/null 2>&1 || {
  # macOS fallback: Homebrew LLVM is the default the Gradle build uses, but it is
  # not on PATH
  if [ "$(uname -s)" = "Darwin" ] && [ -z "${LLVM_BIN:-}" ]; then
    case "$(uname -m)" in
      arm64) export LLVM_BIN="/opt/homebrew/opt/llvm/bin" ;;
      *) export LLVM_BIN="/usr/local/opt/llvm/bin" ;;
    esac
    [ -x "$LLVM_BIN/clang" ] && [ -x "$LLVM_BIN/llvm-ar" ] || {
      echo "ERROR: clang/llvm-ar not found. Install LLVM (brew install llvm) or set LLVM_BIN." >&2
      exit 1
    }
  else
    echo "ERROR: clang/llvm-ar not found. Install LLVM (brew install llvm on macOS) or set LLVM_BIN." >&2
    exit 1
  fi
}

NODE_MAJOR="$(node --version 2>/dev/null | sed 's/^v\([0-9]*\).*/\1/' || echo 0)"
if [ "${NODE_MAJOR:-0}" -lt 18 ]; then
  echo "ERROR: node >= 18 required for wasmGenerate, found: $(node --version 2>&1 || echo none)" >&2
  exit 1
fi

# --- rustup + pinned toolchain ---------------------------------------------------
if ! command -v rustup >/dev/null 2>&1; then
  echo ">>> installing rustup into $RUSTUP_HOME"
  mkdir -p "$RUSTUP_HOME" "$CARGO_HOME" "$TOOLCHAIN_ROOT"
  curl -fsSL --retry 5 --retry-delay 2 --retry-connrefused -o "$TOOLCHAIN_ROOT/rustup-init.sh" \
    https://sh.rustup.rs
  sh "$TOOLCHAIN_ROOT/rustup-init.sh" -y --no-modify-path --profile minimal \
    --default-toolchain "$RUST_CHANNEL" --target wasm32-wasip1
fi
export PATH="$CARGO_HOME/bin:$PATH"
rustup toolchain install "$RUST_CHANNEL" --profile minimal --target wasm32-wasip1
# the generate.mjs rustfmt pin (minimal profile excludes it, and rustup's auto-install
# of a missing toolchain does not request the component either)
rustup toolchain install "$GENERATE_RUSTFMT_TOOLCHAIN" --profile minimal --component rustfmt

# --- Binaryen v133 (per-platform release asset, sha256-verified) ---------------
if [ ! -x "$WASM_OPT" ]; then
  OS="$(uname -s)"
  ARCH="$(uname -m)"
  case "$OS/$ARCH" in
    Linux/x86_64)  ASSET="x86_64-linux" ;;
    Linux/aarch64) ASSET="aarch64-linux" ;;
    Darwin/arm64)  ASSET="arm64-macos" ;;
    Darwin/x86_64) ASSET="x86_64-macos" ;;
    *) echo "ERROR: no prebuilt Binaryen v$BINARYEN_VERSION asset for $OS/$ARCH; build it from source" >&2; exit 1 ;;
  esac
  TARBALL="$TOOLCHAIN_ROOT/binaryen-$BINARYEN_VERSION.tar.gz"
  URL="https://github.com/WebAssembly/binaryen/releases/download/version_${BINARYEN_VERSION}/binaryen-version_${BINARYEN_VERSION}-${ASSET}.tar.gz"
  echo ">>> installing Binaryen $BINARYEN_VERSION ($ASSET) into $TOOLCHAIN_ROOT"
  curl -fsSL --retry 5 --retry-delay 2 --retry-connrefused -o "$TARBALL" "$URL"
  curl -fsSL --retry 5 --retry-delay 2 --retry-connrefused -o "$TARBALL.sha256" "$URL.sha256"
  # the .sha256 sidecar carries the asset filename before the digest
  verify_sha256 "$TARBALL" || { echo "ERROR: Binaryen $BINARYEN_VERSION checksum mismatch" >&2; exit 1; }
  mkdir -p "$TOOLCHAIN_ROOT/binaryen-$BINARYEN_VERSION"
  tar -xzf "$TARBALL" -C "$TOOLCHAIN_ROOT/binaryen-$BINARYEN_VERSION" --strip-components=1
  rm -f "$TARBALL" "$TARBALL.sha256"
fi
export WASM_OPT

WASM_OPT_VERSION="$("$WASM_OPT" --version)"
if ! echo "$WASM_OPT_VERSION" | grep -q "^wasm-opt version $BINARYEN_VERSION\b"; then
  echo "ERROR: wasm-opt v$BINARYEN_VERSION required, found: $WASM_OPT_VERSION" >&2
  exit 1
fi

mkdir -p "$TOOLCHAIN_ROOT"
echo "$RUST_CHANNEL-$GENERATE_RUSTFMT_TOOLCHAIN-$BINARYEN_VERSION" > "$stamp"
echo ">>> wasm toolchain ready:"
echo "    export RUSTUP_HOME=$RUSTUP_HOME"
echo "    export CARGO_HOME=$CARGO_HOME"
echo "    export WASM_OPT=$WASM_OPT"
echo "    export PATH=$CARGO_HOME/bin:\$PATH"
