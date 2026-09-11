package datadog.trace.agent.test.scopediag;

import datadog.context.ContextContinuation;
import datadog.trace.api.DDTraceId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Records test-time scope and continuation lifecycles and reports leaks. */
public final class ScopeDiagnostics {
  private static final int DEFAULT_MAX_FRAMES = 6;

  private static final ScopeDiagnostics INSTANCE = new ScopeDiagnostics();

  /** Linearizes event admission, stop/reset, and report snapshots. */
  private final Object lifecycleLock = new Object();

  private final Map<ContextContinuation, ContinuationRecord> records = new IdentityHashMap<>();
  private final Map<Object, ScopeRecord> scopeRecords = new IdentityHashMap<>();
  private final Map<DDTraceId, Long> rootWrittenNanos = new HashMap<>();
  private final Set<ContextContinuation> resolved =
      Collections.newSetFromMap(new IdentityHashMap<ContextContinuation, Boolean>());
  private long seq;
  private long scopeSeq;
  private boolean recording;
  private StackFilter stackFilter = new StackFilter(DEFAULT_MAX_FRAMES);

  private final Listener listener = new Listener();

  private ScopeDiagnostics() {}

  /** Clears any prior data and starts recording with the default stack depth. */
  public static void startRecording() {
    startRecording(DEFAULT_MAX_FRAMES);
  }

  /** Clears any prior data and starts recording, keeping up to {@code maxFrames} per stack. */
  public static void startRecording(int maxFrames) {
    ScopeContinuationProbe.disable();
    synchronized (INSTANCE.lifecycleLock) {
      INSTANCE.recording = false;
      INSTANCE.clear();
      INSTANCE.stackFilter = new StackFilter(maxFrames);
    }
    ScopeContinuationProbe.enable();
    synchronized (INSTANCE.lifecycleLock) {
      INSTANCE.recording = true;
    }
  }

  /** Stops recording (the probe goes inert). Recorded data remains queryable until reset. */
  public static void stop() {
    ScopeContinuationProbe.disable();
    synchronized (INSTANCE.lifecycleLock) {
      INSTANCE.recording = false;
    }
  }

  /** Discards all recorded data. */
  public static void reset() {
    ScopeContinuationProbe.disable();
    synchronized (INSTANCE.lifecycleLock) {
      INSTANCE.recording = false;
      INSTANCE.clear();
    }
  }

  /** Returns an immutable snapshot of the events recorded so far. */
  public static ScopeDiagnosticsReport report() {
    synchronized (INSTANCE.lifecycleLock) {
      return new ScopeDiagnosticsReport(
          new ArrayList<>(INSTANCE.records.values()),
          new ArrayList<>(INSTANCE.scopeRecords.values()),
          new HashMap<>(INSTANCE.rootWrittenNanos));
    }
  }

  /**
   * Fails with an {@link AssertionError} (carrying the problem summary) if the report flags a
   * genuine bug (see {@link ScopeDiagnosticsReport#hasProblems()}). Report-only signals such as
   * late-after-root and close-on-wrong-thread do not fail.
   */
  public static void assertNoLeaks() {
    assertNoLeaks(report());
  }

  /** Fails using the supplied snapshot, so rendering and assertion examine the same events. */
  public static void assertNoLeaks(ScopeDiagnosticsReport report) {
    if (report.hasProblems()) {
      throw new AssertionError("Scope continuation problems detected:\n" + report.renderSummary());
    }
  }

  /** Resolves the default-on policy and rejects opt-outs without a reason. */
  public static boolean isEnabled(TrackScopeContinuations config) {
    if (config == null || config.enabled()) {
      return true;
    }
    if (config.reason().trim().isEmpty()) {
      throw new IllegalArgumentException(
          "@TrackScopeContinuations(enabled = false) requires a reason");
    }
    return false;
  }

  private void clear() {
    records.clear();
    scopeRecords.clear();
    rootWrittenNanos.clear();
    resolved.clear();
    seq = 0;
    scopeSeq = 0;
  }

  private static final StackTraceElement[] NO_STACK = new StackTraceElement[0];

  private ScopeEvent event(ScopeEvent.Type type) {
    return event(type, System.nanoTime());
  }

  /** Uses the supplied event time while capturing the thread and stack at the call site. */
  private ScopeEvent event(ScopeEvent.Type type, long nanos) {
    // Avoid stack walking when call sites are disabled because it perturbs recorded timings.
    StackFilter filter = stackFilter;
    StackTraceElement[] stack =
        filter.maxFrames() <= 0 ? NO_STACK : filter.filter(new Throwable().getStackTrace());
    return new ScopeEvent(type, Thread.currentThread().getName(), nanos, stack);
  }

  static void recordCapture(
      ContextContinuation id, DDTraceId traceId, long spanId, String spanName, byte source) {
    synchronized (INSTANCE.lifecycleLock) {
      if (INSTANCE.recording) {
        INSTANCE.listener.onCapture(id, traceId, spanId, spanName, source);
      }
    }
  }

  static void recordActivate(
      ContextContinuation id,
      DDTraceId traceId,
      long spanId,
      String spanName,
      byte source,
      long nanos) {
    synchronized (INSTANCE.lifecycleLock) {
      if (INSTANCE.recording) {
        INSTANCE.listener.onActivate(id, traceId, spanId, spanName, source, nanos);
      }
    }
  }

  static void recordActivateFailed(ContextContinuation id) {
    synchronized (INSTANCE.lifecycleLock) {
      if (INSTANCE.recording) {
        INSTANCE.listener.onActivateFailed(id);
      }
    }
  }

  static void recordResolve(
      ContextContinuation id, boolean cancelled, long resolveNanos, boolean alreadyResolved) {
    synchronized (INSTANCE.lifecycleLock) {
      if (INSTANCE.recording && (alreadyResolved || INSTANCE.resolved.add(id))) {
        INSTANCE.listener.onResolve(id, cancelled, resolveNanos);
      }
    }
  }

  static void recordRootWritten(DDTraceId traceId) {
    synchronized (INSTANCE.lifecycleLock) {
      if (INSTANCE.recording) {
        INSTANCE.listener.onRootWritten(traceId);
      }
    }
  }

  static void recordScopeOpen(
      Object scope,
      DDTraceId traceId,
      long spanId,
      String spanName,
      byte source,
      ContextContinuation continuation) {
    synchronized (INSTANCE.lifecycleLock) {
      if (INSTANCE.recording) {
        INSTANCE.listener.onScopeOpen(scope, traceId, spanId, spanName, source, continuation);
      }
    }
  }

  static void recordScopeClose(Object scope) {
    synchronized (INSTANCE.lifecycleLock) {
      if (INSTANCE.recording) {
        INSTANCE.listener.onScopeClose(scope);
      }
    }
  }

  static void recordScopeCloseWrongThread(Object scope) {
    synchronized (INSTANCE.lifecycleLock) {
      if (INSTANCE.recording) {
        INSTANCE.listener.onScopeCloseWrongThread(scope);
      }
    }
  }

  private final class Listener {
    void onCapture(
        ContextContinuation id, DDTraceId traceId, long spanId, String spanName, byte source) {
      try {
        ContinuationRecord record =
            new ContinuationRecord(
                seq++, traceId, spanId, spanName, source, false, event(ScopeEvent.Type.CAPTURE));
        records.put(id, record);
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
        long nanos) {
      try {
        recordFor(id, traceId, spanId, spanName, source)
            .addResume(event(ScopeEvent.Type.ACTIVATE, nanos));
      } catch (Throwable ignored) {
      }
    }

    void onActivateFailed(ContextContinuation id) {
      try {
        ContinuationRecord record = records.get(id);
        // only an activation of an already-resolved continuation is a real failure; a plain
        // rollback (e.g. cancelled before any capture was recorded) is benign and ignored
        if (record != null && record.isResolved()) {
          record.addFailedActivation(event(ScopeEvent.Type.ACTIVATE_FAILED));
        }
      } catch (Throwable ignored) {
      }
    }

    void onResolve(ContextContinuation id, boolean cancelled, long resolveNanos) {
      try {
        ScopeEvent.Type type =
            cancelled ? ScopeEvent.Type.RESOLVE_CANCEL : ScopeEvent.Type.RESOLVE_FINISH;
        recordFor(id, DDTraceId.ZERO, 0, null, (byte) -1)
            .setTerminalOrExtra(event(type, resolveNanos));
      } catch (Throwable ignored) {
      }
    }

    void onRootWritten(DDTraceId traceId) {
      try {
        rootWrittenNanos.putIfAbsent(traceId, System.nanoTime());
      } catch (Throwable ignored) {
      }
    }

    void onScopeOpen(
        Object scope,
        DDTraceId traceId,
        long spanId,
        String spanName,
        byte source,
        ContextContinuation continuation) {
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
                event(ScopeEvent.Type.SCOPE_OPEN)));
        if (owner != null) {
          owner.linkScope(s);
        }
      } catch (Throwable ignored) {
      }
    }

    void onScopeClose(Object scope) {
      try {
        ScopeRecord record = scopeRecords.get(scope);
        if (record != null) {
          record.setClose(event(ScopeEvent.Type.SCOPE_CLOSE));
        }
      } catch (Throwable ignored) {
      }
    }

    void onScopeCloseWrongThread(Object scope) {
      try {
        ScopeRecord record = scopeRecords.get(scope);
        if (record != null) {
          record.addWrongThreadClose(event(ScopeEvent.Type.SCOPE_CLOSE_WRONG_THREAD));
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
