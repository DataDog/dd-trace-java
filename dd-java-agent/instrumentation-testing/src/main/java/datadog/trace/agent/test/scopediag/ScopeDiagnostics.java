package datadog.trace.agent.test.scopediag;

import datadog.context.ContextContinuation;
import datadog.trace.api.DDTraceId;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Records test-time scope and continuation lifecycles and reports leaks. */
public final class ScopeDiagnostics {
  private static final Logger log = LoggerFactory.getLogger(ScopeDiagnostics.class);

  private static final int DEFAULT_MAX_FRAMES = 6;
  private static final long DEFAULT_QUIESCENCE_TIMEOUT_MILLIS = 250;

  static final ScopeDiagnostics INSTANCE = new ScopeDiagnostics();

  /** Serializes event consumption, stop/reset, and report snapshots. */
  final ReentrantLock lifecycleLock = new ReentrantLock();

  // Callbacks also run on virtual-thread carriers. They must never wait for a lock whose owner
  // may be an unmounted virtual thread waiting for one of those same carriers.
  private final ConcurrentLinkedQueue<Thread> quiescenceWaiters = new ConcurrentLinkedQueue<>();

  private final Map<ContextContinuation, ContinuationRecord> records = new IdentityHashMap<>();
  private final Map<Object, ScopeRecord> scopeRecords = new IdentityHashMap<>();
  private final Set<Object> deferredCleanupScopes =
      Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
  private final Map<DDTraceId, Long> rootWrittenNanos = new HashMap<>();
  private final Set<ContextContinuation> resolved =
      Collections.newSetFromMap(new IdentityHashMap<ContextContinuation, Boolean>());
  private final Set<ContextContinuation> released =
      Collections.newSetFromMap(new IdentityHashMap<ContextContinuation, Boolean>());
  private long seq;
  private long scopeSeq;

  @SuppressFBWarnings(
      value = "AT_NONATOMIC_64BIT_PRIMITIVE",
      justification = "All reads and writes, including Listener callbacks, hold lifecycleLock")
  private long incompleteContinuations;

  @SuppressFBWarnings(
      value = "AT_NONATOMIC_64BIT_PRIMITIVE",
      justification = "All reads and writes, including Listener callbacks, hold lifecycleLock")
  private long incompleteScopes;

  private volatile DiagnosticEventBuffer recordingWindow;
  private volatile StackFilter stackFilter = new StackFilter(DEFAULT_MAX_FRAMES);

  private final Listener listener = new Listener();

  private ScopeDiagnostics() {}

  /** Starts configured recording, warning when no remaining check can fail the test. */
  public static void startRecording(TrackScopeContinuations config) {
    EnumSet<ScopeDiagnosticsCheck> checks = enabledChecks(config);
    if (config != null && !config.enabled()) {
      return;
    }
    if (ScopeDiagnosticsReport.enforcedChecks(checks).isEmpty()) {
      log.warn(
          "@TrackScopeContinuations excludes all enforced checks; recording remains enabled, "
              + "but no diagnostic finding can fail the test. Reason: {}",
          config.reason());
    }
    startRecording();
  }

  /** Clears any prior data and starts recording with the default stack depth. */
  public static void startRecording() {
    startRecording(DEFAULT_MAX_FRAMES);
  }

  /** Clears any prior data and starts recording, keeping up to {@code maxFrames} per stack. */
  public static void startRecording(int maxFrames) {
    ScopeContinuationTransformer.install();
    INSTANCE.lifecycleLock.lock();
    try {
      INSTANCE.clear();
      INSTANCE.stackFilter = new StackFilter(maxFrames);
      INSTANCE.recordingWindow = new DiagnosticEventBuffer();
    } finally {
      INSTANCE.lifecycleLock.unlock();
    }
    INSTANCE.wakeQuiescenceWaiters();
  }

  /** Stops recording. Recorded data remains queryable until reset. */
  public static void stop() {
    INSTANCE.lifecycleLock.lock();
    try {
      DiagnosticEventBuffer window = INSTANCE.recordingWindow;
      INSTANCE.recordingWindow = null;
      if (window != null) {
        window.close();
        window.drain(false, 0, true);
      }
    } finally {
      INSTANCE.lifecycleLock.unlock();
    }
    INSTANCE.wakeQuiescenceWaiters();
  }

  /** Discards all recorded data. */
  public static void reset() {
    INSTANCE.lifecycleLock.lock();
    try {
      INSTANCE.clear();
    } finally {
      INSTANCE.lifecycleLock.unlock();
    }
    INSTANCE.wakeQuiescenceWaiters();
  }

  /** Each probe keeps this identity so delayed callbacks cannot enter a later recording. */
  static Object recordingWindow() {
    return INSTANCE.recordingWindow;
  }

  /** Returns an immutable snapshot of the events recorded so far. */
  public static ScopeDiagnosticsReport report() {
    INSTANCE.lifecycleLock.lock();
    try {
      INSTANCE.drainEvents();
      return INSTANCE.snapshot();
    } finally {
      INSTANCE.lifecycleLock.unlock();
    }
  }

  /** Gives asynchronous cleanup a bounded opportunity to resolve lifecycles still in flight. */
  public static void awaitQuiescence() {
    awaitQuiescence(DEFAULT_QUIESCENCE_TIMEOUT_MILLIS);
  }

  /** Waits up to the supplied budget for recorded lifecycles to complete. */
  public static void awaitQuiescence(long timeoutMillis) {
    if (timeoutMillis < 0 || timeoutMillis > Long.MAX_VALUE / 1_000_000L) {
      throw new IllegalArgumentException(
          "Invalid scope diagnostics quiescence budget: " + timeoutMillis);
    }
    long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
    Thread waiter = Thread.currentThread();
    INSTANCE.quiescenceWaiters.add(waiter);
    try {
      while (!waiter.isInterrupted()) {
        INSTANCE.lifecycleLock.lock();
        try {
          INSTANCE.drainEvents(true, deadline, false);
          if (INSTANCE.recordingWindow == null || !INSTANCE.hasIncompleteLifecycles()) {
            return;
          }
        } finally {
          INSTANCE.lifecycleLock.unlock();
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          return;
        }
        // Registration precedes the predicate check; an enqueue racing with park retains its
        // permit.
        LockSupport.parkNanos(INSTANCE, remaining);
      }
    } finally {
      INSTANCE.quiescenceWaiters.remove(waiter);
    }
  }

  /**
   * Fails with an {@link AssertionError} (carrying the problem summary) if the report flags a
   * genuine bug (see {@link ScopeDiagnosticsReport#hasViolations()}). Report-only signals such as
   * late-after-root and close-on-wrong-thread do not fail.
   */
  public static void assertNoViolations() {
    assertNoViolations(report());
  }

  /** Fails using the supplied snapshot, so rendering and assertion examine the same events. */
  public static void assertNoViolations(ScopeDiagnosticsReport report) {
    assertNoViolations(report, null);
  }

  /** Fails when the supplied configuration selects an enforced violation in the snapshot. */
  public static void assertNoViolations(
      ScopeDiagnosticsReport report, TrackScopeContinuations config) {
    EnumSet<ScopeDiagnosticsCheck> checks = enabledChecks(config);
    if (report.hasViolations(checks)) {
      throw new AssertionError(
          "Scope/continuation lifecycle violations detected:\n" + report.renderSummary(checks));
    }
  }

  /** Validates the configuration and returns whether recording is enabled. */
  public static boolean isEnabled(TrackScopeContinuations config) {
    enabledChecks(config);
    return config == null || config.enabled();
  }

  static EnumSet<ScopeDiagnosticsCheck> enabledChecks(TrackScopeContinuations config) {
    EnumSet<ScopeDiagnosticsCheck> allChecks = EnumSet.allOf(ScopeDiagnosticsCheck.class);
    if (config == null) {
      return allChecks;
    }

    if (!config.enabled() && config.disabledChecks().length > 0) {
      throw new IllegalArgumentException(
          "@TrackScopeContinuations(enabled = false) requires empty disabledChecks");
    }
    if ((!config.enabled() || config.disabledChecks().length > 0)
        && config.reason().trim().isEmpty()) {
      throw new IllegalArgumentException(
          "@TrackScopeContinuations requires a reason when recording or checks are disabled");
    }

    EnumSet<ScopeDiagnosticsCheck> enabled = allChecks;
    if (!config.enabled()) {
      enabled.clear();
    } else {
      for (ScopeDiagnosticsCheck check : config.disabledChecks()) {
        enabled.remove(check);
      }
    }
    return enabled;
  }

  private void clear() {
    DiagnosticEventBuffer window = recordingWindow;
    recordingWindow = null;
    if (window != null) {
      window.close();
    }
    records.clear();
    scopeRecords.clear();
    deferredCleanupScopes.clear();
    rootWrittenNanos.clear();
    resolved.clear();
    released.clear();
    seq = 0;
    scopeSeq = 0;
    incompleteContinuations = 0;
    incompleteScopes = 0;
  }

  private ScopeDiagnosticsReport snapshot() {
    return new ScopeDiagnosticsReport(
        new ArrayList<>(records.values()),
        new ArrayList<>(scopeRecords.values()),
        new HashMap<>(rootWrittenNanos));
  }

  private static final StackTraceElement[] NO_STACK = new StackTraceElement[0];

  private ScopeEvent event(
      ScopeEvent.Type type, long nanos, String threadName, StackFilter filter, Throwable callSite) {
    StackTraceElement[] stack =
        callSite == null ? NO_STACK : filter.filter(callSite.getStackTrace());
    return new ScopeEvent(type, threadName, nanos, stack);
  }

  private static void record(
      Object identity, ScopeEvent.Type type, long nanos, Consumer<Supplier<ScopeEvent>> handler) {
    DiagnosticEventBuffer window = INSTANCE.recordingWindow;
    if (window == null || window != identity) {
      return;
    }
    // Reservation is admission: closing the buffer atomically refuses all later callbacks.
    // Reserve before collecting a stack so slow stack walks cannot reorder lifecycle events.
    DiagnosticEventBuffer.Event pending = window.reserve();
    if (pending == null) {
      return;
    }
    StackFilter filter = INSTANCE.stackFilter;
    String threadName = Thread.currentThread().getName();
    try {
      if (INSTANCE.lifecycleLock.tryLock()) {
        try {
          if (window == INSTANCE.recordingWindow) {
            window.drain(false, 0, false);
            if (window.isNext(pending)) {
              // The common path filters ignored callbacks before even constructing a Throwable.
              pending.publish(
                  () ->
                      handler.accept(
                          () ->
                              INSTANCE.event(
                                  type,
                                  nanos,
                                  threadName,
                                  filter,
                                  filter.maxFrames() <= 0 ? null : new Throwable())));
              window.drain(false, 0, false);
              return;
            }
          }
        } finally {
          INSTANCE.lifecycleLock.unlock();
        }
      }
      // A contended callback must preserve its originating stack, but materialization and filtering
      // can wait until the consumer knows this event will actually be retained.
      Throwable callSite = type == null || filter.maxFrames() <= 0 ? null : new Throwable();
      pending.publish(
          () -> handler.accept(() -> INSTANCE.event(type, nanos, threadName, filter, callSite)));
      if (INSTANCE.lifecycleLock.tryLock()) {
        try {
          if (window == INSTANCE.recordingWindow) {
            window.drain(false, 0, false);
          }
        } finally {
          INSTANCE.lifecycleLock.unlock();
        }
      }
    } finally {
      // Even a failed stack capture must release the reservation to a stopping/reporting reader.
      pending.publishIfAbsent();
      INSTANCE.wakeQuiescenceWaiters();
    }
  }

  private void wakeQuiescenceWaiters() {
    for (Thread waiter : quiescenceWaiters) {
      LockSupport.unpark(waiter);
    }
  }

  private void drainEvents() {
    drainEvents(false, 0, true);
  }

  private void drainEvents(boolean timed, long deadline, boolean waitForPublication) {
    DiagnosticEventBuffer window = recordingWindow;
    if (window != null) {
      window.drain(timed, deadline, waitForPublication);
    }
  }

  /** Inspects live state without copying every record and call stack on every wakeup. */
  private boolean hasIncompleteLifecycles() {
    return incompleteContinuations != 0
        || incompleteScopes != 0
        || (recordingWindow != null && recordingWindow.hasPending());
  }

  static void recordCapture(
      Object window,
      ContextContinuation id,
      DDTraceId traceId,
      long spanId,
      String spanName,
      byte source) {
    record(
        window,
        ScopeEvent.Type.CAPTURE,
        System.nanoTime(),
        event -> INSTANCE.listener.onCapture(id, traceId, spanId, spanName, source, event));
  }

  static void recordActivate(
      Object window,
      ContextContinuation id,
      DDTraceId traceId,
      long spanId,
      String spanName,
      byte source,
      long nanos) {
    record(
        window,
        ScopeEvent.Type.ACTIVATE,
        nanos,
        event -> INSTANCE.listener.onActivate(id, traceId, spanId, spanName, source, event));
  }

  static void recordActivateFailed(Object window, ContextContinuation id) {
    record(
        window,
        ScopeEvent.Type.ACTIVATE_FAILED,
        System.nanoTime(),
        event -> INSTANCE.listener.onActivateFailed(id, event));
  }

  static void recordResolve(
      Object window,
      ContextContinuation id,
      boolean release,
      long resolveNanos,
      boolean duplicateAttempt) {
    record(
        window,
        release ? ScopeEvent.Type.RESOLVE_RELEASE : ScopeEvent.Type.RESOLVE_FINISH,
        resolveNanos,
        event -> {
          boolean firstResolution = INSTANCE.resolved.add(id);
          boolean repeatedRelease = release && !INSTANCE.released.add(id);
          if (duplicateAttempt || firstResolution || repeatedRelease) {
            INSTANCE.listener.onResolve(id, duplicateAttempt || repeatedRelease, event);
          }
        });
  }

  static void recordRootWritten(Object window, DDTraceId traceId) {
    long nanos = System.nanoTime();
    record(window, null, nanos, ignored -> INSTANCE.listener.onRootWritten(traceId, nanos));
  }

  static void recordScopeOpen(
      Object window,
      Object scope,
      DDTraceId traceId,
      long spanId,
      String spanName,
      byte source,
      ContextContinuation continuation) {
    record(
        window,
        ScopeEvent.Type.SCOPE_OPEN,
        System.nanoTime(),
        event ->
            INSTANCE.listener.onScopeOpen(
                scope, traceId, spanId, spanName, source, continuation, event));
  }

  static void recordScopeClose(Object window, Object scope) {
    record(
        window,
        ScopeEvent.Type.SCOPE_CLOSE,
        System.nanoTime(),
        event -> INSTANCE.listener.onScopeClose(scope, event));
  }

  static void recordScopeCloseWrongThread(Object window, Object scope) {
    record(
        window,
        ScopeEvent.Type.SCOPE_CLOSE_WRONG_THREAD,
        System.nanoTime(),
        event -> INSTANCE.listener.onScopeCloseWrongThread(scope, event));
  }

  static void recordDeferredScopeCleanup(Object window, Object scope) {
    record(window, null, 0, ignored -> INSTANCE.listener.onDeferredScopeCleanup(scope));
  }

  private final class Listener {
    void onCapture(
        ContextContinuation id,
        DDTraceId traceId,
        long spanId,
        String spanName,
        byte source,
        Supplier<ScopeEvent> event) {
      try {
        ContinuationRecord record =
            new ContinuationRecord(seq++, traceId, spanId, spanName, source, false, event.get());
        ContinuationRecord previous = records.put(id, record);
        if (previous == null || previous.orphan || previous.isResolved()) {
          incompleteContinuations++;
        }
      } catch (Throwable ignored) {
        // diagnostics must never disturb the tracer
      }
    }

    void onActivate(
        ContextContinuation id,
        DDTraceId traceId,
        long spanId,
        String spanName,
        byte source,
        Supplier<ScopeEvent> event) {
      try {
        recordFor(id, traceId, spanId, spanName, source).addResume(event.get());
      } catch (Throwable ignored) {
      }
    }

    void onActivateFailed(ContextContinuation id, Supplier<ScopeEvent> event) {
      try {
        // The probe observed cancellation; capture and resolution need not be in this window.
        ContinuationRecord record = recordFor(id, DDTraceId.ZERO, 0, null, (byte) -1);
        ScopeEvent retained = event.get();
        if (!record.orphan && !record.isResolved()) {
          incompleteContinuations--;
        }
        record.addFailedActivation(retained);
      } catch (Throwable ignored) {
      }
    }

    void onResolve(ContextContinuation id, boolean duplicateAttempt, Supplier<ScopeEvent> event) {
      try {
        ContinuationRecord record = recordFor(id, DDTraceId.ZERO, 0, null, (byte) -1);
        ScopeEvent retained = event.get();
        if (!record.orphan && !record.isResolved()) {
          incompleteContinuations--;
        }
        record.setTerminalOrExtra(retained, duplicateAttempt);
      } catch (Throwable ignored) {
      }
    }

    void onRootWritten(DDTraceId traceId, long nanos) {
      try {
        rootWrittenNanos.putIfAbsent(traceId, nanos);
      } catch (Throwable ignored) {
      }
    }

    void onScopeOpen(
        Object scope,
        DDTraceId traceId,
        long spanId,
        String spanName,
        byte source,
        ContextContinuation continuation,
        Supplier<ScopeEvent> event) {
      try {
        if (scopeRecords.containsKey(scope)) {
          return; // re-activation of an already-open scope, not a new open
        }
        ContinuationRecord owner = continuation != null ? records.get(continuation) : null;
        Long continuationSeq = owner != null ? owner.seq : null;
        long s = scopeSeq++;
        scopeRecords.put(
            scope,
            new ScopeRecord(
                s,
                traceId,
                spanId,
                spanName,
                source,
                continuationSeq,
                deferredCleanupScopes.remove(scope),
                event.get()));
        if (!scopeRecords.get(scope).deferredCleanup()) {
          incompleteScopes++;
        }
        if (owner != null) {
          owner.linkScope(s);
        }
      } catch (Throwable ignored) {
      }
    }

    void onScopeClose(Object scope, Supplier<ScopeEvent> event) {
      try {
        ScopeRecord record = scopeRecords.get(scope);
        if (record != null && !record.closed()) {
          record.setClose(event.get());
          if (!record.deferredCleanup()) {
            incompleteScopes--;
          }
        }
      } catch (Throwable ignored) {
      }
    }

    void onScopeCloseWrongThread(Object scope, Supplier<ScopeEvent> event) {
      try {
        ScopeRecord record = scopeRecords.get(scope);
        if (record != null) {
          record.addWrongThreadClose(event.get());
        }
      } catch (Throwable ignored) {
      }
    }

    void onDeferredScopeCleanup(Object scope) {
      try {
        ScopeRecord record = scopeRecords.get(scope);
        if (record != null) {
          if (!record.closed() && !record.deferredCleanup()) {
            incompleteScopes--;
          }
          record.markDeferredCleanup();
        } else {
          deferredCleanupScopes.add(scope);
        }
      } catch (Throwable ignored) {
      }
    }

    /** Returns the record for an id, creating an orphan record if capture was not observed. */
    private ContinuationRecord recordFor(
        ContextContinuation id, DDTraceId traceId, long spanId, String spanName, byte source) {
      ContinuationRecord existing = records.get(id);
      if (existing != null) {
        return existing;
      }
      ContinuationRecord orphan =
          new ContinuationRecord(seq++, traceId, spanId, spanName, source, true, null);
      records.put(id, orphan);
      return orphan;
    }
  }
}
