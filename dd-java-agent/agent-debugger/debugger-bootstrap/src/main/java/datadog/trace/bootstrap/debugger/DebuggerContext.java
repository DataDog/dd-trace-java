package datadog.trace.bootstrap.debugger;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.util.TimeoutChecker;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
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
    RATE {
      @Override
      public String tag() {
        return "cause:rate";
      }
    },
    CONDITION {
      @Override
      public String tag() {
        return "cause:condition";
      }
    },
    DEBUG_SESSION_DISABLED {
      @Override
      public String tag() {
        return "cause:debug session disabled";
      }
    },
    BUDGET {
      @Override
      public String tag() {
        return "cause:budget_exceeded";
      }
    };

    public abstract String tag();
  }

  public interface ProbeResolver {
    ProbeImplementation resolve(int probeIndex);
  }

  public interface ClassFilter {
    boolean isDenied(String fullyQualifiedClassName);
  }

  public interface ClassNameFilter {
    boolean isExcluded(String className);
  }

  public enum MetricKind {
    COUNT,
    GAUGE,
    HISTOGRAM,
    DISTRIBUTION;
  }

  public interface MetricForwarder {
    void count(String encodedProbeId, String name, long delta, String[] tags);

    void gauge(String encodedProbeId, String name, long value, String[] tags);

    void gauge(String encodedProbeId, String name, double value, String[] tags);

    void histogram(String encodedProbeId, String name, long value, String[] tags);

    void histogram(String encodedProbeId, String name, double value, String[] tags);

    void distribution(String encodedProbeId, String name, long value, String[] tags);

    void distribution(String encodedProbeId, String name, double value, String[] tags);
  }

  public interface Tracer {
    DebuggerSpan createSpan(String encodedProbeId, String resourceName, String[] tags);
  }

  public interface ValueSerializer {
    String serializeValue(CapturedContext.CapturedValue value);
  }

  public interface ExceptionDebugger {
    void handleException(Throwable t, AgentSpan span);
  }

  public interface CodeOriginRecorder {
    String captureCodeOrigin(boolean entry);

    String captureCodeOrigin(Method method, boolean entry);
  }

  private static volatile ProbeResolver probeResolver;
  private static volatile ClassFilter classFilter;
  private static volatile ClassNameFilter classNameFilter;
  private static volatile MetricForwarder metricForwarder;
  private static volatile Tracer tracer;
  private static volatile ValueSerializer valueSerializer;
  private static volatile ExceptionDebugger exceptionDebugger;
  private static volatile CodeOriginRecorder codeOriginRecorder;

  public static void initProbeResolver(ProbeResolver probeResolver) {
    DebuggerContext.probeResolver = probeResolver;
  }

  public static void initMetricForwarder(MetricForwarder metricForwarder) {
    DebuggerContext.metricForwarder = metricForwarder;
  }

  public static void initTracer(Tracer tracer) {
    DebuggerContext.tracer = tracer;
  }

  public static void initClassFilter(ClassFilter classFilter) {
    DebuggerContext.classFilter = classFilter;
  }

  public static void initClassNameFilter(ClassNameFilter classNameFilter) {
    DebuggerContext.classNameFilter = classNameFilter;
  }

  public static void initValueSerializer(ValueSerializer valueSerializer) {
    DebuggerContext.valueSerializer = valueSerializer;
  }

  public static void initExceptionDebugger(ExceptionDebugger exceptionDebugger) {
    DebuggerContext.exceptionDebugger = exceptionDebugger;
  }

  public static void initCodeOrigin(CodeOriginRecorder codeOriginRecorder) {
    DebuggerContext.codeOriginRecorder = codeOriginRecorder;
  }

  /** Returns the probe details based on the probe idx provided. */
  public static ProbeImplementation resolveProbe(int probeIndex) {
    ProbeResolver resolver = probeResolver;
    if (resolver == null) {
      return null;
    }
    return resolver.resolve(probeIndex);
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
  public static void metric(
      String probeId, MetricKind kind, String name, long value, String[] tags) {
    try {
      MetricForwarder forwarder = metricForwarder;
      if (forwarder == null) {
        return;
      }
      switch (kind) {
        case COUNT:
          forwarder.count(probeId, name, value, tags);
          break;
        case GAUGE:
          forwarder.gauge(probeId, name, value, tags);
          break;
        case HISTOGRAM:
          forwarder.histogram(probeId, name, value, tags);
          break;
        case DISTRIBUTION:
          forwarder.distribution(probeId, name, value, tags);
          break;
        default:
          throw new IllegalArgumentException("Unsupported metric kind: " + kind);
      }
    } catch (Exception ex) {
      LOGGER.debug("Error in metric method: ", ex);
    }
  }

  /** Updates the specified metric No-op if no implementation is available */
  public static void metric(
      String probeId, MetricKind kind, String name, double value, String[] tags) {
    try {
      MetricForwarder forwarder = metricForwarder;
      if (forwarder == null) {
        return;
      }
      switch (kind) {
        case GAUGE:
          forwarder.gauge(probeId, name, value, tags);
          break;
        case HISTOGRAM:
          forwarder.histogram(probeId, name, value, tags);
          break;
        case DISTRIBUTION:
          forwarder.distribution(probeId, name, value, tags);
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
  public static DebuggerSpan createSpan(String probeId, String operationName, String[] tags) {
    try {
      Tracer localTracer = tracer;
      if (localTracer == null) {
        return DebuggerSpan.NOOP_SPAN;
      }
      return localTracer.createSpan(probeId, operationName, tags);
    } catch (Exception ex) {
      LOGGER.debug("Error in createSpan: ", ex);
      return DebuggerSpan.NOOP_SPAN;
    }
  }

  /**
   * tests whether the provided probe Ids are ready for capturing data it allows to skip capture
   * part if no condition and sampling is returns false
   *
   * @return true if can proceed to capture data
   */
  public static boolean isReadyToCapture(Class<?> callingClass, int... probeIndices) {
    try {
      return checkAndSetInProbe();
    } catch (Exception ex) {
      LOGGER.debug("Error in isReadyToCapture: ", ex);
      return false;
    }
  }

  public static boolean isReadyToCapture(Class<?> callingClass, int probeIndex) {
    try {
      ProbeImplementation probeImplementation = resolveProbe(probeIndex);
      if (probeImplementation == null) {
        return false;
      }
      if (((CapturedContextProbe) probeImplementation).isReadyToCapture()) {
        return checkAndSetInProbe();
      }
      return false;
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
   * conditions. This is for method probes.
   */
  public static void evalContext(
      CapturedContext context,
      Class<?> callingClass,
      long startTimestamp,
      MethodLocation methodLocation,
      int... probeIndices) {
    try {
      boolean needFreeze = false;
      for (int probeIndex : probeIndices) {
        ProbeImplementation probeImplementation = resolveProbe(probeIndex);
        if (probeImplementation == null) {
          continue;
        }
        CapturedContext.Status status =
            context.evaluate(
                probeImplementation,
                callingClass.getTypeName(),
                startTimestamp,
                methodLocation,
                false);
        needFreeze |= status.shouldFreezeContext();
      }
      // only freeze the context when we have at lest one snapshot probe, and we should send
      // snapshot
      if (needFreeze) {
        Duration timeout =
            Duration.of(Config.get().getDynamicInstrumentationCaptureTimeout(), ChronoUnit.MILLIS);
        context.freeze(new TimeoutChecker(timeout));
      }
    } catch (Exception ex) {
      LOGGER.debug("Error in evalContext: ", ex);
    }
  }

  /**
   * Optimized version of {@link DebuggerContext#evalContext(CapturedContext, Class, long,
   * MethodLocation, int...)} for single probe
   */
  public static void evalContext(
      CapturedContext context,
      Class<?> callingClass,
      long startTimestamp,
      MethodLocation methodLocation,
      int probeIndex) {
    try {
      ProbeImplementation probeImplementation = resolveProbe(probeIndex);
      if (probeImplementation == null) {
        return;
      }
      CapturedContext.Status status =
          context.evaluate(
              probeImplementation,
              callingClass.getTypeName(),
              startTimestamp,
              methodLocation,
              true);
      boolean needFreeze = status.shouldFreezeContext();
      // only freeze the context when we have at lest one snapshot probe, and we should send
      // snapshot
      if (needFreeze) {
        Duration timeout =
            Duration.of(Config.get().getDynamicInstrumentationCaptureTimeout(), ChronoUnit.MILLIS);
        context.freeze(new TimeoutChecker(timeout));
      }
    } catch (Exception ex) {
      LOGGER.debug("Error in evalContext: ", ex);
    }
  }

  /**
   * resolve probe details based on probe indices, evaluate the captured context regarding summary &
   * conditions and commit snapshot to send it if needed. This is for line probes.
   */
  public static void evalContextAndCommit(
      CapturedContext context, Class<?> callingClass, int line, int... probeIndices) {
    try {
      List<ProbeImplementation> probeImplementations = new ArrayList<>();
      for (int probeIndex : probeIndices) {
        ProbeImplementation probeImplementation = resolveProbe(probeIndex);
        if (probeImplementation == null) {
          continue;
        }
        context.evaluate(
            probeImplementation, callingClass.getTypeName(), -1, MethodLocation.DEFAULT, false);
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
   * Optimized version of {@link DebuggerContext#evalContextAndCommit(CapturedContext, Class, int,
   * int...)} for single probe
   */
  public static void evalContextAndCommit(
      CapturedContext context, Class<?> callingClass, int line, int probeIndex) {
    // Cannot call the multi probe version here, because it will add a new level for stacktrace
    // recording
    try {
      ProbeImplementation probeImplementation = resolveProbe(probeIndex);
      if (probeImplementation == null) {
        return;
      }
      context.evaluate(
          probeImplementation, callingClass.getTypeName(), -1, MethodLocation.DEFAULT, true);
      probeImplementation.commit(context, line);
    } catch (Exception ex) {
      LOGGER.debug("Error in evalContextAndCommit: ", ex);
    }
  }

  public static void codeOrigin(int probeIndex) {
    try {
      ProbeImplementation probe = probeResolver.resolve(probeIndex);
      if (probe != null) {
        probe.commit(
            CapturedContext.EMPTY_CONTEXT, CapturedContext.EMPTY_CONTEXT, Collections.emptyList());
      }
    } catch (Exception e) {
      LOGGER.debug("Error in codeOrigin: ", e);
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
      int... probeIndices) {
    try {
      if (entryContext == CapturedContext.EMPTY_CONTEXT
          && exitContext == CapturedContext.EMPTY_CONTEXT) {
        // rate limited
        return;
      }
      for (int probeIndex : probeIndices) {
        CapturedContext.Status entryStatus = entryContext.getStatus(probeIndex);
        CapturedContext.Status exitStatus = exitContext.getStatus(probeIndex);
        ProbeImplementation probeImplementation;
        if (entryStatus.probeImplementation != ProbeImplementation.UNKNOWN
            && (entryStatus.probeImplementation.getEvaluateAt() == MethodLocation.ENTRY
                || entryStatus.probeImplementation.getEvaluateAt() == MethodLocation.DEFAULT)) {
          probeImplementation = entryStatus.probeImplementation;
        } else if (exitStatus.probeImplementation.getEvaluateAt() == MethodLocation.EXIT) {
          probeImplementation = exitStatus.probeImplementation;
        } else {
          throw new IllegalStateException("no probe details for " + probeIndex);
        }
        probeImplementation.commit(entryContext, exitContext, caughtExceptions);
      }
    } catch (Exception ex) {
      LOGGER.debug("Error in commit: ", ex);
    }
  }

  /**
   * Optimized version of {@link DebuggerContext#commit(CapturedContext, CapturedContext, List,
   * int...)} for single probe
   */
  public static void commit(
      CapturedContext entryContext,
      CapturedContext exitContext,
      List<CapturedContext.CapturedThrowable> caughtExceptions,
      int probeIndex) {
    // Cannot call the multi probe version here, because it will add a new level for stacktrace
    // recording
    try {
      if (entryContext == CapturedContext.EMPTY_CONTEXT
          && exitContext == CapturedContext.EMPTY_CONTEXT) {
        // rate limited
        return;
      }
      CapturedContext.Status entryStatus = entryContext.getStatus(probeIndex);
      CapturedContext.Status exitStatus = exitContext.getStatus(probeIndex);
      ProbeImplementation probeImplementation;
      if (entryStatus.probeImplementation != ProbeImplementation.UNKNOWN
          && (entryStatus.probeImplementation.getEvaluateAt() == MethodLocation.ENTRY
              || entryStatus.probeImplementation.getEvaluateAt() == MethodLocation.DEFAULT)) {
        probeImplementation = entryStatus.probeImplementation;
      } else if (exitStatus.probeImplementation.getEvaluateAt() == MethodLocation.EXIT) {
        probeImplementation = exitStatus.probeImplementation;
      } else {
        throw new IllegalStateException("no probe details for " + probeIndex);
      }
      probeImplementation.commit(entryContext, exitContext, caughtExceptions);
    } catch (Exception ex) {
      LOGGER.debug("Error in commit: ", ex);
    }
  }

  public static void marker() {}

  public static void captureCodeOrigin(boolean entry) {
    try {
      CodeOriginRecorder recorder = codeOriginRecorder;
      if (recorder != null) {
        recorder.captureCodeOrigin(entry);
      }
    } catch (Exception ex) {
      LOGGER.debug("Error in captureCodeOrigin: ", ex);
    }
  }

  public static void captureCodeOrigin(Method method, boolean entry) {
    try {
      CodeOriginRecorder recorder = codeOriginRecorder;
      if (recorder != null) {
        recorder.captureCodeOrigin(method, entry);
      }
    } catch (Exception ex) {
      LOGGER.debug("Error in captureCodeOrigin: ", ex);
    }
  }

  public static void handleException(Throwable t, AgentSpan span) {
    try {
      ExceptionDebugger exDebugger = exceptionDebugger;
      if (exDebugger == null) {
        return;
      }
      exDebugger.handleException(t, span);
    } catch (Exception ex) {
      LOGGER.debug("Error in handleException: ", ex);
    }
  }

  public static boolean isClassNameExcluded(String className) {
    try {
      return classNameFilter != null && classNameFilter.isExcluded(className);
    } catch (Exception ex) {
      LOGGER.debug("Error in isClassNameExcluded: ", ex);
      return false;
    }
  }
}
