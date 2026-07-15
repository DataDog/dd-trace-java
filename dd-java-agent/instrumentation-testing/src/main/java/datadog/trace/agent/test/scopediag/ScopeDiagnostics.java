package datadog.trace.agent.test.scopediag;

import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test-time engine that records scope/continuation lifecycle events and renders leak reports.
 *
 * <p>It models two correlated lifecycles separately: continuations ({@link ContinuationRecord} —
 * captured/resumed/finished) and scopes ({@link ScopeRecord} — opened/closed). While recording,
 * {@link ScopeContinuationProbe} (test-only bytecode advice) feeds it events, which it correlates
 * by identity (an {@link IdentityHashMap}, never {@code equals}/{@code hashCode}) — continuations
 * by their {@code AgentScope.Continuation} instance, scopes by their scope instance. It assumes a
 * single test runs at a time per JVM (true for instrumentation tests); {@link #reset()} isolates
 * one test from the next.
 *
 * <p>Usage:
 *
 * <pre>
 *   ScopeDiagnostics.startRecording();
 *   ... exercise code under test ...
 *   System.out.println(ScopeDiagnostics.report().renderSummary());
 *   ScopeDiagnostics.assertNoLeaks();   // optional
 *   ScopeDiagnostics.stop();
 * </pre>
 */
public final class ScopeDiagnostics {
  private static final int DEFAULT_MAX_FRAMES = 6;

  private static final ScopeDiagnostics INSTANCE = new ScopeDiagnostics();

  private final Map<AgentScope.Continuation, ContinuationRecord> records =
      Collections.synchronizedMap(
          new IdentityHashMap<AgentScope.Continuation, ContinuationRecord>());
  private final Map<Object, ScopeRecord> scopeRecords =
      Collections.synchronizedMap(new IdentityHashMap<Object, ScopeRecord>());
  private final Map<DDTraceId, Long> rootWrittenNanos = new ConcurrentHashMap<>();
  private final AtomicLong seq = new AtomicLong();
  private final AtomicLong scopeSeq = new AtomicLong();
  private volatile StackFilter stackFilter = new StackFilter(DEFAULT_MAX_FRAMES);

  private final Listener listener = new Listener();

  private ScopeDiagnostics() {}

  // ---- public static facade ------------------------------------------------

  /** Clears any prior data and starts recording with the default stack depth. */
  public static void startRecording() {
    startRecording(DEFAULT_MAX_FRAMES);
  }

  /** Clears any prior data and starts recording, keeping up to {@code maxFrames} per stack. */
  public static void startRecording(int maxFrames) {
    INSTANCE.reset();
    INSTANCE.stackFilter = new StackFilter(maxFrames);
    ScopeContinuationProbe.enable();
  }

  /** Stops recording (the probe goes inert). Recorded data remains queryable until reset. */
  public static void stop() {
    ScopeContinuationProbe.disable();
  }

  /** Discards all recorded data. */
  public static void reset() {
    INSTANCE.records.clear();
    INSTANCE.scopeRecords.clear();
    INSTANCE.rootWrittenNanos.clear();
    INSTANCE.seq.set(0);
    INSTANCE.scopeSeq.set(0);
    ScopeContinuationProbe.reset();
  }

  /** Builds an immutable snapshot report of everything recorded so far. */
  public static ScopeDiagnosticsReport report() {
    List<ContinuationRecord> continuations;
    synchronized (INSTANCE.records) {
      continuations = new ArrayList<>(INSTANCE.records.values());
    }
    List<ScopeRecord> scopes;
    synchronized (INSTANCE.scopeRecords) {
      scopes = new ArrayList<>(INSTANCE.scopeRecords.values());
    }
    return new ScopeDiagnosticsReport(
        continuations, scopes, new ConcurrentHashMap<>(INSTANCE.rootWrittenNanos));
  }

  /**
   * Fails with an {@link AssertionError} (carrying the problem summary) if the report flags a
   * genuine bug (see {@link ScopeDiagnosticsReport#hasProblems()}). Report-only signals such as
   * late-after-root and close-on-wrong-thread do not fail.
   */
  public static void assertNoLeaks() {
    ScopeDiagnosticsReport report = report();
    if (report.hasProblems()) {
      throw new AssertionError("Scope continuation problems detected:\n" + report.renderSummary());
    }
  }

  // ---- listener implementation ---------------------------------------------

  private static final StackTraceElement[] NO_STACK = new StackTraceElement[0];

  private ScopeEvent event(ScopeEvent.Type type) {
    return event(type, System.nanoTime());
  }

  /** Builds an event with an explicit timestamp (thread and stack are still captured now). */
  private ScopeEvent event(ScopeEvent.Type type, long nanos) {
    // Capturing a stack per event is the dominant cost and perturbs the very timings we record;
    // skip it entirely when callsites are disabled (maxFrames <= 0) rather than walking then
    // trimming.
    StackFilter filter = stackFilter;
    StackTraceElement[] stack =
        filter.maxFrames() <= 0 ? NO_STACK : filter.filter(new Throwable().getStackTrace());
    return new ScopeEvent(type, Thread.currentThread().getName(), nanos, stack);
  }

  // ---- static forwarders called by ScopeContinuationProbe ------------------

  static void recordCapture(
      AgentScope.Continuation id, DDTraceId traceId, long spanId, String spanName, byte source) {
    INSTANCE.listener.onCapture(id, traceId, spanId, spanName, source);
  }

  static void recordActivate(
      AgentScope.Continuation id,
      DDTraceId traceId,
      long spanId,
      String spanName,
      byte source,
      long nanos) {
    INSTANCE.listener.onActivate(id, traceId, spanId, spanName, source, nanos);
  }

  static void recordActivateFailed(AgentScope.Continuation id) {
    INSTANCE.listener.onActivateFailed(id);
  }

  static void recordResolve(AgentScope.Continuation id, boolean cancelled, long resolveNanos) {
    INSTANCE.listener.onResolve(id, cancelled, resolveNanos);
  }

  static void recordRootWritten(DDTraceId traceId) {
    INSTANCE.listener.onRootWritten(traceId);
  }

  static void recordScopeOpen(
      Object scope,
      DDTraceId traceId,
      long spanId,
      String spanName,
      byte source,
      AgentScope.Continuation continuation) {
    INSTANCE.listener.onScopeOpen(scope, traceId, spanId, spanName, source, continuation);
  }

  static void recordScopeClose(Object scope) {
    INSTANCE.listener.onScopeClose(scope);
  }

  static void recordScopeCloseWrongThread(Object scope) {
    INSTANCE.listener.onScopeCloseWrongThread(scope);
  }

  private final class Listener {
    void onCapture(
        AgentScope.Continuation id, DDTraceId traceId, long spanId, String spanName, byte source) {
      try {
        ContinuationRecord record =
            new ContinuationRecord(
                seq.getAndIncrement(),
                traceId,
                spanId,
                spanName,
                source,
                false,
                event(ScopeEvent.Type.CAPTURE));
        records.put(id, record);
      } catch (Throwable ignored) {
        // diagnostics must never disturb the tracer
      }
    }

    void onActivate(
        AgentScope.Continuation id,
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

    void onActivateFailed(AgentScope.Continuation id) {
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

    void onResolve(AgentScope.Continuation id, boolean cancelled, long resolveNanos) {
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
        AgentScope.Continuation continuation) {
      try {
        synchronized (scopeRecords) {
          if (scopeRecords.containsKey(scope)) {
            return; // re-activation of an already-open scope, not a new open
          }
          ContinuationRecord owner = continuation != null ? records.get(continuation) : null;
          Long continuationSeq = owner != null ? owner.seq : null;
          long s = scopeSeq.getAndIncrement();
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
        AgentScope.Continuation id, DDTraceId traceId, long spanId, String spanName, byte source) {
      synchronized (records) {
        ContinuationRecord existing = records.get(id);
        if (existing != null) {
          return existing;
        }
        ContinuationRecord orphan =
            new ContinuationRecord(
                seq.getAndIncrement(), traceId, spanId, spanName, source, true, null);
        records.put(id, orphan);
        return orphan;
      }
    }
  }
}
