package datadog.trace.agent.jmxfetch;

import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.COUNTER;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.GAUGE;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.UP_DOWN_COUNTER;

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
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToLongFunction;
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
  private static final Attributes HEAP_ATTRS = Attributes.of(MEMORY_TYPE, "heap");
  private static final Attributes NON_HEAP_ATTRS = Attributes.of(MEMORY_TYPE, "non_heap");

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

  /** jvm.thread.count (UpDownCounter, Stable). */
  private static void registerThreadMetrics() {
    ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    registerLongObservable(
        "jvm.thread.count",
        "Number of executing platform threads.",
        "{thread}",
        UP_DOWN_COUNTER,
        storage -> storage.recordLong(threadBean.getThreadCount(), Attributes.empty()));
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
    OperatingSystemMXBean osBean = sunOsBean();

    if (osBean != null) {
      registerDoubleObservable(
          "jvm.cpu.time",
          "CPU time used by the process as reported by the JVM.",
          "s",
          COUNTER,
          storage -> {
            long nanos = osBean.getProcessCpuTime();
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
            double cpuLoad = osBean.getProcessCpuLoad();
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
   * jvm.system.cpu.utilization (Gauge) and jvm.system.cpu.load_1m (Gauge) — both Development per
   * spec.
   */
  private static void registerSystemCpuMetrics() {
    OperatingSystemMXBean osBean = sunOsBean();
    if (osBean != null) {
      registerDoubleObservable(
          "jvm.system.cpu.utilization",
          "Recent CPU utilization for the whole system as reported by the JVM.",
          "1",
          GAUGE,
          storage -> {
            double load = osBean.getSystemCpuLoad();
            if (load >= 0) {
              storage.recordDouble(load, Attributes.empty());
            }
          });
    } else {
      log.debug(
          "com.sun.management.OperatingSystemMXBean not available; skipping jvm.system.cpu.utilization");
    }

    java.lang.management.OperatingSystemMXBean stdOsBean =
        ManagementFactory.getOperatingSystemMXBean();
    registerDoubleObservable(
        "jvm.system.cpu.load_1m",
        "Average CPU load of the whole system for the last minute as reported by the JVM.",
        "{run_queue_item}",
        GAUGE,
        storage -> {
          double load = stdOsBean.getSystemLoadAverage();
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

  /** Returns the {@code com.sun.management} OS bean if available on this JVM, else {@code null}. */
  private static OperatingSystemMXBean sunOsBean() {
    java.lang.management.OperatingSystemMXBean rawOsBean =
        ManagementFactory.getOperatingSystemMXBean();
    return rawOsBean instanceof OperatingSystemMXBean ? (OperatingSystemMXBean) rawOsBean : null;
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
