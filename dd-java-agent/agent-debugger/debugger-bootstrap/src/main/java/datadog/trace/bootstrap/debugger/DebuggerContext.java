package datadog.trace.bootstrap.debugger;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Instrumented code helper class for calling back into Debugger Classloader Keeps track of debugger
 * service instances through interfaces implemented by class loaded into debugger classloader, but
 * accessible from instrumented code
 */
public class DebuggerContext {

  private static final Logger LOGGER = LoggerFactory.getLogger(DebuggerContext.class);

  public enum SkipCause {
    RATE,
    CONDITION
  }

  public interface Sink {
    void addSnapshot(Snapshot snapshot);

    void addDiagnostics(String probeId, List<DiagnosticMessage> messages);

    default void skipSnapshot(String probeId, SkipCause cause) {}
  }

  public interface ProbeResolver {
    Snapshot.ProbeDetails resolve(String id, Class<?> callingClass);
  }

  public interface ClassFilter {
    boolean isDenied(String fullyQualifiedClassName);
  }

  public interface MetricForwarder {
    void count(String name, long delta, String[] tags);

    void gauge(String name, long value, String[] tags);

    void histogram(String name, long value, String[] tags);
  }

  public interface Tracer {
    DebuggerSpan createSpan(String resourceName, String[] tags);
  }

  public interface SnapshotSerializer {
    String serializeSnapshot(String serviceName, Snapshot snapshot);

    String serializeValue(Snapshot.CapturedValue value);
  }

  private static volatile Sink sink;
  private static volatile ProbeResolver probeResolver;
  private static volatile ClassFilter classFilter;
  private static volatile MetricForwarder metricForwarder;
  private static volatile Tracer tracer;
  private static volatile SnapshotSerializer snapshotSerializer;

  public static void init(Sink sink, ProbeResolver probeResolver, MetricForwarder metricForwarder) {
    DebuggerContext.sink = sink;
    DebuggerContext.probeResolver = probeResolver;
    DebuggerContext.metricForwarder = metricForwarder;
  }

  public static void initTracer(Tracer tracer) {
    DebuggerContext.tracer = tracer;
  }

  public static void initClassFilter(ClassFilter classFilter) {
    DebuggerContext.classFilter = classFilter;
  }

  public static void initSnapshotSerializer(SnapshotSerializer snapshotSerializer) {
    DebuggerContext.snapshotSerializer = snapshotSerializer;
  }

  /**
   * Notifies the underlying sink that the snapshot was skipped for one of the SkipCause reason
   * No-op if no implementation available
   */
  public static void skipSnapshot(String probeId, SkipCause cause) {
    Sink localSink = sink;
    if (localSink == null) {
      return;
    }
    localSink.skipSnapshot(probeId, cause);
  }

  /** Adds a snapshot to the underlying sink No-op if no implementation available */
  public static void addSnapshot(Snapshot snapshot) {
    Sink localSink = sink;
    if (localSink == null) {
      return;
    }
    localSink.addSnapshot(snapshot);
  }

  /** Add diagnostics message to the underlying sink No-op if not implementation available */
  public static void reportDiagnostics(String probeId, List<DiagnosticMessage> messages) {
    Sink localSink = sink;
    if (localSink == null) {
      return;
    }
    localSink.addDiagnostics(probeId, messages);
  }

  /**
   * Returns the probe details based on the probe id provided. If no probe is found, try to
   * re-transform the class using the callingClass parameter No-op if no implementation available
   */
  public static Snapshot.ProbeDetails resolveProbe(String id, Class<?> callingClass) {
    ProbeResolver resolver = probeResolver;
    if (resolver == null) {
      return null;
    }
    return resolver.resolve(id, callingClass);
  }

  /**
   * Indicates if the fully-qualified-class-name is denied to be instrumented Returns true if no
   * implementation is available
   */
  public static boolean isDenied(String fullyQualifiedClassName) {
    ClassFilter filter = classFilter;
    if (filter == null) {
      LOGGER.warn("no class filter => all classes are denied");
      return true;
    }
    return filter.isDenied(fullyQualifiedClassName);
  }

  /** Increments the specified counter metric No-op if no implementation is available */
  public static void count(String name, long delta, String[] tags) {
    MetricForwarder forwarder = metricForwarder;
    if (forwarder == null) {
      return;
    }
    forwarder.count(name, delta, tags);
  }

  /** Updates the specified gauge metric No-op if no implementation is available */
  public static void gauge(String name, long value, String[] tags) {
    MetricForwarder forwarder = metricForwarder;
    if (forwarder == null) {
      return;
    }
    forwarder.gauge(name, value, tags);
  }

  /** Updates the specified histogram metric No-op if no implementation is available */
  public static void histogram(String name, long value, String[] tags) {
    MetricForwarder forwarder = metricForwarder;
    if (forwarder == null) {
      return;
    }
    forwarder.histogram(name, value, tags);
  }

  /** Serializes the specified Snapshot as string Returns null if no implementation is available */
  public static String serializeSnapshot(String serviceName, Snapshot snapshot) {
    SnapshotSerializer serializer = snapshotSerializer;
    if (serializer == null) {
      LOGGER.warn("Cannot serialize snapshots, no serializer set");
      return null;
    }
    return serializer.serializeSnapshot(serviceName, snapshot);
  }

  /** Serializes the specified value as string Returns null if no implementation is available */
  public static String serializeValue(Snapshot.CapturedValue value) {
    SnapshotSerializer serializer = snapshotSerializer;
    if (serializer == null) {
      LOGGER.warn("Cannot serialize value, no serializer set");
      return null;
    }
    return serializer.serializeValue(value);
  }

  /** Creates a span, returns null if no implementation available */
  public static DebuggerSpan createSpan(String operationName, String[] tags) {
    Tracer localTracer = tracer;
    if (localTracer == null) {
      return null;
    }
    return localTracer.createSpan(operationName, tags);
  }

  /**
   * tests whether the provided probe Ids are ready for capturing data
   *
   * @return true if can proceed to capture data
   */
  public static boolean isReadyToCapture(String... probeIds) {
    // TODO provide overloaded version without string array
    if (probeIds == null || probeIds.length == 0) {
      return false;
    }
    boolean result = false;
    for (String probeId : probeIds) {
      // if all probes are rate limited, we don't capture
      result |= ProbeRateLimiter.tryProbe(probeId);
    }
    return result;
  }

  /**
   * resolve probe details based on probe ids and evaluate the captured context regarding summary &
   * conditions This is for method probes
   */
  public static void evalContext(
      Snapshot.CapturedContext context,
      Class<?> callingClass,
      long startTimestamp,
      Snapshot.MethodLocation methodLocation,
      String... probeIds) {
    for (String probeId : probeIds) {
      Snapshot.ProbeDetails probeDetails = resolveProbe(probeId, callingClass);
      if (probeDetails == null) {
        continue;
      }
      context.evaluate(
          probeId, probeDetails, callingClass.getTypeName(), startTimestamp, methodLocation);
    }
  }

  /**
   * resolve probe details based on probe ids, evaluate the captured context regarding summary &
   * conditions and commit snapshot to send it if needed. This is for line probes.
   */
  public static void evalContextAndCommit(
      Snapshot.CapturedContext context, Class<?> callingClass, int line, String... probeIds) {
    for (String probeId : probeIds) {
      Snapshot.ProbeDetails probeDetails = resolveProbe(probeId, callingClass);
      if (probeDetails == null) {
        continue;
      }
      context.evaluate(
          probeId, probeDetails, callingClass.getTypeName(), -1, Snapshot.MethodLocation.DEFAULT);
      Snapshot snapshot = prepareForCommit(context, line, probeDetails);
      if (snapshot != null) {
        snapshot.commit();
      }
    }
  }

  /**
   * Commit snapshot based on entry/exit contexts and eventually caught exceptions for given probe
   * Ids This is for method probes
   */
  public static void commit(
      Snapshot.CapturedContext entryContext,
      Snapshot.CapturedContext exitContext,
      List<Snapshot.CapturedThrowable> caughtExceptions,
      String... probeIds) {
    if (entryContext == Snapshot.CapturedContext.EMPTY_CONTEXT
        && exitContext == Snapshot.CapturedContext.EMPTY_CONTEXT) {
      // rate limited
      return;
    }
    for (String probeId : probeIds) {
      Snapshot.CapturedContext.Status entryStatus = entryContext.getStatus(probeId);
      Snapshot.CapturedContext.Status exitStatus = exitContext.getStatus(probeId);
      Snapshot.ProbeDetails probeDetails;
      if (entryStatus.probeDetails != Snapshot.ProbeDetails.UNKNOWN
          && (entryStatus.probeDetails.getEvaluateAt() == Snapshot.MethodLocation.ENTRY
              || entryStatus.probeDetails.getEvaluateAt() == Snapshot.MethodLocation.DEFAULT)) {
        probeDetails = entryStatus.probeDetails;
      } else if (exitStatus.probeDetails.getEvaluateAt() == Snapshot.MethodLocation.EXIT) {
        probeDetails = exitStatus.probeDetails;
      } else {
        throw new IllegalStateException("no probe details");
      }
      boolean shouldCommit = false;
      Snapshot snapshot = new Snapshot(Thread.currentThread(), probeDetails);
      if (entryStatus.shouldSend() && exitStatus.shouldSend()) {
        // only rate limit if a condition is defined
        if (probeDetails.getScript() != null) {
          if (!ProbeRateLimiter.tryProbe(probeId)) {
            DebuggerContext.skipSnapshot(probeId, DebuggerContext.SkipCause.RATE);
            continue;
          }
        }
        if (probeDetails.isCaptureSnapshot()) {
          snapshot.setEntry(entryContext);
          snapshot.setExit(exitContext);
        }
        snapshot.setDuration(exitContext.getDuration());
        snapshot.addCaughtExceptions(caughtExceptions);
        shouldCommit = true;
      }
      if (entryStatus.shouldReportError()) {
        snapshot.addEvaluationErrors(entryStatus.errors);
        shouldCommit = true;
      }
      if (exitStatus.shouldReportError()) {
        snapshot.addEvaluationErrors(exitStatus.errors);
        shouldCommit = true;
      }
      if (shouldCommit) {
        snapshot.commit();
      } else {
        DebuggerContext.skipSnapshot(probeId, DebuggerContext.SkipCause.CONDITION);
      }
    }
  }

  /** Commit snapshot based on line context and the current probe This is for line probes */
  private static Snapshot prepareForCommit(
      Snapshot.CapturedContext lineContext, int line, Snapshot.ProbeDetails probeDetails) {
    Snapshot.CapturedContext.Status status = lineContext.getStatus(probeDetails.getId());
    if (status == null) {
      return null;
    }
    Snapshot snapshot = new Snapshot(Thread.currentThread(), probeDetails);
    boolean shouldCommit = false;
    if (status.shouldSend()) {
      // only rate limit if a condition is defined
      if (probeDetails.getScript() != null) {
        if (!ProbeRateLimiter.tryProbe(probeDetails.getId())) {
          DebuggerContext.skipSnapshot(probeDetails.getId(), DebuggerContext.SkipCause.RATE);
          return null;
        }
      }
      if (probeDetails.isSnapshotProbe()) {
        snapshot.addLine(lineContext, line);
      }
      shouldCommit = true;
    }
    if (status.shouldReportError()) {
      snapshot.addEvaluationErrors(status.errors);
      shouldCommit = true;
    }
    if (shouldCommit) {
      return snapshot;
    }
    DebuggerContext.skipSnapshot(probeDetails.getId(), DebuggerContext.SkipCause.CONDITION);
    return null;
  }
}
