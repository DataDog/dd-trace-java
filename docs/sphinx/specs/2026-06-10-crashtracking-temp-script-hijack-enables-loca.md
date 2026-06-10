# Spec: crashtracking-temp-script-hijack-enables-loca

## Problem

Crashtracking is enabled by default. When autoconfig is active the agent sets
HotSpot `OnError` / `OnOutOfMemoryError` to execute `dd_crash_uploader` /
`dd_oome_notifier` scripts located under a **predictable** temp path derived by
`TempLocationManager`: `<java.io.tmpdir>/ddprof_<user>/pid_<pid>/`.

The path is predictable and the agent does not establish a trust boundary on
directories or scripts it reuses:

- `TempLocationManager.createTempDir(Path)` calls `Files.createDirectories(...)`
  which is a no-op when the directory already exists. On the existing-directory
  path it never validates that the directory is owned by the current user or
  that its permissions are `0700`. POSIX permissions are only inspected lazily
  inside a `catch (IOException)` block, i.e. only when creation *fails* — never
  when an attacker-owned directory already satisfies traversal so creation
  silently succeeds.
- `CrashUploaderScriptInitializer.copyCrashUploaderScript` and
  `OOMENotifierScriptInitializer.copyOOMEscript` create the script directory
  with world-readable/world-writable/world-executable bits
  (`setWritable(true, false)` / `setReadable(true, false)` /
  `setExecutable(true, false)`; the `false` second arg means "all users") and
  do **not** validate an already-existing directory's ownership.
- Both initializers deliberately skip writing the script when it already exists
  (`if (!scriptFile.exists())`). A pre-planted attacker-owned script is reused
  verbatim.

A local attacker who can pre-create or race an attacker-owned
`<tmp>/ddprof_<serviceUser>/pid_<pid>/` directory and plant
`dd_crash_uploader.sh` or `dd_oome_notifier.sh` causes the JVM crash/OOME
handler to execute attacker-controlled code as the instrumented service user
when the process crashes or hits OOME. This is local code execution / privilege
pivot.

## Correct behaviour

The agent must establish a trust boundary on every directory and script it
reuses under the predictable temp tree on POSIX file systems:

1. When `TempLocationManager` resolves/creates a temp directory and the
   directory (or any ancestor it creates under `baseTempDir`, plus
   `baseTempDir` itself) already exists, it must verify the directory is:
   - owned by the current user (`java.nio.file.Files.getOwner` equals the
     owner of a freshly created reference path, or equals the JVM user
     principal), and
   - not group- or world-accessible (effective permissions `0700`; reject if
     any of `GROUP_*` / `OTHERS_*` bits are set).
   If validation fails the manager must refuse to use the directory: throw
   `IllegalStateException` (consistent with the existing failure mode in
   `createTempDir`) and emit a `ProfilerFlareLogger` message naming the
   offending path and its permissions/owner. It must not silently proceed.

2. Newly created directories must continue to be created with `0700`
   (`rwx------`) — already the case via the `PosixFilePermissions` attribute —
   and must not be widened afterwards.

3. `CrashUploaderScriptInitializer` and `OOMENotifierScriptInitializer` must,
   on POSIX file systems:
   - not create or reuse script directories with group/world bits — drop the
     world-wide `setReadable/​setWritable/​setExecutable(.., false)` widening;
     restrict to owner-only;
   - before reusing a pre-existing script directory, verify it is owned by the
     current user with `0700` perms, and refuse (return `false`, log telemetry)
     otherwise;
   - before reusing a pre-existing script file, verify it is owned by the
     current user and not group/world-writable; if it fails validation, refuse
     to use it (do not execute an untrusted script) rather than silently
     trusting it.

4. Non-POSIX file systems (e.g. Windows) retain current behaviour; ownership /
   permission checks are POSIX-gated, matching the existing `isPosixFs`
   branching.

## Constraints

- `internal-api` `TempLocationManager` must stay free of new external
  dependencies; use only `java.nio.file` (`Files.getOwner`,
  `Files.getPosixFilePermissions`, `PosixFilePermission`,
  `UserPrincipal`) already imported/available.
- Preserve existing public API: `getTempDir(...)`, `getInstance(...)`,
  package-visible test constructors and `@VisibleForTesting` hooks must keep
  their signatures so existing tests compile.
- Failure mode for an untrusted temp dir is `IllegalStateException` with a
  `ProfilerFlareLogger` message, matching the current `createTempDir` contract.
- Script initializers must continue to degrade gracefully (log via
  `SEND_TELEMETRY`, `return`/`return false`) rather than throw — matching their
  current contract; the new behaviour is "refuse to use untrusted path", not
  "crash the agent".
- google-java-format / Spotless enforced. No `java.util.logging`, `javax.management`
  additions in bootstrap-reachable code.

## Scope

### Primary fixes

- `internal-api/src/main/java/datadog/trace/util/TempLocationManager.java`
  (`createTempDir`, ~L425-501; also `createDirStructure` L420-423 and the
  `getTempDir(Path, boolean)` reuse path L350-357) — defect class:
  *missing ownership/permission validation on pre-existing directory
  (predictable-path TOCTOU / hijack)* — add POSIX ownership+`0700` validation
  for any reused/pre-existing directory under `baseTempDir` (including
  `baseTempDir`), rejecting non-conforming dirs with `IllegalStateException` +
  flare log.

### Auto-expanded sibling fixes

- `dd-java-agent/agent-crashtracking/src/main/java/datadog/crashtracking/CrashUploaderScriptInitializer.java`
  (`copyCrashUploaderScript` L68-106, `writeCrashUploaderScript` L108-127) —
  defect class: *script/dir hijack via world-wide perms + reuse of pre-existing
  unvalidated file*.
  - evidence: L80-82 set dir world-readable/writable/executable; L91 only
    checks `canWrite`; L111 `if (!scriptFile.exists())` reuses any pre-existing
    script without ownership check.
  - reasoning:
    FLOW: agent autoconfig → `Initializer.initialize` → `initializeCrashUploader`
    → `CrashUploaderScriptInitializer.initialize` → `copyCrashUploaderScript`
    on the predictable `tempDir`.
    PRECONDITION: attacker can create `<tmp>/ddprof_<user>/pid_<pid>/` or the
    script before the agent and is a local user on the host.
    REACHABLE: yes — enabled by default; OnError points at the predictable path.
    CONCLUSION: critical — pre-planted `dd_crash_uploader.sh` executes as the
    service user on crash; world-writable dir creation also lets others tamper.

- `dd-java-agent/agent-crashtracking/src/main/java/datadog/crashtracking/OOMENotifierScriptInitializer.java`
  (`copyOOMEscript` L58-103) —
  defect class: *script/dir hijack via world-wide perms + reuse of pre-existing
  unvalidated file*.
  - evidence: L82-84 set dir world-readable/writable/executable; L65-73 reuse
    existing dir checking only `canWrite`; L88-90 `if (!scriptFile.exists())`
    reuses any pre-existing script without ownership check.
  - reasoning:
    FLOW: agent autoconfig → `Initializer.initialize` → `initializeOOMENotifier`
    → `OOMENotifierScriptInitializer.initialize` → `copyOOMEscript` on the
    predictable `tempDir`.
    PRECONDITION: attacker pre-plants `<tmp>/ddprof_<user>/pid_<pid>/dd_oome_notifier.sh`.
    REACHABLE: yes — OnOutOfMemoryError points at the predictable path; default-on.
    CONCLUSION: critical — pre-planted notifier script executes as the service
    user on OOME.

- `dd-java-agent/agent-crashtracking/src/main/java/datadog/crashtracking/ConfigManager.java`
  (`writeConfigToPath` L193-197, `writeConfigToFile` L199-235) —
  defect class: *config file written into predictable dir without trust check*.
  - evidence: writes `<script>_pid<pid>.cfg` into `scriptFile.getParentFile()`,
    the same predictable dir; an attacker-controlled config alters uploader
    behaviour (`agentless`, `agent` jar path consumed by the script).
  - reasoning:
    FLOW: initializers → `writeConfigToPath` → `writeConfigToFile` into the
    predictable temp dir.
    PRECONDITION: same predictable-dir control as the script siblings.
    REACHABLE: no — latent — the cfg is overwritten by the agent each run and is
    consumed only by the agent-owned script; once the dir trust boundary
    (primary fix) and script-file validation are in place, the residual risk is
    mitigated. Listed for completeness; the directory-level validation covers it.
    CONCLUSION: low — covered transitively by the directory ownership fix; no
    independent change required beyond inheriting a validated parent directory.

## Assumptions

- Resolved at >=90% confidence: failure for an untrusted **temp directory** is a
  hard `IllegalStateException` (matches existing `createTempDir` contract),
  whereas failure for an untrusted **script path** in the initializers is a soft
  refusal (`return`/`return false` + `SEND_TELEMETRY` log), matching each call
  site's existing contract.
- Resolved at >=90% confidence: ownership is "current JVM user". Implemented by
  comparing `Files.getOwner` of the suspect directory against the owner of a
  path the JVM just created itself (or `baseTempDir` once validated), avoiding a
  dependency on resolving the OS username string.
- Resolved at >=90% confidence: all checks are POSIX-gated via the existing
  `isPosixFs` flag; non-POSIX behaviour is unchanged.
- Resolved at >=90% confidence: `ConfigManager` needs no standalone fix; its
  files live in the directory secured by the primary fix.
