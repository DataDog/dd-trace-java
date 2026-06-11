package datadog.trace.agent.test.scopediag;

import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.core.scopemanager.ContinuationDiagnostics;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test-time engine that records scope-continuation lifecycle events and renders leak reports.
 *
 * <p>It installs itself as the {@link ContinuationDiagnostics.Listener} while recording and
 * correlates events by continuation identity (an {@link IdentityHashMap}, never {@code
 * equals}/{@code hashCode}). It assumes a single test runs at a time per JVM (true for
 * instrumentation tests); {@link #reset()} isolates one test from the next.
 *
 * <p>Usage:
 *
 * <pre>
 *   ScopeDiagnostics.startRecording();
 *   ... exercise code under test ...
 *   log.info(ScopeDiagnostics.report().renderGantt());
 *   ScopeDiagnostics.assertNoLeaks();   // optional
 *   ScopeDiagnostics.stop();
 * </pre>
 */
public final class ScopeDiagnostics {
  private static final Logger log = LoggerFactory.getLogger(ScopeDiagnostics.class);
  private static final int DEFAULT_MAX_FRAMES = 6;

  private static final ScopeDiagnostics INSTANCE = new ScopeDiagnostics();

  private final Map<AgentScope.Continuation, ContinuationRecord> records =
      Collections.synchronizedMap(
          new IdentityHashMap<AgentScope.Continuation, ContinuationRecord>());
  private final Map<DDTraceId, Long> rootWrittenNanos = new ConcurrentHashMap<>();
  private final AtomicLong seq = new AtomicLong();
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
    ContinuationDiagnostics.install(INSTANCE.listener);
  }

  /** Stops recording (uninstalls the listener). Recorded data remains queryable until reset. */
  public static void stop() {
    ContinuationDiagnostics.clear();
  }

  /** Discards all recorded data. */
  public static void reset() {
    INSTANCE.records.clear();
    INSTANCE.rootWrittenNanos.clear();
    INSTANCE.seq.set(0);
  }

  /** Builds an immutable snapshot report of everything recorded so far. */
  public static ScopeDiagnosticsReport report() {
    List<ContinuationRecord> snapshot;
    synchronized (INSTANCE.records) {
      snapshot = new ArrayList<>(INSTANCE.records.values());
    }
    return new ScopeDiagnosticsReport(snapshot, new ConcurrentHashMap<>(INSTANCE.rootWrittenNanos));
  }

  /**
   * Fails with an {@link AssertionError} (carrying the leak summary) if any never-resolved leak or
   * double/invalid resolution was recorded. Late-after-root is reported but does not fail.
   */
  public static void assertNoLeaks() {
    ScopeDiagnosticsReport report = report();
    if (report.hasProblems()) {
      throw new AssertionError(
          "Scope continuation leaks detected:\n"
              + report.renderSummary()
              + "\n"
              + report.renderGantt());
    }
  }

  /** Writes the JSON report under {@code build/scope-diagnostics/<name>.json}. */
  public static Path writeJson(String name) {
    return write(name, ".json", report().toJson());
  }

  /** Writes the Mermaid Gantt timeline under {@code build/scope-diagnostics/<name>.md}. */
  public static Path writeMermaid(String name) {
    return write(name, ".md", report().toMermaidGantt());
  }

  private static Path write(String name, String extension, String content) {
    String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
    Path path = Paths.get("build", "scope-diagnostics", safe + extension);
    try {
      Files.createDirectories(path.getParent());
      Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      log.warn("Failed to write scope diagnostics to {}", path, e);
    }
    return path;
  }

  // ---- listener implementation ---------------------------------------------

  private ScopeEvent event(ScopeEvent.Type type) {
    return new ScopeEvent(
        type,
        Thread.currentThread().getName(),
        System.nanoTime(),
        stackFilter.filter(new Throwable().getStackTrace()));
  }

  private final class Listener implements ContinuationDiagnostics.Listener {
    @Override
    public void onCapture(AgentScope.Continuation id, DDTraceId traceId, long spanId, byte source) {
      try {
        ContinuationRecord record =
            new ContinuationRecord(
                seq.getAndIncrement(),
                traceId,
                spanId,
                source,
                false,
                event(ScopeEvent.Type.CAPTURE));
        records.put(id, record);
      } catch (Throwable ignored) {
        // diagnostics must never disturb the tracer
      }
    }

    @Override
    public void onActivate(
        AgentScope.Continuation id, DDTraceId traceId, long spanId, byte source) {
      try {
        recordFor(id, traceId, spanId, source).addActivation(event(ScopeEvent.Type.ACTIVATE));
      } catch (Throwable ignored) {
      }
    }

    @Override
    public void onResolve(AgentScope.Continuation id, boolean cancelled) {
      try {
        ScopeEvent.Type type =
            cancelled ? ScopeEvent.Type.RESOLVE_CANCEL : ScopeEvent.Type.RESOLVE_FINISH;
        recordFor(id, DDTraceId.ZERO, 0, (byte) -1).addResolution(event(type));
      } catch (Throwable ignored) {
      }
    }

    @Override
    public void onRootWritten(DDTraceId traceId) {
      try {
        rootWrittenNanos.putIfAbsent(traceId, System.nanoTime());
      } catch (Throwable ignored) {
      }
    }

    /** Returns the record for an id, creating an orphan record if capture was not observed. */
    private ContinuationRecord recordFor(
        AgentScope.Continuation id, DDTraceId traceId, long spanId, byte source) {
      synchronized (records) {
        ContinuationRecord existing = records.get(id);
        if (existing != null) {
          return existing;
        }
        ContinuationRecord orphan =
            new ContinuationRecord(seq.getAndIncrement(), traceId, spanId, source, true, null);
        records.put(id, orphan);
        return orphan;
      }
    }
  }
}
