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

  private static volatile Sink sink;
  private static volatile ProbeResolver probeResolver;
  private static volatile ClassFilter classFilter;
  private static volatile MetricForwarder metricForwarder;

  public static void init(Sink sink, ProbeResolver probeResolver, MetricForwarder metricForwarder) {
    DebuggerContext.sink = sink;
    DebuggerContext.probeResolver = probeResolver;
    DebuggerContext.metricForwarder = metricForwarder;
  }

  public static void initClassFilter(ClassFilter classFilter) {
    DebuggerContext.classFilter = classFilter;
  }

  public static void skipSnapshot(String probeId, SkipCause cause) {
    Sink localSink = sink;
    if (localSink == null) {
      return;
    }
    localSink.skipSnapshot(probeId, cause);
  }

  public static void addSnapshot(Snapshot snapshot) {
    Sink localSink = sink;
    if (localSink == null) {
      return;
    }
    localSink.addSnapshot(snapshot);
  }

  public static void reportDiagnostics(String probeId, List<DiagnosticMessage> messages) {
    Sink localSink = sink;
    if (localSink == null) {
      return;
    }
    localSink.addDiagnostics(probeId, messages);
  }

  public static Snapshot.ProbeDetails resolveProbe(String id, Class<?> callingClass) {
    ProbeResolver resolver = probeResolver;
    if (resolver == null) {
      return null;
    }
    return resolver.resolve(id, callingClass);
  }

  public static boolean isDenied(String fullyQualifiedClassName) {
    ClassFilter filter = classFilter;
    if (filter == null) {
      LOGGER.warn("no class filter => all classes are denied");
      return true;
    }
    return filter.isDenied(fullyQualifiedClassName);
  }

  public static void count(String name, long delta, String[] tags) {
    MetricForwarder forwarder = metricForwarder;
    if (forwarder == null) {
      return;
    }
    forwarder.count(name, delta, tags);
  }

  public static void gauge(String name, long value, String[] tags) {
    MetricForwarder forwarder = metricForwarder;
    if (forwarder == null) {
      return;
    }
    forwarder.gauge(name, value, tags);
  }

  public static void histogram(String name, long value, String[] tags) {
    MetricForwarder forwarder = metricForwarder;
    if (forwarder == null) {
      return;
    }
    forwarder.histogram(name, value, tags);
  }
}
