# Netty wall-clock coverage: two independent, separable paths

Context: investigation on `paul.fournillon/wallclock-threadsleep-taskblock`, prompted by
Native Socket I/O events (`datadog.NativeSocketEvent`) never firing for `prof-java`'s
AWS/Azure clients, which use `netty-transport-native-epoll`.

Root cause: java-profiler's native socket interposer
(`ddprof-lib/src/main/cpp/libraryPatcher_linux.cpp`, `socket_patch_target_for_library`)
only PLT/GOT-patches `libnet.so`, `libnio.so`, or the IBM `libjava.so` JCL bridge, and only
when physically located inside the JDK's own lib directory (`resolve_jdk_library_directory`).
Netty's native epoll `.so` is never eligible, so none of `send`/`recv`/`read`/`write`/
`epoll_wait`/`poll`/`select` get intercepted in it.

This gap has **two structurally different fixes**, targeting different mechanisms in
ddprof. Do not conflate them — they have different implementation cost, different risk,
and arguably different value.

## Path A — Native Socket I/O for Netty (extend the interposer allowlist)

- Add Netty's native epoll `.so` (or a broader same-directory/companion-jar heuristic) to
  `socket_patch_target_for_library` in `libraryPatcher_linux.cpp`.
- Native/C++ only change, in a security-sensitive allowlist (the JDK-directory check
  exists specifically to avoid patching arbitrary application/JNI DSOs).
- Produces `NativeSocketEvent` (`nativeSocketSampler.cpp`), which is per-call and carries
  a `remoteAddress` resolved via `getpeername`.
- **Weak fit for `epoll_wait` specifically**: `epoll_wait` blocks across many
  simultaneously-registered fds/peers, so there's no single attributable remote address,
  and most `epoll_wait` returns are healthy idle waiting, not a stalled peer. The
  send/recv/read/write case (single fd, single peer) is the strong fit; the multiplexed
  wait call is not.

## Path B — TaskBlock + signal suppression for Netty (bytecode call-site bracketing)

- Independent mechanism. `beginTaskBlock`/`endTaskBlock`
  (`ddprof-lib/src/main/java/com/datadoghq/profiler/TaskBlockBridge.java` →
  `javaApi.cpp:Java_com_datadoghq_profiler_JavaProfiler_beginTaskBlock0`/`endTaskBlock0` →
  `ThreadFilter::activeOwnedBlockGeneration`/`isOwnedBlockSuppressionCandidate` in
  `threadFilter.cpp`) has nothing to do with the socket interposer or `IO_WAIT`.
- This is the exact same primitive already used by
  `ThreadSleepProfilingInstrumentation`/the LockSupport instrumentation
  (`dd-java-agent/instrumentation/datadog/profiling/thread-sleep/...`): a
  `CallSiteAdvice`-based bytecode rewrite that brackets a target call with
  `begin()` / `try { ... } finally { finish() }`.
- For Netty: bracket the Java-level blocking entry point (e.g.
  `io.netty.channel.epoll.EpollEventLoop`'s wait call, or NIO `SelectorImpl`'s select)
  the same way `ThreadSleepCallSites` brackets `Thread.sleep`.
- **All-Java**, no native/allowlist changes, no dependency on which native transport
  Netty is using (epoll vs kqueue vs NIO) since the bracket is at the Java call site, not
  the native syscall.
- Signal suppression and the TaskBlock backfill are bundled atomically by construction
  (same as sleep/park today) — no separate coverage-gap risk to reason about.
- Same open question as Path A applies here too: is bracketing a multiplexed,
  mostly-healthy wait as a single "blocked" interval a meaningful signal, or just noise
  with a fancy name? Worth prototyping cheaply (it's one instrumentation module) before
  deciding.

## Bottom line for whoever picks this up

- These are not two ways to reach the same goal — Path A is a native interposer scope
  extension for per-connection I/O attribution; Path B is a Java bytecode
  instrumentation for suppress-and-backfill signal accounting. They can be pursued
  independently, in either order, or not at all.
- Path B is cheaper and lower-risk to prototype (no C++ changes) and is structurally
  identical to already-shipped code (`ThreadSleepProfilingInstrumentation`).
- Neither path is currently justified as "filling an existing gap" — today, Netty's
  `epoll_wait`-blocked threads are *not* precheck-suppressed at all (they fall through to
  ddprof's generic HotSpot-reported native/`SYSCALL` state, which is not in
  `isPrecheckSuppressionState`), so ordinary signal-based wall-clock sampling already
  covers them. Building either path *introduces* a new suppression category together
  with its own backfill — it is not closing a hole that exists today.
