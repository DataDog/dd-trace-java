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

  public interface SnapshotSerializer {
    String serializeSnapshot(String serviceName, Snapshot snapshot);

    String serializeValue(Snapshot.CapturedValue value);
  }

  private static volatile Sink sink;
  private static volatile ProbeResolver probeResolver;
  private static volatile ClassFilter classFilter;
  private static volatile MetricForwarder metricForwarder;
  private static volatile SnapshotSerializer snapshotSerializer;

  public static void init(Sink sink, ProbeResolver probeResolver, MetricForwarder metricForwarder) {
    DebuggerContext.sink = sink;
    DebuggerContext.probeResolver = probeResolver;
    DebuggerContext.metricForwarder = metricForwarder;
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
   * Indicates if the fully-qualifed-class-name is denied to be intstrumented Returns true if no
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
}
