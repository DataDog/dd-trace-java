# remote-config-wasm

Remote configuration engine backed by the shared libdatadog WASM reactor
(`bindings/wasm/remote-config-poc` in the libdatadog repository), implementing the same
`ConfigurationPoller` contract as `remote-config-core`. The guest owns TUF state, request
building and change detection; this module supplies HTTP through the tracer's OkHttp client
and dispatches raw config bytes to the existing `ProductListener` model.

## Layout

- `libdatadog` — git submodule (branch `ban/rc-wasi-allow-better-java-use`) pinning the
  libdatadog revision the reactor is built from. Used only in source mode
  (`-PwasmFromSource=true`); the default build resolves the published AOT artifact instead
  (see below). Nothing from libdatadog is vendored into this module: in source mode the
  Java host library (`com.datadoghq.remoteconfig.poc`) compiles straight from
  `libdatadog/bindings/wasm/remote-config-poc/hosts/java/src`, and the AOT reactor classes
  are produced by the build.
- `maven-dist` — git submodule (the distribution repository's `gh-pages` branch) holding
  the published AOT artifact's Maven layout; the default build resolves
  `io.github.bantonsson:remote-config-wasm-aot` from this directory, so CI needs no maven
  egress. Updated alongside the libdatadog pin when a new artifact is published.
- `datadog.remoteconfig.WasmConfigurationPoller` — the `ConfigurationPoller` implementation
  and the `Product`/`Capabilities` translation to the upstream guest enums.
- `datadog.remoteconfig.OkHttpTransport` — the `HttpTransport` bridge over the shared
  OkHttp client.

## Build

By default the module resolves the **published AOT artifact** — the machine classes,
`RemoteConfig.meta` and the `com.datadoghq.remoteconfig.poc` host library — through the
`maven-dist` git submodule (the distribution repository's `gh-pages` branch, which holds
the Maven layout) with the GitHub Pages URL as fallback for clones without submodules;
both are declared in `gradle/repositories.gradle`:

```kotlin
implementation("io.github.bantonsson:remote-config-wasm-aot:0.1.0+b4106c4")
```

CI checks out submodules (`GIT_SUBMODULE_STRATEGY: normal`), so the artifact arrives with
the git fetch and the build needs no maven egress for this group. The jar carries a
provenance record (`remote-config-aot.json`: wasm sha256, libdatadog revision, Endive and
wasm-opt versions). The artifact is built and published from the libdatadog crate's Java
host (`bindings/wasm/remote-config-poc`), with the version encoding the libdatadog
revision (`<base>+<short-sha>`). To consume a new revision, publish the artifact, then
update the `maven-dist` submodule pin (`git submodule update --remote
remote-config/remote-config-wasm/maven-dist`) alongside the `publishedAot` coordinate in
`build.gradle.kts`.

`-PwasmFromSource=true` builds everything from the submodule instead, replicating the
Java-relevant part of the crate's `scripts/build.mjs` (the Node and .NET hosts are not
built):

| Task | What it does |
| --- | --- |
| `submodulesUpdate` | `git submodule update --init libdatadog` |
| `wasmGenerate` | `node scripts/generate.mjs` — regenerates `src/generated.rs` from `abi.json` |
| `wasmCargoBuild` | `cargo build --target wasm32-wasip1 --release --locked` |
| `wasmOptJava` | `wasm-opt` (Binaryen v133) — the AOT input artifact |
| `wasmAotCompile` | Endive build-time compiler — `RemoteConfigMachine*` classes, `RemoteConfig.meta` and the generated `RemoteConfig` wrapper source |

`wasmAotCompile` output feeds the main source set, so source mode produces the full
engine locally; no artifacts are checked in. `-Pwasm=/path/to.wasm` AOT-compiles a
pre-built reactor instead and skips the cargo chain, for experiments.

The source mode carries the `-Oz`/method-splitting experiment (Endive main-branch
`999-SNAPSHOT` from Maven Local, `--method-splitting`): its machine classes are not
identical to the published artifact, which is built with wasm-opt `-O1` and Endive 1.2.0.

### Toolchain requirements (source mode only)

- **rustup** with the channel from the crate's `rust-toolchain.toml` (currently 1.98.0)
  and the `wasm32-wasip1` target — `rustup toolchain install 1.98.0 --profile minimal
  --target wasm32-wasip1`.
- **Binaryen v133 exactly** (`wasm-opt`) — its output shape feeds the AOT compiler; the
  build fails on any other version. `WASM_OPT=/path/to/wasm-opt` overrides the binary.
- **LLVM clang/llvm-ar** for the wasm32-wasip1 C bits (`LLVM_BIN=/path/to/llvm/bin`
  overrides; Homebrew LLVM is the default on macOS).
- **Node** (>= 18) for `wasmGenerate`.
- The **Endive compiler CLI** — the source mode uses a local main-branch build
  (`999-SNAPSHOT`) for method splitting:
  `git clone https://github.com/bytecodealliance/endive && ./mvnw -pl build-time-compiler-cli,wasi -am install -DskipTests`.
  The Endive runtime itself (`run.endive:*`) resolves from Maven like any other
  dependency.

`scripts/build.mjs` in the crate documents the upstream view of the same chain; when it
changes (flags, versions, ABI revisions), mirror the change here.

### Why wasm-opt -O1 in the published artifact

`-Oz --converge` inlining grows wasm functions past the JVM's 64 KiB method limit, which
silently demotes them to the Endive interpreter — a large-config latency cliff (~3x
slower per poll at 1 MiB and above). The published artifact is therefore built from the
`-O1` reactor, and upstream treats any interpreter fallback as a build error. Endive's unreleased method splitting (`bytecodealliance/endive#270`) removes
the cliff — validated in the source mode with flat benchmarks and ~416 KB fewer machine
classes — so the artifact can move to `-Oz --converge` once an Endive release carries it.

### Updating the submodule (source mode)

```sh
git fetch --shallow-since=... # or: git -C libdatadog fetch origin <branch>
git -C libdatadog checkout <new-rev>
git add remote-config/remote-config-wasm/libdatadog
./gradlew :remote-config:remote-config-wasm:build -PwasmFromSource=true  # cargo chain rebuilds incrementally
```

`wasmCargoBuild` tracks every Rust/C source and manifest under `libdatadog`, and cargo
keeps its own incremental state, so the rebuild cost follows what actually changed.

The default build ignores the `libdatadog` submodule — the published artifact encodes
the libdatadog revision in its version (`<base>+<short-sha>`). To consume a new libdatadog
revision by default, publish the artifact from the libdatadog crate, bump the
`publishedAot` coordinate in `build.gradle.kts`, update the `maven-dist` submodule pin
(`git submodule update --remote remote-config/remote-config-wasm/maven-dist`), and update
the `libdatadog` pin to the same revision for source mode.

### CI

The default build resolves the published artifact and needs no wasm toolchain, so CI
provisions none. Source-mode builds (`-PwasmFromSource=true`) need the toolchain
described above and are run manually, not in the default CI matrix. The module's
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
