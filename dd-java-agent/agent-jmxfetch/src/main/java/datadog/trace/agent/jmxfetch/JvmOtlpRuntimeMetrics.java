package datadog.trace.agent.jmxfetch;

import static datadog.trace.api.DDTags.RUNTIME_ID_TAG;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.COUNTER;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.GAUGE;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.HISTOGRAM;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.UP_DOWN_COUNTER;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.OperatingSystemMXBean;
import com.sun.management.UnixOperatingSystemMXBean;
import datadog.trace.api.Config;
import datadog.trace.api.ProcessTags;
import datadog.trace.bootstrap.otel.api.common.AttributeKey;
import datadog.trace.bootstrap.otel.api.common.Attributes;
import datadog.trace.bootstrap.otel.api.common.AttributesBuilder;
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
import java.lang.management.PlatformManagedObject;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.IdentityHashMap;
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
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers JVM runtime metrics with OTel-native names against the agent's bootstrap-level metric
 * registry. Each point also carries the identity, process, and source-MBean tags used by the
 * JMXFetch runtime metrics it replaces.
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
  private static final AttributeKey<String> JMX_DOMAIN = AttributeKey.stringKey("jmx_domain");

  // Keep the identity of the default config whose runtime metrics this OTLP path replaces, rather
  // than the no-JVM-defaults config that remains active alongside it.
  private static final String LEGACY_JMX_INSTANCE = "dd-java-agent default";
  private static final String LEGACY_JMX_CHECK_NAME = "jmxfetch-config";
  private static final String ENTRYPOINT_BASEDIR = "entrypoint.basedir";
  private static final String ENTRYPOINT_NAME = "entrypoint.name";
  private static final String ENTRYPOINT_TYPE = "entrypoint.type";
  private static final String ENTRYPOINT_WORKDIR = "entrypoint.workdir";
  private static final String INJECTION_MODE = "_dd.injection.mode";

  private static Attributes buildLegacyJmxAttributes() {
    AttributesBuilder builder =
        Attributes.builder()
            .put("instance", LEGACY_JMX_INSTANCE)
            .put("dd.internal.jmx_check_name", LEGACY_JMX_CHECK_NAME);
    Map<String, String> jmxTags = Config.get().getMergedJmxTags();
    putIfNotEmpty(builder, RUNTIME_ID_TAG, jmxTags.get(RUNTIME_ID_TAG));
    putIfNotEmpty(builder, INJECTION_MODE, jmxTags.get(INJECTION_MODE));
    List<String> processTags = ProcessTags.getTagsAsStringList();
    if (processTags != null) {
      for (String processTag : processTags) {
        int separator = processTag.indexOf(':');
        if (separator > 0) {
          String key = processTag.substring(0, separator);
          if (ENTRYPOINT_BASEDIR.equals(key)
              || ENTRYPOINT_NAME.equals(key)
              || ENTRYPOINT_TYPE.equals(key)
              || ENTRYPOINT_WORKDIR.equals(key)) {
            builder.put(key, processTag.substring(separator + 1));
          }
        }
      }
    }
    return builder.build();
  }

  private static Attributes[] buildThreadStateAttrs(
      Attributes legacyJmxAttributes, ThreadMXBean threadBean, boolean daemon) {
    Thread.State[] states = Thread.State.values();
    Attributes[] result = new Attributes[states.length];
    for (Thread.State state : states) {
      result[state.ordinal()] =
          withJmxTags(
              Attributes.of(
                  THREAD_DAEMON, daemon, THREAD_STATE, state.name().toLowerCase(Locale.ROOT)),
              threadBean,
              legacyJmxAttributes);
    }
    return result;
  }

  /**
   * MethodHandle for {@code ThreadInfo#isDaemon()}: non-null on Java 9+, null on Java 8. Doubles as
   * the Java-version probe since this code is compiled against Java 8 and cannot reference the
   * symbol directly.
   */
  private static final MethodHandle THREAD_INFO_IS_DAEMON = resolveThreadInfoIsDaemon();

  private static MethodHandle resolveThreadInfoIsDaemon() {
    try {
      return MethodHandles.publicLookup()
          .findVirtual(ThreadInfo.class, "isDaemon", MethodType.methodType(boolean.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      return null; // Java 8 — fall back to ThreadGroup walk
    }
  }

  private static Consumer<OtelMetricStorage> chooseThreadCountCollector(
      ThreadMXBean threadBean, Attributes[] daemonStateAttrs, Attributes[] nonDaemonStateAttrs) {
    boolean isJava9OrNewer = THREAD_INFO_IS_DAEMON != null;
    boolean isNativeImage = System.getProperty("org.graalvm.nativeimage.imagecode") != null;
    if (isJava9OrNewer && !isNativeImage) {
      return storage ->
          collectThreadCountsViaThreadMXBean(
              storage, threadBean, daemonStateAttrs, nonDaemonStateAttrs);
    }
    return storage ->
        collectThreadCountsViaThreadGroup(storage, daemonStateAttrs, nonDaemonStateAttrs);
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

      Attributes legacyJmxAttributes = buildLegacyJmxAttributes();
      MemoryAttributes memoryAttributes = new MemoryAttributes(legacyJmxAttributes);
      ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
      Consumer<OtelMetricStorage> threadCountCollector =
          chooseThreadCountCollector(
              threadBean,
              buildThreadStateAttrs(legacyJmxAttributes, threadBean, true),
              buildThreadStateAttrs(legacyJmxAttributes, threadBean, false));
      java.lang.management.OperatingSystemMXBean operatingSystemBean =
          ManagementFactory.getOperatingSystemMXBean();
      Attributes operatingSystemAttributes =
          withJmxTags(Attributes.empty(), operatingSystemBean, legacyJmxAttributes);

      // Stable metrics — always registered.
      registerMemoryMetrics(memoryAttributes);
      registerThreadMetrics(threadCountCollector);
      registerClassLoadingMetrics(legacyJmxAttributes);
      registerCpuMetrics(operatingSystemBean, operatingSystemAttributes);
      registerGcDurationMetric(legacyJmxAttributes, emitExperimentalMetrics);

      // Development-status metrics — gated by the experimental flag.
      if (emitExperimentalMetrics) {
        registerMemoryInitMetric(memoryAttributes);
        registerBufferMetrics(legacyJmxAttributes);
        registerSystemCpuMetrics(operatingSystemBean, operatingSystemAttributes);
        registerFileDescriptorMetrics(operatingSystemBean, operatingSystemAttributes);
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
  private static void registerMemoryMetrics(MemoryAttributes memory) {
    MemoryMXBean memoryBean = memory.memoryBean;
    List<MemoryPoolMXBean> pools = memory.pools;
    Attributes heapAttrs = memory.heap;
    Attributes nonHeapAttrs = memory.nonHeap;
    Map<MemoryPoolMXBean, Attributes> poolAttrs = memory.poolAttrs;

    registerLongObservable(
        "jvm.memory.used",
        "Measure of memory used.",
        "By",
        UP_DOWN_COUNTER,
        storage -> {
          storage.recordLong(memoryBean.getHeapMemoryUsage().getUsed(), heapAttrs);
          storage.recordLong(memoryBean.getNonHeapMemoryUsage().getUsed(), nonHeapAttrs);
          for (MemoryPoolMXBean pool : pools) {
            storage.recordLong(pool.getUsage().getUsed(), poolAttrs.get(pool));
          }
        });

    registerLongObservable(
        "jvm.memory.committed",
        "Measure of memory committed.",
        "By",
        UP_DOWN_COUNTER,
        storage -> {
          storage.recordLong(memoryBean.getHeapMemoryUsage().getCommitted(), heapAttrs);
          storage.recordLong(memoryBean.getNonHeapMemoryUsage().getCommitted(), nonHeapAttrs);
          for (MemoryPoolMXBean pool : pools) {
            storage.recordLong(pool.getUsage().getCommitted(), poolAttrs.get(pool));
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
            storage.recordLong(heapMax, heapAttrs);
          }
          long nonHeapMax = memoryBean.getNonHeapMemoryUsage().getMax();
          if (nonHeapMax != -1) {
            storage.recordLong(nonHeapMax, nonHeapAttrs);
          }
          for (MemoryPoolMXBean pool : pools) {
            long max = pool.getUsage().getMax();
            if (max != -1) {
              storage.recordLong(max, poolAttrs.get(pool));
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
              storage.recordLong(collectionUsage.getUsed(), poolAttrs.get(pool));
            }
          }
        });
  }

  /** jvm.memory.init (UpDownCounter, Development). */
  private static void registerMemoryInitMetric(MemoryAttributes memory) {
    MemoryMXBean memoryBean = memory.memoryBean;
    List<MemoryPoolMXBean> pools = memory.pools;
    Attributes heapAttrs = memory.heap;
    Attributes nonHeapAttrs = memory.nonHeap;
    Map<MemoryPoolMXBean, Attributes> poolAttrs = memory.poolAttrs;
    registerLongObservable(
        "jvm.memory.init",
        "Measure of initial memory requested.",
        "By",
        UP_DOWN_COUNTER,
        storage -> {
          long heapInit = memoryBean.getHeapMemoryUsage().getInit();
          if (heapInit != -1) {
            storage.recordLong(heapInit, heapAttrs);
          }
          long nonHeapInit = memoryBean.getNonHeapMemoryUsage().getInit();
          if (nonHeapInit != -1) {
            storage.recordLong(nonHeapInit, nonHeapAttrs);
          }
          for (MemoryPoolMXBean pool : pools) {
            long init = pool.getUsage().getInit();
            if (init != -1) {
              storage.recordLong(init, poolAttrs.get(pool));
            }
          }
        });
  }

  /** jvm.buffer.* (UpDownCounter, Development) — direct + mapped pool metrics. */
  private static void registerBufferMetrics(Attributes legacyJmxAttributes) {
    List<BufferPoolMXBean> bufferPools =
        ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
    Map<BufferPoolMXBean, Attributes> bufferPoolAttrs =
        buildBeanAttributes(
            bufferPools, pool -> Attributes.of(BUFFER_POOL, pool.getName()), legacyJmxAttributes);
    bufferPoolMetric(
        "jvm.buffer.memory.used",
        "Measure of memory used by buffers.",
        "By",
        bufferPools,
        bufferPoolAttrs,
        BufferPoolMXBean::getMemoryUsed);
    bufferPoolMetric(
        "jvm.buffer.memory.limit",
        "Measure of total memory capacity of buffers.",
        "By",
        bufferPools,
        bufferPoolAttrs,
        BufferPoolMXBean::getTotalCapacity);
    bufferPoolMetric(
        "jvm.buffer.count",
        "Number of buffers in the pool.",
        "{buffer}",
        bufferPools,
        bufferPoolAttrs,
        BufferPoolMXBean::getCount);
  }

  /**
   * jvm.thread.count (UpDownCounter, Stable). Bucketed by {@code jvm.thread.daemon} and {@code
   * jvm.thread.state} per the OTel JVM semantic conventions.
   */
  private static void registerThreadMetrics(Consumer<OtelMetricStorage> threadCountCollector) {
    registerLongObservable(
        "jvm.thread.count",
        "Number of executing platform threads.",
        "{thread}",
        UP_DOWN_COUNTER,
        threadCountCollector);
  }

  /**
   * Java 9+ path. Enumerates threads via {@link ThreadMXBean#getThreadInfo(long[])}; the single-arg
   * overload omits stack-trace capture, avoiding the safepoint and per-frame allocation incurred by
   * {@link Thread#getAllStackTraces()}.
   */
  private static void collectThreadCountsViaThreadMXBean(
      OtelMetricStorage storage,
      ThreadMXBean threadBean,
      Attributes[] daemonStateAttrs,
      Attributes[] nonDaemonStateAttrs) {
    Map<Thread.State, long[]> daemonCounts = new EnumMap<>(Thread.State.class);
    Map<Thread.State, long[]> nonDaemonCounts = new EnumMap<>(Thread.State.class);
    long[] ids = threadBean.getAllThreadIds();
    for (ThreadInfo info : threadBean.getThreadInfo(ids)) {
      if (info == null) {
        continue; // thread terminated between getAllThreadIds and getThreadInfo
      }
      Map<Thread.State, long[]> bucket = threadInfoIsDaemon(info) ? daemonCounts : nonDaemonCounts;
      bucket.computeIfAbsent(info.getThreadState(), k -> new long[1])[0]++;
    }
    recordThreadStateCounts(storage, daemonCounts, daemonStateAttrs);
    recordThreadStateCounts(storage, nonDaemonCounts, nonDaemonStateAttrs);
  }

  /**
   * Java 8 / GraalVM fallback. Walks the root {@link ThreadGroup} because {@code
   * ThreadInfo.isDaemon()} was added in Java 9 and {@link ThreadMXBean} is not supported on GraalVM
   * native images.
   */
  private static void collectThreadCountsViaThreadGroup(
      OtelMetricStorage storage, Attributes[] daemonStateAttrs, Attributes[] nonDaemonStateAttrs) {
    Map<Thread.State, long[]> daemonCounts = new EnumMap<>(Thread.State.class);
    Map<Thread.State, long[]> nonDaemonCounts = new EnumMap<>(Thread.State.class);
    for (Thread thread : enumerateAllThreads()) {
      Map<Thread.State, long[]> bucket = thread.isDaemon() ? daemonCounts : nonDaemonCounts;
      bucket.computeIfAbsent(thread.getState(), k -> new long[1])[0]++;
    }
    recordThreadStateCounts(storage, daemonCounts, daemonStateAttrs);
    recordThreadStateCounts(storage, nonDaemonCounts, nonDaemonStateAttrs);
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
  private static void registerClassLoadingMetrics(Attributes legacyJmxAttributes) {
    ClassLoadingMXBean classLoadingBean = ManagementFactory.getClassLoadingMXBean();
    Attributes attrs = withJmxTags(Attributes.empty(), classLoadingBean, legacyJmxAttributes);
    registerLongObservable(
        "jvm.class.loaded",
        "Number of classes loaded since JVM start.",
        "{class}",
        COUNTER,
        storage -> storage.recordLong(classLoadingBean.getTotalLoadedClassCount(), attrs));

    registerLongObservable(
        "jvm.class.count",
        "Number of classes currently loaded.",
        "{class}",
        UP_DOWN_COUNTER,
        storage -> storage.recordLong(classLoadingBean.getLoadedClassCount(), attrs));

    registerLongObservable(
        "jvm.class.unloaded",
        "Number of classes unloaded since JVM start.",
        "{class}",
        COUNTER,
        storage -> storage.recordLong(classLoadingBean.getUnloadedClassCount(), attrs));
  }

  /**
   * jvm.cpu.time (Counter), jvm.cpu.count (UpDownCounter), jvm.cpu.recent_utilization (Gauge) — all
   * Stable per spec.
   */
  private static void registerCpuMetrics(
      java.lang.management.OperatingSystemMXBean rawOsBean, Attributes osAttrs) {
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
              storage.recordDouble(nanos / 1e9, osAttrs);
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
              storage.recordDouble(cpuLoad, osAttrs);
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
        storage -> storage.recordLong(Runtime.getRuntime().availableProcessors(), osAttrs));
  }

  /**
   * jvm.gc.duration (Histogram, Stable) — synchronous; recorded from a JMX notification listener
   * attached to each {@link GarbageCollectorMXBean} when the JVM completes a GC.
   *
   * <p>The {@code jvm.gc.cause} attribute is gated on {@code captureGcCause} because cause is not
   * part of the stable attribute set in the OTel semantic conventions.
   */
  private static void registerGcDurationMetric(
      Attributes legacyJmxAttributes, boolean captureGcCause) {
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
    for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
      if (bean instanceof NotificationEmitter) {
        GcNotificationListener listener =
            new GcNotificationListener(
                storage,
                captureGcCause,
                withJmxTags(Attributes.empty(), bean, legacyJmxAttributes));
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
      OtelMetricStorage storage,
      GarbageCollectionNotificationInfo info,
      boolean captureGcCause,
      Attributes jmxAttributes) {
    double durationSeconds = info.getGcInfo().getDuration() / 1000d;
    AttributesBuilder builder = jmxAttributes.toBuilder();
    builder.put(GC_NAME, info.getGcName());
    builder.put(GC_ACTION, info.getGcAction());
    if (captureGcCause) {
      builder.put(GC_CAUSE, info.getGcCause());
    }
    storage.recordDouble(durationSeconds, builder.build());
  }

  /** Listener fired by the JVM on the JMX notification thread when a GC completes. */
  static final class GcNotificationListener implements NotificationListener {
    private final OtelMetricStorage storage;
    private final boolean captureGcCause;
    private final Attributes jmxAttributes;

    GcNotificationListener(
        OtelMetricStorage storage, boolean captureGcCause, Attributes jmxAttributes) {
      this.storage = storage;
      this.captureGcCause = captureGcCause;
      this.jmxAttributes = jmxAttributes;
    }

    @Override
    public void handleNotification(Notification notification, Object handback) {
      GarbageCollectionNotificationInfo info =
          GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
      if (info == null) {
        log.debug("Skipping jvm.gc.duration record: GC notification carried no info payload");
        return;
      }
      recordGcDuration(storage, info, captureGcCause, jmxAttributes);
    }
  }

  /**
   * jvm.system.cpu.utilization (Gauge) and jvm.system.cpu.load_1m (Gauge) — both Development per
   * spec.
   */
  private static void registerSystemCpuMetrics(
      java.lang.management.OperatingSystemMXBean rawOsBean, Attributes osAttrs) {
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
              storage.recordDouble(load, osAttrs);
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
            storage.recordDouble(load, osAttrs);
          }
        });
  }

  /**
   * jvm.file_descriptor.count (UpDownCounter) and jvm.file_descriptor.limit (UpDownCounter) — both
   * Development per spec. Only registered when the underlying JVM exposes {@link
   * UnixOperatingSystemMXBean} (Unix-like platforms).
   */
  private static void registerFileDescriptorMetrics(
      java.lang.management.OperatingSystemMXBean rawOsBean, Attributes osAttrs) {
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
            storage.recordLong(count, osAttrs);
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
            storage.recordLong(limit, osAttrs);
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
      Map<BufferPoolMXBean, Attributes> bufferPoolAttrs,
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
              storage.recordLong(value, bufferPoolAttrs.get(pool));
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

  /** Memory beans and their attributes, shared by all jvm.memory.* registrations. */
  private static final class MemoryAttributes {
    private final MemoryMXBean memoryBean;
    private final List<MemoryPoolMXBean> pools;
    private final Attributes heap;
    private final Attributes nonHeap;
    private final Map<MemoryPoolMXBean, Attributes> poolAttrs;

    private MemoryAttributes(Attributes legacyJmxAttributes) {
      memoryBean = ManagementFactory.getMemoryMXBean();
      pools = ManagementFactory.getMemoryPoolMXBeans();
      heap = withJmxTags(Attributes.of(MEMORY_TYPE, "heap"), memoryBean, legacyJmxAttributes);
      nonHeap =
          withJmxTags(Attributes.of(MEMORY_TYPE, "non_heap"), memoryBean, legacyJmxAttributes);
      poolAttrs =
          buildBeanAttributes(
              pools, JvmOtlpRuntimeMetrics::memoryPoolAttributes, legacyJmxAttributes);
    }
  }

  private static <T extends PlatformManagedObject> Map<T, Attributes> buildBeanAttributes(
      List<T> beans, Function<T, Attributes> metricAttributes, Attributes legacyJmxAttributes) {
    Map<T, Attributes> result = new IdentityHashMap<>(beans.size());
    for (T bean : beans) {
      result.put(bean, withJmxTags(metricAttributes.apply(bean), bean, legacyJmxAttributes));
    }
    return result;
  }

  private static Attributes memoryPoolAttributes(MemoryPoolMXBean pool) {
    return Attributes.of(
        MEMORY_TYPE, pool.getType().name().toLowerCase(Locale.ROOT),
        MEMORY_POOL, pool.getName());
  }

  /** Adds the requested legacy identity and ObjectName tags for the source platform MXBean. */
  private static Attributes withJmxTags(
      Attributes metricAttributes, PlatformManagedObject bean, Attributes legacyJmxAttributes) {
    ObjectName objectName = bean.getObjectName();
    AttributesBuilder builder = legacyJmxAttributes.toBuilder();
    builder.put(JMX_DOMAIN, objectName.getDomain());
    for (Map.Entry<String, String> property : objectName.getKeyPropertyList().entrySet()) {
      builder.put(AttributeKey.stringKey(property.getKey()), property.getValue());
    }
    builder.putAll(metricAttributes);
    return builder.build();
  }

  private static void putIfNotEmpty(AttributesBuilder builder, String key, String value) {
    if (value != null && !value.isEmpty()) {
      builder.put(key, value);
    }
  }

  private JvmOtlpRuntimeMetrics() {}
}
