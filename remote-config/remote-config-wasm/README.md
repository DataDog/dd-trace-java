# remote-config-wasm

Remote configuration engine backed by the shared libdatadog WASM reactor
(`bindings/wasm/remote-config-poc` in the libdatadog repository), implementing the same
`ConfigurationPoller` contract as `remote-config-core`. The guest owns TUF state, request
building and change detection; this module supplies HTTP through the tracer's OkHttp client
and dispatches raw config bytes to the existing `ProductListener` model.

## Layout

- `libdatadog` — git submodule (branch `ban/rc-wasi-allow-better-java-use`) pinning the
  libdatadog revision the reactor is built from. Nothing from libdatadog is vendored into
  this module: the Java host library (`com.datadoghq.remoteconfig.poc`) compiles straight
  from `libdatadog/bindings/wasm/remote-config-poc/hosts/java/src`, and the AOT reactor
  classes are produced by the build.
- `datadog.remoteconfig.WasmConfigurationPoller` — the `ConfigurationPoller` implementation
  and the `Product`/`Capabilities` translation to the upstream guest enums.
- `datadog.remoteconfig.OkHttpTransport` — the `HttpTransport` bridge over the shared
  OkHttp client.

## Build

The Gradle build replicates the Java-relevant part of the crate's `scripts/build.mjs`
(the Node and .NET hosts are not built):

| Task | What it does |
| --- | --- |
| `submodulesUpdate` | `git submodule update --init libdatadog` |
| `wasmGenerate` | `node scripts/generate.mjs` — regenerates `src/generated.rs` from `abi.json` |
| `wasmCargoBuild` | `cargo build --target wasm32-wasip1 --release --locked` |
| `wasmOptJava` | `wasm-opt -O1` (Binaryen v133) — the Java-optimized wasm artifact |
| `wasmAotCompile` | Endive build-time compiler — `RemoteConfigMachine*` classes, `RemoteConfig.meta` and the generated `RemoteConfig` wrapper source |

`wasmAotCompile` output feeds the main source set, so a plain `./gradlew
:remote-config:remote-config-wasm:build` produces the full engine; no artifacts are checked
in. `-Pwasm=/path/to.wasm` AOT-compiles a pre-built reactor instead and skips the cargo
chain, for experiments.

### Toolchain requirements

- **rustup** with the channel from the crate's `rust-toolchain.toml` (currently 1.98.0)
  and the `wasm32-wasip1` target — `rustup toolchain install 1.98.0 --profile minimal
  --target wasm32-wasip1`.
- **Binaryen v133 exactly** (`wasm-opt`) — its output shape feeds the AOT compiler; the
  build fails on any other version. `WASM_OPT=/path/to/wasm-opt` overrides the binary.
- **LLVM clang/llvm-ar** for the wasm32-wasip1 C bits (`LLVM_BIN=/path/to/llvm/bin`
  overrides; Homebrew LLVM is the default on macOS).
- **Node** (>= 18) for `wasmGenerate`.
- The **Endive compiler CLI and runtime** (`run.endive:*:1.0.1`) resolve from Maven,
  like any other dependency.

`scripts/build.mjs` in the crate documents the upstream view of the same chain; when it
changes (flags, versions, ABI revisions), mirror the change here.

### Why wasm-opt -O1

`-Oz --converge` inlining grows wasm functions past the JVM's 64 KiB method limit, which
silently demotes them to the Endive interpreter — a large-config latency cliff discovered
in the Phase 0 benchmarks (see `PHASE0-WASM-RC-BENCHMARKS.md`). The Java artifact therefore
uses `-O1`, and upstream treats any interpreter fallback as a build error.

### Updating the submodule

```sh
git fetch --shallow-since=... # or: git -C libdatadog fetch origin ban/rc-wasi-allow-better-java-use
git -C libdatadog checkout <new-rev>
git add remote-config/remote-config-wasm/libdatadog
./gradlew :remote-config:remote-config-wasm:build  # cargo chain rebuilds incrementally
```

`wasmCargoBuild` tracks every Rust/C source and manifest under `libdatadog`, and cargo
keeps its own incremental state, so the rebuild cost follows what actually changed.

### CI

The cargo chain is on the critical path of the default agent build (`dd-java-agent`
shadowJars this module), so the shared `.gradle_build` job template provisions the
toolchain in `before_script` via `.gitlab/setup-wasm-remote-config-toolchain.sh`
(rustup with the pinned channel, Binaryen v133 with checksum verification, and
clang/llvm/node from the image's package manager when missing). The toolchain and
the crate's `target/` directory are cached per runner architecture. The module's
tests skip on Java 8 images (`testJvmConstraints` min 11) because the Endive host
runtime and the AOT machine classes are class-file v55.

## Status

Default engine since this branch: `SharedCommunicationObjects` builds
`WasmConfigurationPoller` whenever `dd.remote_config.wasm.enabled` is set (the
default), falling back to `DefaultConfigurationPoller` when the flag is explicitly
disabled or the JVM is older than Java 11 (the Endive host runtime and the AOT
machine classes are class-file v55). Known gaps: polling-rate hints are not
surfaced from the guest, and client state does not survive tracer restarts.
Parity tests against the original poller specification are pending (Phase 2).
