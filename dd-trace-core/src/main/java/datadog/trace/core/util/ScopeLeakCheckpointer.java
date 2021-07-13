package datadog.trace.core.util;

import static java.lang.Boolean.TRUE;
import static java.lang.System.lineSeparator;

import datadog.trace.api.Checkpointer;
import datadog.trace.api.DDId;
import java.io.IOException;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ScopeLeakCheckpointer implements Checkpointer {
  static final Logger log = LoggerFactory.getLogger(ScopeLeakCheckpointer.class);

  private static final int LARGE_TRACE_SIZE = 500;
  private static final int FINGERPRINT_SIZE = 10;

  private final ConcurrentHashMap<Fingerprint, Boolean> fingerprints = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<DDId, LeakCandidate> leakCandidates = new ConcurrentHashMap<>();
  private final PrintStream out;

  public ScopeLeakCheckpointer(final String outputFile) {
    PrintStream out = null;
    if (!outputFile.isEmpty() && !"log".equalsIgnoreCase(outputFile)) {
      try {
        out = new PrintStream(outputFile);
      } catch (IOException e) {
        log.warn("Could not open {}, will log scope leaks instead", outputFile, e);
      }
    }
    this.out = out;
  }

  @Override
  public void checkpoint(final DDId traceId, final DDId spanId, final int flags) {
    LeakCandidate leakCandidate;
    switch (flags) {
      case THREAD_MIGRATION:
        Fingerprint caller = buildStackFingerprint();
        if (fingerprints.putIfAbsent(caller, TRUE) == null) {
          // not seen this migration before, see if there's an existing candidate to tie it to
          leakCandidate = new LeakCandidate();
          LeakCandidate oldCandidate = leakCandidates.putIfAbsent(traceId, leakCandidate);
          if (oldCandidate != null) {
            leakCandidate = oldCandidate;
          }
          leakCandidate.markMigrationPoint(caller);
        }
        break;
      case THREAD_MIGRATION | END:
        leakCandidate = leakCandidates.get(traceId);
        if (leakCandidate != null) {
          // if we've already done this migration in this trace then this could be a scope leak
          if (!leakCandidate.markMigrationPoint(buildStackFingerprint())) {
            leakCandidates.remove(traceId);
            leakCandidate.printMigrationPoints(traceId, out);
          }
        }
        break;
      case SPAN:
        leakCandidate = leakCandidates.get(traceId);
        // very large trace could indicate a scope leak, so print out details of any migrations
        if (leakCandidate != null && leakCandidate.markSpanStart() > LARGE_TRACE_SIZE) {
          leakCandidates.remove(traceId);
          leakCandidate.printMigrationPoints(traceId, out);
        }
        break;
      case SPAN | END:
        leakCandidate = leakCandidates.get(traceId);
        if (leakCandidate != null) {
          leakCandidate.markSpanEnd();
        }
        break;
      default:
        // ignore other events
        break;
    }
  }

  @Override
  public void onRootSpanPublished(final String route, final DDId traceId) {
    leakCandidates.remove(traceId);
  }

  private Fingerprint buildStackFingerprint() {
    StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
    int from = 0, to = 0;
    // walk stack until we have a large enough fingerprint
    while (to - from < FINGERPRINT_SIZE && to < stackTrace.length) {
      // ignore everything from the top of the stack until after our datadog frames
      if ((from == 0 || from == to) && stackTrace[to].getClassName().startsWith("datadog.")) {
        from = ++to;
      } else {
        ++to;
      }
    }
    return new Fingerprint(stackTrace, from, to);
  }

  /** Represents a potential scope leak, marks a series of migration points between threads. */
  static final class LeakCandidate {
    private static final AtomicIntegerFieldUpdater<LeakCandidate> spanCountUpdater =
        AtomicIntegerFieldUpdater.newUpdater(LeakCandidate.class, "spanCount");

    private final Map<Fingerprint, String> migrationPath = new LinkedHashMap<>();
    private volatile int spanCount = 0;

    public boolean markMigrationPoint(final Fingerprint caller) {
      synchronized (migrationPath) {
        return migrationPath.put(caller, Thread.currentThread().getName()) == null;
      }
    }

    public void printMigrationPoints(final DDId traceId, final PrintStream out) {
      StringBuilder buf = new StringBuilder();
      String prefix = "    trace " + traceId.toHexString() + " from ";
      for (Map.Entry<Fingerprint, String> point : migrationPath.entrySet()) {
        buf.append(lineSeparator())
            .append(prefix)
            .append(point.getValue())
            .append(':')
            .append(point.getKey());
        prefix = "    migrated to ";
      }
      if (out != null) {
        out.println("Potential scope leak!" + buf);
      } else {
        log.warn("Potential scope leak!{}", buf);
      }
    }

    public int markSpanStart() {
      return spanCountUpdater.getAndIncrement(this);
    }

    public int markSpanEnd() {
      return spanCountUpdater.getAndDecrement(this);
    }
  }

  /** Represents a short snippet of a stack trace that can act as a fingerprint. */
  static final class Fingerprint {
    private final StackTraceElement[] stackTrace;
    private final int from, to;
    private final int hashCode;

    Fingerprint(final StackTraceElement[] stackTrace, final int from, final int to) {
      this.stackTrace = stackTrace;
      this.from = from;
      this.to = to;
      // precompute hashCode
      int hashCode = 1;
      for (int i = from; i < to; i++) {
        hashCode = 31 * hashCode + stackTrace[i].hashCode();
      }
      this.hashCode = hashCode;
    }

    @Override
    public boolean equals(final Object other) {
      if (other == this) {
        return true;
      }
      if (!(other instanceof Fingerprint)) {
        return false;
      }
      Fingerprint fingerprint = (Fingerprint) other;
      if (hashCode != fingerprint.hashCode || to - from != fingerprint.to - fingerprint.from) {
        return false;
      }
      for (int i = from, j = fingerprint.from; i < to; i++, j++) {
        if (!stackTrace[i].equals(fingerprint.stackTrace[j])) {
          return false;
        }
      }
      return true;
    }

    @Override
    public int hashCode() {
      return hashCode;
    }

    @Override
    public String toString() {
      StringBuilder buf = new StringBuilder();
      for (int i = from; i < to; i++) {
        buf.append(lineSeparator()).append("\tat ").append(stackTrace[i]);
      }
      return buf.toString();
    }
  }
}
