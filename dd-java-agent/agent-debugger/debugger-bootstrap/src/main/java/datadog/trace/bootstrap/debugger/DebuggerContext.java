package datadog.trace.bootstrap.debugger;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.util.TimeoutChecker;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
  private static final ThreadLocal<Boolean> IN_PROBE = ThreadLocal.withInitial(() -> Boolean.FALSE);

  public enum SkipCause {
    RATE,
    CONDITION
  }

  public interface ProbeResolver {
    ProbeImplementation resolve(String id, Class<?> callingClass);
  }

  public interface ClassFilter {
    boolean isDenied(String fullyQualifiedClassName);
  }

  public enum MetricKind {
    COUNT,
    GAUGE,
    HISTOGRAM,
    DISTRIBUTION;
  }

  public interface MetricForwarder {
    void count(String name, long delta, String[] tags);

    void gauge(String name, long value, String[] tags);

    void gauge(String name, double value, String[] tags);

    void histogram(String name, long value, String[] tags);

    void histogram(String name, double value, String[] tags);

    void distribution(String name, long value, String[] tags);

    void distribution(String name, double value, String[] tags);
  }

  public interface Tracer {
    DebuggerSpan createSpan(String resourceName, String[] tags);
  }

  public interface ValueSerializer {
    String serializeValue(CapturedContext.CapturedValue value);
  }

  private static volatile ProbeResolver probeResolver;
  private static volatile ClassFilter classFilter;
  private static volatile MetricForwarder metricForwarder;
  private static volatile Tracer tracer;
  private static volatile ValueSerializer valueSerializer;

  public static void init(ProbeResolver probeResolver, MetricForwarder metricForwarder) {
    DebuggerContext.probeResolver = probeResolver;
    DebuggerContext.metricForwarder = metricForwarder;
  }

  public static void initTracer(Tracer tracer) {
    DebuggerContext.tracer = tracer;
  }

  public static void initClassFilter(ClassFilter classFilter) {
    DebuggerContext.classFilter = classFilter;
  }

  public static void initValueSerializer(ValueSerializer valueSerializer) {
    DebuggerContext.valueSerializer = valueSerializer;
  }

  /**
   * Returns the probe details based on the probe id provided. If no probe is found, try to
   * re-transform the class using the callingClass parameter No-op if no implementation available
   */
  public static ProbeImplementation resolveProbe(String id, Class<?> callingClass) {
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

  /** Increments or updates the specified metric No-op if no implementation is available */
  public static void metric(MetricKind kind, String name, long value, String[] tags) {
    try {
      MetricForwarder forwarder = metricForwarder;
      if (forwarder == null) {
        return;
      }
      switch (kind) {
        case COUNT:
          forwarder.count(name, value, tags);
          break;
        case GAUGE:
          forwarder.gauge(name, value, tags);
          break;
        case HISTOGRAM:
          forwarder.histogram(name, value, tags);
          break;
        case DISTRIBUTION:
          forwarder.distribution(name, value, tags);
        default:
          throw new IllegalArgumentException("Unsupported metric kind: " + kind);
      }
    } catch (Exception ex) {
      LOGGER.debug("Error in metric method: ", ex);
    }
  }

  /** Updates the specified metric No-op if no implementation is available */
  public static void metric(MetricKind kind, String name, double value, String[] tags) {
    try {
      MetricForwarder forwarder = metricForwarder;
      if (forwarder == null) {
        return;
      }
      switch (kind) {
        case GAUGE:
          forwarder.gauge(name, value, tags);
          break;
        case HISTOGRAM:
          forwarder.histogram(name, value, tags);
          break;
        case DISTRIBUTION:
          forwarder.distribution(name, value, tags);
          break;
        default:
          throw new IllegalArgumentException("Unsupported metric kind: " + kind);
      }
    } catch (Exception ex) {
      LOGGER.debug("Error in metric method: ", ex);
    }
  }

  /** Serializes the specified value as string Returns null if no implementation is available */
  public static String serializeValue(CapturedContext.CapturedValue value) {
    ValueSerializer serializer = valueSerializer;
    if (serializer == null) {
      LOGGER.warn("Cannot serialize value, no serializer set");
      return null;
    }
    return serializer.serializeValue(value);
  }

  /** Creates a span, returns null if no implementation available */
  public static DebuggerSpan createSpan(String operationName, String[] tags) {
    try {
      Tracer localTracer = tracer;
      if (localTracer == null) {
        return DebuggerSpan.NOOP_SPAN;
      }
      return localTracer.createSpan(operationName, tags);
    } catch (Exception ex) {
      LOGGER.debug("Error in createSpan: ", ex);
      return DebuggerSpan.NOOP_SPAN;
    }
  }

  /**
   * tests whether the provided probe Ids are ready for capturing data
   *
   * @return true if can proceed to capture data
   */
  public static boolean isReadyToCapture(String... probeIds) {
    // TODO provide overloaded version without string array
    try {
      if (probeIds == null || probeIds.length == 0) {
        return false;
      }
      boolean result = false;
      for (String probeId : probeIds) {
        // if all probes are rate limited, we don't capture
        result |= ProbeRateLimiter.tryProbe(probeId);
      }
      result = result && checkAndSetInProbe();
      return result;
    } catch (Exception ex) {
      LOGGER.debug("Error in isReadyToCapture: ", ex);
      return false;
    }
  }

  public static void disableInProbe() {
    IN_PROBE.set(Boolean.FALSE);
  }

  public static boolean isInProbe() {
    return IN_PROBE.get();
  }

  public static boolean checkAndSetInProbe() {
    if (IN_PROBE.get()) {
      LOGGER.debug("Instrumentation is reentered, skip it.");
      return false;
    }
    IN_PROBE.set(Boolean.TRUE);
    return true;
  }
  /**
   * resolve probe details based on probe ids and evaluate the captured context regarding summary &
   * conditions This is for method probes
   */
  public static void evalContext(
      CapturedContext context,
      Class<?> callingClass,
      long startTimestamp,
      MethodLocation methodLocation,
      String... probeIds) {
    try {
      boolean needFreeze = false;
      for (String probeId : probeIds) {
        ProbeImplementation probeImplementation = resolveProbe(probeId, callingClass);
        if (probeImplementation == null) {
          continue;
        }
        CapturedContext.Status status =
            context.evaluate(
                probeId,
                probeImplementation,
                callingClass.getTypeName(),
                startTimestamp,
                methodLocation);
        needFreeze |= status.shouldFreezeContext();
      }
      // only freeze the context when we have at lest one snapshot probe, and we should send
      // snapshot
      if (needFreeze) {
        Duration timeout = Duration.of(Config.get().getDebuggerCaptureTimeout(), ChronoUnit.MILLIS);
        context.freeze(new TimeoutChecker(timeout));
      }
    } catch (Exception ex) {
      LOGGER.debug("Error in evalContext: ", ex);
    }
  }

  /**
   * resolve probe details based on probe ids, evaluate the captured context regarding summary &
   * conditions and commit snapshot to send it if needed. This is for line probes.
   */
  public static void evalContextAndCommit(
      CapturedContext context, Class<?> callingClass, int line, String... probeIds) {
    try {
      List<ProbeImplementation> probeImplementations = new ArrayList<>();
      for (String probeId : probeIds) {
        ProbeImplementation probeImplementation = resolveProbe(probeId, callingClass);
        if (probeImplementation == null) {
          continue;
        }
        context.evaluate(
            probeId, probeImplementation, callingClass.getTypeName(), -1, MethodLocation.DEFAULT);
        probeImplementations.add(probeImplementation);
      }
      for (ProbeImplementation probeImplementation : probeImplementations) {
        probeImplementation.commit(context, line);
      }
    } catch (Exception ex) {
      LOGGER.debug("Error in evalContextAndCommit: ", ex);
    }
  }

  /**
   * Commit snapshot based on entry/exit contexts and eventually caught exceptions for given probe
   * Ids This is for method probes
   */
  public static void commit(
      CapturedContext entryContext,
      CapturedContext exitContext,
      List<CapturedContext.CapturedThrowable> caughtExceptions,
      String... probeIds) {
    try {
      if (entryContext == CapturedContext.EMPTY_CONTEXT
          && exitContext == CapturedContext.EMPTY_CONTEXT) {
        // rate limited
        return;
      }
      for (String probeId : probeIds) {
        CapturedContext.Status entryStatus = entryContext.getStatus(probeId);
        CapturedContext.Status exitStatus = exitContext.getStatus(probeId);
        ProbeImplementation probeImplementation;
        if (entryStatus.probeImplementation != ProbeImplementation.UNKNOWN
            && (entryStatus.probeImplementation.getEvaluateAt() == MethodLocation.ENTRY
                || entryStatus.probeImplementation.getEvaluateAt() == MethodLocation.DEFAULT)) {
          probeImplementation = entryStatus.probeImplementation;
        } else if (exitStatus.probeImplementation.getEvaluateAt() == MethodLocation.EXIT) {
          probeImplementation = exitStatus.probeImplementation;
        } else {
          throw new IllegalStateException("no probe details");
        }
        probeImplementation.commit(entryContext, exitContext, caughtExceptions);
      }
    } catch (Exception ex) {
      LOGGER.debug("Error in commit: ", ex);
    }
  }
}
