package datadog.trace.agent.jmxfetch;

import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.COUNTER;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.GAUGE;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.HISTOGRAM;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.UP_DOWN_COUNTER;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.OperatingSystemMXBean;
import com.sun.management.UnixOperatingSystemMXBean;
import datadog.trace.bootstrap.otel.api.common.AttributeKey;
import datadog.trace.bootstrap.otel.api.common.Attributes;
import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import datadog.trace.bootstrap.otel.metrics.OtelInstrumentBuilder;
import datadog.trace.bootstrap.otel.metrics.OtelInstrumentDescriptor;
import datadog.trace.bootstrap.otel.metrics.OtelInstrumentType;
import datadog.trace.bootstrap.otel.metrics.data.OtelMetricRegistry;
import datadog.trace.bootstrap.otel.metrics.data.OtelMetricStorage;
import datadog.trace.bootstrap.otel.metrics.data.OtelRunnableObservable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers JVM runtime metrics with OTel-native names against the agent's bootstrap-level metric
 * registry.
 */
public final class JvmOtlpRuntimeMetrics {
  private static final Logger log = LoggerFactory.getLogger(JvmOtlpRuntimeMetrics.class);

  private static final OtelInstrumentationScope JVM_SCOPE =
      new OtelInstrumentationScope("datadog.jvm.runtime", null, null);

  private static final AttributeKey<String> MEMORY_TYPE = AttributeKey.stringKey("jvm.memory.type");
  private static final AttributeKey<String> MEMORY_POOL =
      AttributeKey.stringKey("jvm.memory.pool.name");
  private static final AttributeKey<String> BUFFER_POOL =
      AttributeKey.stringKey("jvm.buffer.pool.name");
  private static final AttributeKey<String> GC_NAME = AttributeKey.stringKey("jvm.gc.name");
  private static final AttributeKey<String> GC_ACTION = AttributeKey.stringKey("jvm.gc.action");
  private static final AttributeKey<String> GC_CAUSE = AttributeKey.stringKey("jvm.gc.cause");
  private static final AttributeKey<Boolean> THREAD_DAEMON =
      AttributeKey.booleanKey("jvm.thread.daemon");
  private static final AttributeKey<String> THREAD_STATE =
      AttributeKey.stringKey("jvm.thread.state");
  private static final Attributes HEAP_ATTRS = Attributes.of(MEMORY_TYPE, "heap");
  private static final Attributes NON_HEAP_ATTRS = Attributes.of(MEMORY_TYPE, "non_heap");

  /**
   * Precomputed Attributes for each (daemon, Thread.State) pair, used by jvm.thread.count. There
   * are only 12 combinations, so caching avoids per-poll allocation of identical Attribute objects.
   */
  private static final Attributes[] DAEMON_THREAD_STATE_ATTRS = buildThreadStateAttrs(true);

  private static final Attributes[] NON_DAEMON_THREAD_STATE_ATTRS = buildThreadStateAttrs(false);

  private static Attributes[] buildThreadStateAttrs(boolean daemon) {
    Thread.State[] states = Thread.State.values();
    Attributes[] result = new Attributes[states.length];
    for (Thread.State state : states) {
      result[state.ordinal()] =
          Attributes.of(THREAD_DAEMON, daemon, THREAD_STATE, state.name().toLowerCase(Locale.ROOT));
    }
    return result;
  }

  /**
   * MethodHandle for {@code ThreadInfo#isDaemon()}: non-null on Java 9+, null on Java 8. Doubles as
   * the Java-version probe since this code is compiled against Java 8 and cannot reference the
   * symbol directly.
   */
  private static final MethodHandle THREAD_INFO_IS_DAEMON = resolveThreadInfoIsDaemon();

  private static final ThreadMXBean THREAD_BEAN = ManagementFactory.getThreadMXBean();

  /**
   * jvm.thread.count collector, chosen once at class load. Java 9+ uses {@link
   * ThreadMXBean#getThreadInfo(long[])} (the single-arg overload omits stack-trace capture); Java 8
   * (and GraalVM native image, where ThreadMXBean is unsupported) walks the root {@link
   * ThreadGroup}. Avoids {@link Thread#getAllStackTraces()}, which forces a safepoint and allocates
   * a {@code StackTraceElement[]} per thread on every poll.
   */
  private static final Consumer<OtelMetricStorage> THREAD_COUNT_COLLECTOR =
      chooseThreadCountCollector();

  private static MethodHandle resolveThreadInfoIsDaemon() {
    try {
      return MethodHandles.publicLookup()
          .findVirtual(ThreadInfo.class, "isDaemon", MethodType.methodType(boolean.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      return null; // Java 8 — fall back to ThreadGroup walk
    }
  }

  private static Consumer<OtelMetricStorage> chooseThreadCountCollector() {
    boolean isJava9OrNewer = THREAD_INFO_IS_DAEMON != null;
    boolean isNativeImage = System.getProperty("org.graalvm.nativeimage.imagecode") != null;
    if (isJava9OrNewer && !isNativeImage) {
      return JvmOtlpRuntimeMetrics::collectThreadCountsViaThreadMXBean;
    }
    return JvmOtlpRuntimeMetrics::collectThreadCountsViaThreadGroup;
  }

  /** Explicit bucket advice for jvm.gc.duration in seconds (matches OTel runtime-telemetry). */
  private static final List<Double> GC_DURATION_BUCKETS = Arrays.asList(0.01, 0.1, 1.0, 10.0);

  private static final String GC_NOTIFICATION_TYPE = "com.sun.management.gc.notification";

  private static final AtomicBoolean started = new AtomicBoolean(false);

  /**
   * Registers all JVM runtime metric instruments on the bootstrap-level metric registry.
   *
   * @param emitExperimentalMetrics when {@code true} (the spec-aligned default), metrics marked as
   *     <em>Development</em> in the OTel semantic conventions are also registered. When {@code
   *     false}, only metrics with stable status are emitted.
   */
  public static void start(boolean emitExperimentalMetrics) {
    if (!started.compareAndSet(false, true)) {
      return;
    }

    try {
      // Ensure OtelMetricStorage can serialize io.opentelemetry.api Attributes recorded below;
      // the otel-shim registers an equivalent reader on its own class-loader, but agent-jmxfetch
      // does not depend on the shim — so we register one here for our Attributes class-loader.
      OtelMetricStorage.registerAttributeReader(
          Attributes.class.getClassLoader(),
          (attributes, visitor) ->
              ((Attributes) attributes)
                  .forEach((a, v) -> visitor.visitAttribute(a.getType().ordinal(), a.getKey(), v)));

      // Stable metrics — always registered.
      registerMemoryMetrics();
      registerThreadMetrics();
      registerClassLoadingMetrics();
      registerCpuMetrics();
      registerGcDurationMetric(emitExperimentalMetrics);

      // Development-status metrics — gated by the experimental flag.
      if (emitExperimentalMetrics) {
        registerMemoryInitMetric();
        registerBufferMetrics();
        registerSystemCpuMetrics();
        registerFileDescriptorMetrics();
      }
      log.debug(
          "Started OTLP runtime metrics with OTel-native naming (jvm.*), experimental={}",
          emitExperimentalMetrics);
    } catch (Exception e) {
      log.error("Failed to start JVM OTLP runtime metrics", e);
    }
  }

  /**
   * jvm.memory.used, jvm.memory.committed, jvm.memory.limit, jvm.memory.used_after_last_gc — all
   * Stable per spec. All UpDownCounter.
   */
  private static void registerMemoryMetrics() {
    MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();

    registerLongObservable(
        "jvm.memory.used",
        "Measure of memory used.",
        "By",
        UP_DOWN_COUNTER,
        storage -> {
          storage.recordLong(memoryBean.getHeapMemoryUsage().getUsed(), HEAP_ATTRS);
          storage.recordLong(memoryBean.getNonHeapMemoryUsage().getUsed(), NON_HEAP_ATTRS);
          for (MemoryPoolMXBean pool : pools) {
            storage.recordLong(pool.getUsage().getUsed(), poolAttributes(pool));
          }
        });

    registerLongObservable(
        "jvm.memory.committed",
        "Measure of memory committed.",
        "By",
        UP_DOWN_COUNTER,
        storage -> {
          storage.recordLong(memoryBean.getHeapMemoryUsage().getCommitted(), HEAP_ATTRS);
          storage.recordLong(memoryBean.getNonHeapMemoryUsage().getCommitted(), NON_HEAP_ATTRS);
          for (MemoryPoolMXBean pool : pools) {
            storage.recordLong(pool.getUsage().getCommitted(), poolAttributes(pool));
          }
        });

    registerLongObservable(
        "jvm.memory.limit",
        "Measure of max obtainable memory.",
        "By",
        UP_DOWN_COUNTER,
        storage -> {
          long heapMax = memoryBean.getHeapMemoryUsage().getMax();
          if (heapMax != -1) {
            storage.recordLong(heapMax, HEAP_ATTRS);
          }
          long nonHeapMax = memoryBean.getNonHeapMemoryUsage().getMax();
          if (nonHeapMax != -1) {
            storage.recordLong(nonHeapMax, NON_HEAP_ATTRS);
          }
          for (MemoryPoolMXBean pool : pools) {
            long max = pool.getUsage().getMax();
            if (max != -1) {
              storage.recordLong(max, poolAttributes(pool));
            }
          }
        });

    registerLongObservable(
        "jvm.memory.used_after_last_gc",
        "Measure of memory used after the most recent garbage collection event.",
        "By",
        UP_DOWN_COUNTER,
        storage -> {
          for (MemoryPoolMXBean pool : pools) {
            MemoryUsage collectionUsage = pool.getCollectionUsage();
            if (collectionUsage != null && collectionUsage.getUsed() >= 0) {
              storage.recordLong(collectionUsage.getUsed(), poolAttributes(pool));
            }
          }
        });
  }

  /** jvm.memory.init (UpDownCounter, Development). */
  private static void registerMemoryInitMetric() {
    MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
    registerLongObservable(
        "jvm.memory.init",
        "Measure of initial memory requested.",
        "By",
        UP_DOWN_COUNTER,
        storage -> {
          long heapInit = memoryBean.getHeapMemoryUsage().getInit();
          if (heapInit != -1) {
            storage.recordLong(heapInit, HEAP_ATTRS);
          }
          long nonHeapInit = memoryBean.getNonHeapMemoryUsage().getInit();
          if (nonHeapInit != -1) {
            storage.recordLong(nonHeapInit, NON_HEAP_ATTRS);
          }
          for (MemoryPoolMXBean pool : pools) {
            long init = pool.getUsage().getInit();
            if (init != -1) {
              storage.recordLong(init, poolAttributes(pool));
            }
          }
        });
  }

  /** jvm.buffer.* (UpDownCounter, Development) — direct + mapped pool metrics. */
  private static void registerBufferMetrics() {
    List<BufferPoolMXBean> bufferPools =
        ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
    bufferPoolMetric(
        "jvm.buffer.memory.used",
        "Measure of memory used by buffers.",
        "By",
        bufferPools,
        BufferPoolMXBean::getMemoryUsed);
    bufferPoolMetric(
        "jvm.buffer.memory.limit",
        "Measure of total memory capacity of buffers.",
        "By",
        bufferPools,
        BufferPoolMXBean::getTotalCapacity);
    bufferPoolMetric(
        "jvm.buffer.count",
        "Number of buffers in the pool.",
        "{buffer}",
        bufferPools,
        BufferPoolMXBean::getCount);
  }

  /**
   * jvm.thread.count (UpDownCounter, Stable). Bucketed by {@code jvm.thread.daemon} and {@code
   * jvm.thread.state} per the OTel JVM semantic conventions.
   */
  private static void registerThreadMetrics() {
    registerLongObservable(
        "jvm.thread.count",
        "Number of executing platform threads.",
        "{thread}",
        UP_DOWN_COUNTER,
        THREAD_COUNT_COLLECTOR);
  }

  /**
   * Java 9+ path. Enumerates threads via {@link ThreadMXBean#getThreadInfo(long[])}; the single-arg
   * overload omits stack-trace capture, avoiding the safepoint and per-frame allocation incurred by
   * {@link Thread#getAllStackTraces()}.
   */
  private static void collectThreadCountsViaThreadMXBean(OtelMetricStorage storage) {
    Map<Thread.State, long[]> daemonCounts = new EnumMap<>(Thread.State.class);
    Map<Thread.State, long[]> nonDaemonCounts = new EnumMap<>(Thread.State.class);
    long[] ids = THREAD_BEAN.getAllThreadIds();
    for (ThreadInfo info : THREAD_BEAN.getThreadInfo(ids)) {
      if (info == null) {
        continue; // thread terminated between getAllThreadIds and getThreadInfo
      }
      Map<Thread.State, long[]> bucket = threadInfoIsDaemon(info) ? daemonCounts : nonDaemonCounts;
      bucket.computeIfAbsent(info.getThreadState(), k -> new long[1])[0]++;
    }
    recordThreadStateCounts(storage, daemonCounts, DAEMON_THREAD_STATE_ATTRS);
    recordThreadStateCounts(storage, nonDaemonCounts, NON_DAEMON_THREAD_STATE_ATTRS);
  }

  /**
   * Java 8 / GraalVM fallback. Walks the root {@link ThreadGroup} because {@code
   * ThreadInfo.isDaemon()} was added in Java 9 and {@link ThreadMXBean} is not supported on GraalVM
   * native images.
   */
  private static void collectThreadCountsViaThreadGroup(OtelMetricStorage storage) {
    Map<Thread.State, long[]> daemonCounts = new EnumMap<>(Thread.State.class);
    Map<Thread.State, long[]> nonDaemonCounts = new EnumMap<>(Thread.State.class);
    for (Thread thread : enumerateAllThreads()) {
      Map<Thread.State, long[]> bucket = thread.isDaemon() ? daemonCounts : nonDaemonCounts;
      bucket.computeIfAbsent(thread.getState(), k -> new long[1])[0]++;
    }
    recordThreadStateCounts(storage, daemonCounts, DAEMON_THREAD_STATE_ATTRS);
    recordThreadStateCounts(storage, nonDaemonCounts, NON_DAEMON_THREAD_STATE_ATTRS);
  }

  /** Invokes {@code ThreadInfo#isDaemon()} via {@link #THREAD_INFO_IS_DAEMON} (Java 9+ only). */
  private static boolean threadInfoIsDaemon(ThreadInfo info) {
    try {
      return (boolean) THREAD_INFO_IS_DAEMON.invoke(info);
    } catch (Throwable t) {
      throw new IllegalStateException("Unexpected error invoking ThreadInfo#isDaemon()", t);
    }
  }

  /**
   * Walks the root {@link ThreadGroup} and returns a snapshot of active threads. Allocates a
   * slightly oversized buffer to absorb threads created between {@code activeCount()} and {@code
   * enumerate()}; if the buffer is still too small the returned array may be truncated.
   */
  private static Thread[] enumerateAllThreads() {
    ThreadGroup group = Thread.currentThread().getThreadGroup();
    // ThreadGroup.enumerate() recursively descends through children by default, so enumerating from
    // the root gives every live thread in the JVM.
    while (group.getParent() != null) {
      group = group.getParent();
    }
    Thread[] buffer = new Thread[group.activeCount() + 10];
    int n = group.enumerate(buffer);
    if (n == buffer.length) {
      return buffer;
    }
    Thread[] trimmed = new Thread[n];
    System.arraycopy(buffer, 0, trimmed, 0, n);
    return trimmed;
  }

  private static void recordThreadStateCounts(
      OtelMetricStorage storage, Map<Thread.State, long[]> counts, Attributes[] attrsByState) {
    for (Map.Entry<Thread.State, long[]> entry : counts.entrySet()) {
      storage.recordLong(entry.getValue()[0], attrsByState[entry.getKey().ordinal()]);
    }
  }

  /**
   * jvm.class.loaded (Counter), jvm.class.unloaded (Counter), jvm.class.count (UpDownCounter) — all
   * Stable per spec.
   */
  private static void registerClassLoadingMetrics() {
    ClassLoadingMXBean classLoadingBean = ManagementFactory.getClassLoadingMXBean();
    registerLongObservable(
        "jvm.class.loaded",
        "Number of classes loaded since JVM start.",
        "{class}",
        COUNTER,
        storage ->
            storage.recordLong(classLoadingBean.getTotalLoadedClassCount(), Attributes.empty()));

    registerLongObservable(
        "jvm.class.count",
        "Number of classes currently loaded.",
        "{class}",
        UP_DOWN_COUNTER,
        storage -> storage.recordLong(classLoadingBean.getLoadedClassCount(), Attributes.empty()));

    registerLongObservable(
        "jvm.class.unloaded",
        "Number of classes unloaded since JVM start.",
        "{class}",
        COUNTER,
        storage ->
            storage.recordLong(classLoadingBean.getUnloadedClassCount(), Attributes.empty()));
  }

  /**
   * jvm.cpu.time (Counter), jvm.cpu.count (UpDownCounter), jvm.cpu.recent_utilization (Gauge) — all
   * Stable per spec.
   */
  private static void registerCpuMetrics() {
    java.lang.management.OperatingSystemMXBean rawOsBean =
        ManagementFactory.getOperatingSystemMXBean();
    if (rawOsBean instanceof OperatingSystemMXBean) {
      OperatingSystemMXBean sunOsBean = (OperatingSystemMXBean) rawOsBean;
      registerDoubleObservable(
          "jvm.cpu.time",
          "CPU time used by the process as reported by the JVM.",
          "s",
          COUNTER,
          storage -> {
            long nanos = sunOsBean.getProcessCpuTime();
            if (nanos >= 0) {
              storage.recordDouble(nanos / 1e9, Attributes.empty());
            }
          });

      registerDoubleObservable(
          "jvm.cpu.recent_utilization",
          "Recent CPU utilization for the process as reported by the JVM.",
          "1",
          GAUGE,
          storage -> {
            double cpuLoad = sunOsBean.getProcessCpuLoad();
            if (cpuLoad >= 0) {
              storage.recordDouble(cpuLoad, Attributes.empty());
            }
          });
    } else {
      log.debug(
          "com.sun.management.OperatingSystemMXBean not available; skipping jvm.cpu.time and jvm.cpu.recent_utilization");
    }

    registerLongObservable(
        "jvm.cpu.count",
        "Number of processors available to the JVM.",
        "{cpu}",
        UP_DOWN_COUNTER,
        storage ->
            storage.recordLong(Runtime.getRuntime().availableProcessors(), Attributes.empty()));
  }

  /**
   * jvm.gc.duration (Histogram, Stable) — synchronous; recorded from a JMX notification listener
   * attached to each {@link GarbageCollectorMXBean} when the JVM completes a GC.
   *
   * <p>The {@code jvm.gc.cause} attribute is gated on {@code captureGcCause} because cause is not
   * part of the stable attribute set in the OTel semantic conventions.
   */
  private static void registerGcDurationMetric(boolean captureGcCause) {
    if (!isGcNotificationInfoAvailable()) {
      log.debug(
          "com.sun.management.GarbageCollectionNotificationInfo not available; skipping jvm.gc.duration");
      return;
    }
    OtelMetricStorage storage =
        registerDoubleHistogramStorage(
            "jvm.gc.duration",
            "Duration of JVM garbage collection actions.",
            "s",
            GC_DURATION_BUCKETS);
    NotificationFilter filter = n -> GC_NOTIFICATION_TYPE.equals(n.getType());
    GcNotificationListener listener = new GcNotificationListener(storage, captureGcCause);
    for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
      if (bean instanceof NotificationEmitter) {
        ((NotificationEmitter) bean).addNotificationListener(listener, filter, null);
      }
    }
  }

  private static boolean isGcNotificationInfoAvailable() {
    try {
      Class.forName(
          "com.sun.management.GarbageCollectionNotificationInfo",
          false,
          GarbageCollectorMXBean.class.getClassLoader());
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private static void recordGcDuration(
      OtelMetricStorage storage, GarbageCollectionNotificationInfo info, boolean captureGcCause) {
    double durationSeconds = info.getGcInfo().getDuration() / 1000d;
    Attributes attrs =
        captureGcCause
            ? Attributes.of(
                GC_NAME, info.getGcName(),
                GC_ACTION, info.getGcAction(),
                GC_CAUSE, info.getGcCause())
            : Attributes.of(
                GC_NAME, info.getGcName(),
                GC_ACTION, info.getGcAction());
    storage.recordDouble(durationSeconds, attrs);
  }

  /** Listener fired by the JVM on the JMX notification thread when a GC completes. */
  static final class GcNotificationListener implements NotificationListener {
    private final OtelMetricStorage storage;
    private final boolean captureGcCause;

    GcNotificationListener(OtelMetricStorage storage, boolean captureGcCause) {
      this.storage = storage;
      this.captureGcCause = captureGcCause;
    }

    @Override
    public void handleNotification(Notification notification, Object handback) {
      GarbageCollectionNotificationInfo info =
          GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
      if (info == null) {
        log.debug("Skipping jvm.gc.duration record: GC notification carried no info payload");
        return;
      }
      recordGcDuration(storage, info, captureGcCause);
    }
  }

  /**
   * jvm.system.cpu.utilization (Gauge) and jvm.system.cpu.load_1m (Gauge) — both Development per
   * spec.
   */
  private static void registerSystemCpuMetrics() {
    java.lang.management.OperatingSystemMXBean rawOsBean =
        ManagementFactory.getOperatingSystemMXBean();
    if (rawOsBean instanceof OperatingSystemMXBean) {
      OperatingSystemMXBean sunOsBean = (OperatingSystemMXBean) rawOsBean;
      registerDoubleObservable(
          "jvm.system.cpu.utilization",
          "Recent CPU utilization for the whole system as reported by the JVM.",
          "1",
          GAUGE,
          storage -> {
            double load = sunOsBean.getSystemCpuLoad();
            if (load >= 0) {
              storage.recordDouble(load, Attributes.empty());
            }
          });
    } else {
      log.debug(
          "com.sun.management.OperatingSystemMXBean not available; skipping jvm.system.cpu.utilization");
    }

    registerDoubleObservable(
        "jvm.system.cpu.load_1m",
        "Average CPU load of the whole system for the last minute as reported by the JVM.",
        "{run_queue_item}",
        GAUGE,
        storage -> {
          double load = rawOsBean.getSystemLoadAverage();
          if (load >= 0) {
            storage.recordDouble(load, Attributes.empty());
          }
        });
  }

  /**
   * jvm.file_descriptor.count (UpDownCounter) and jvm.file_descriptor.limit (UpDownCounter) — both
   * Development per spec. Only registered when the underlying JVM exposes {@link
   * UnixOperatingSystemMXBean} (Unix-like platforms).
   */
  private static void registerFileDescriptorMetrics() {
    java.lang.management.OperatingSystemMXBean rawOsBean =
        ManagementFactory.getOperatingSystemMXBean();
    if (!(rawOsBean instanceof UnixOperatingSystemMXBean)) {
      log.debug(
          "com.sun.management.UnixOperatingSystemMXBean not available (non-Unix JVM); skipping jvm.file_descriptor.count and jvm.file_descriptor.limit");
      return;
    }
    UnixOperatingSystemMXBean unixOsBean = (UnixOperatingSystemMXBean) rawOsBean;

    registerLongObservable(
        "jvm.file_descriptor.count",
        "Number of open file descriptors as reported by the JVM.",
        "{file_descriptor}",
        UP_DOWN_COUNTER,
        storage -> {
          long count = unixOsBean.getOpenFileDescriptorCount();
          if (count >= 0) {
            storage.recordLong(count, Attributes.empty());
          }
        });

    registerLongObservable(
        "jvm.file_descriptor.limit",
        "Measure of max open file descriptors as reported by the JVM.",
        "{file_descriptor}",
        UP_DOWN_COUNTER,
        storage -> {
          long limit = unixOsBean.getMaxFileDescriptorCount();
          if (limit >= 0) {
            storage.recordLong(limit, Attributes.empty());
          }
        });
  }

  /**
   * Registers an UpDownCounter that iterates each platform buffer pool and records {@code getter}
   * with the {@code jvm.buffer.pool.name} attribute. Skips negative readings.
   */
  private static void bufferPoolMetric(
      String name,
      String description,
      String unit,
      List<BufferPoolMXBean> bufferPools,
      ToLongFunction<BufferPoolMXBean> getter) {
    registerLongObservable(
        name,
        description,
        unit,
        UP_DOWN_COUNTER,
        storage -> {
          for (BufferPoolMXBean pool : bufferPools) {
            long value = getter.applyAsLong(pool);
            if (value >= 0) {
              storage.recordLong(value, Attributes.of(BUFFER_POOL, pool.getName()));
            }
          }
        });
  }

  /** Registers a long observable instrument and its callback against the bootstrap registry. */
  private static void registerLongObservable(
      String name,
      String description,
      String unit,
      OtelInstrumentType type,
      Consumer<OtelMetricStorage> callback) {
    registerObservable(OtelInstrumentBuilder.ofLongs(name, type), description, unit, callback);
  }

  /** Registers a double observable instrument and its callback against the bootstrap registry. */
  private static void registerDoubleObservable(
      String name,
      String description,
      String unit,
      OtelInstrumentType type,
      Consumer<OtelMetricStorage> callback) {
    registerObservable(OtelInstrumentBuilder.ofDoubles(name, type), description, unit, callback);
  }

  /** Registers an observable instrument and its callback against the bootstrap registry. */
  private static void registerObservable(
      OtelInstrumentBuilder builder,
      String description,
      String unit,
      Consumer<OtelMetricStorage> callback) {
    builder.setDescription(description);
    builder.setUnit(unit);
    OtelMetricStorage storage = registerStorage(builder.observableDescriptor());
    OtelMetricRegistry.INSTANCE.registerObservable(
        JVM_SCOPE, new OtelRunnableObservable(() -> callback.accept(storage)));
  }

  /**
   * Registers a synchronous double histogram against the bootstrap registry and returns its storage
   * so callers can record values directly (e.g. from a JMX notification listener).
   */
  private static OtelMetricStorage registerDoubleHistogramStorage(
      String name, String description, String unit, List<Double> bucketBoundaries) {
    OtelInstrumentBuilder builder = OtelInstrumentBuilder.ofDoubles(name, HISTOGRAM);
    builder.setDescription(description);
    builder.setUnit(unit);
    return OtelMetricRegistry.INSTANCE.registerStorage(
        JVM_SCOPE,
        builder.descriptor(),
        descriptor -> OtelMetricStorage.newHistogramStorage(descriptor, bucketBoundaries));
  }

  /** Registers metric storage for the instrument against the bootstrap registry. */
  private static OtelMetricStorage registerStorage(OtelInstrumentDescriptor descriptor) {
    Function<OtelInstrumentDescriptor, OtelMetricStorage> storageFactory;
    switch (descriptor.getType()) {
      case OBSERVABLE_GAUGE:
        // observable gauges always use last-value
        storageFactory =
            descriptor.hasLongValues()
                ? OtelMetricStorage::newLongValueStorage
                : OtelMetricStorage::newDoubleValueStorage;
        break;
      case OBSERVABLE_COUNTER:
      case OBSERVABLE_UP_DOWN_COUNTER:
        // observable counters use delta value since last reset
        storageFactory =
            descriptor.hasLongValues()
                ? OtelMetricStorage::newLongDeltaStorage
                : OtelMetricStorage::newDoubleDeltaStorage;
        break;
      default:
        throw new IllegalStateException("Unexpected value: " + descriptor.getType());
    }
    return OtelMetricRegistry.INSTANCE.registerStorage(JVM_SCOPE, descriptor, storageFactory);
  }

  /** Returns Attributes carrying jvm.memory.type and jvm.memory.pool.name for the given pool. */
  private static Attributes poolAttributes(MemoryPoolMXBean pool) {
    return Attributes.of(
        MEMORY_TYPE, pool.getType().name().toLowerCase(Locale.ROOT),
        MEMORY_POOL, pool.getName());
  }

  private JvmOtlpRuntimeMetrics() {}
}
