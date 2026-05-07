package datadog.opentelemetry.shim.metrics;

import com.sun.management.OperatingSystemMXBean;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.function.ToLongFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers JVM runtime metrics with OTel-native names against the agent's MeterProvider. See
 * https://opentelemetry.io/docs/specs/semconv/runtime/jvm-metrics/.
 */
public final class JvmOtlpRuntimeMetrics {

  private static final Logger log = LoggerFactory.getLogger(JvmOtlpRuntimeMetrics.class);
  private static final String INSTRUMENTATION_SCOPE = "datadog.jvm.runtime";
  private static final AttributeKey<String> MEMORY_TYPE = AttributeKey.stringKey("jvm.memory.type");
  private static final AttributeKey<String> MEMORY_POOL =
      AttributeKey.stringKey("jvm.memory.pool.name");
  private static final AttributeKey<String> BUFFER_POOL =
      AttributeKey.stringKey("jvm.buffer.pool.name");

  private static volatile boolean started = false;

  /** Registers all JVM runtime metric instruments on the OTel MeterProvider. */
  public static void start() {
    if (started) {
      return;
    }
    started = true;

    try {
      Meter meter = OtelMeterProvider.INSTANCE.get(INSTRUMENTATION_SCOPE);
      registerMemoryMetrics(meter);
      registerBufferMetrics(meter);
      registerThreadMetrics(meter);
      registerClassLoadingMetrics(meter);
      registerCpuMetrics(meter);
      log.debug("Started OTLP runtime metrics with OTel-native naming (jvm.*)");
    } catch (Exception e) {
      log.error("Failed to start JVM OTLP runtime metrics", e);
    }
  }

  // jvm.gc.duration is excluded — spec requires Histogram, JMX only exposes cumulative time.

  /**
   * jvm.memory.used, jvm.memory.committed, jvm.memory.limit, jvm.memory.init,
   * jvm.memory.used_after_last_gc — all UpDownCounter per spec.
   */
  private static void registerMemoryMetrics(Meter meter) {
    MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();

    meter
        .upDownCounterBuilder("jvm.memory.used")
        .setDescription("Measure of memory used.")
        .setUnit("By")
        .buildWithCallback(
            measurement -> {
              measurement.record(
                  memoryBean.getHeapMemoryUsage().getUsed(), Attributes.of(MEMORY_TYPE, "heap"));
              measurement.record(
                  memoryBean.getNonHeapMemoryUsage().getUsed(),
                  Attributes.of(MEMORY_TYPE, "non_heap"));
              for (MemoryPoolMXBean pool : pools) {
                measurement.record(pool.getUsage().getUsed(), poolAttributes(pool));
              }
            });

    meter
        .upDownCounterBuilder("jvm.memory.committed")
        .setDescription("Measure of memory committed.")
        .setUnit("By")
        .buildWithCallback(
            measurement -> {
              measurement.record(
                  memoryBean.getHeapMemoryUsage().getCommitted(),
                  Attributes.of(MEMORY_TYPE, "heap"));
              measurement.record(
                  memoryBean.getNonHeapMemoryUsage().getCommitted(),
                  Attributes.of(MEMORY_TYPE, "non_heap"));
              for (MemoryPoolMXBean pool : pools) {
                measurement.record(pool.getUsage().getCommitted(), poolAttributes(pool));
              }
            });

    meter
        .upDownCounterBuilder("jvm.memory.limit")
        .setDescription("Measure of max obtainable memory.")
        .setUnit("By")
        .buildWithCallback(
            measurement -> {
              long heapMax = memoryBean.getHeapMemoryUsage().getMax();
              if (heapMax > 0) {
                measurement.record(heapMax, Attributes.of(MEMORY_TYPE, "heap"));
              }
              long nonHeapMax = memoryBean.getNonHeapMemoryUsage().getMax();
              if (nonHeapMax > 0) {
                measurement.record(nonHeapMax, Attributes.of(MEMORY_TYPE, "non_heap"));
              }
              for (MemoryPoolMXBean pool : pools) {
                long max = pool.getUsage().getMax();
                if (max > 0) {
                  measurement.record(max, poolAttributes(pool));
                }
              }
            });

    meter
        .upDownCounterBuilder("jvm.memory.init")
        .setDescription("Measure of initial memory requested.")
        .setUnit("By")
        .buildWithCallback(
            measurement -> {
              long heapInit = memoryBean.getHeapMemoryUsage().getInit();
              if (heapInit > 0) {
                measurement.record(heapInit, Attributes.of(MEMORY_TYPE, "heap"));
              }
              long nonHeapInit = memoryBean.getNonHeapMemoryUsage().getInit();
              if (nonHeapInit > 0) {
                measurement.record(nonHeapInit, Attributes.of(MEMORY_TYPE, "non_heap"));
              }
            });

    meter
        .upDownCounterBuilder("jvm.memory.used_after_last_gc")
        .setDescription("Measure of memory used after the most recent garbage collection event.")
        .setUnit("By")
        .buildWithCallback(
            measurement -> {
              for (MemoryPoolMXBean pool : pools) {
                MemoryUsage collectionUsage = pool.getCollectionUsage();
                if (collectionUsage != null && collectionUsage.getUsed() >= 0) {
                  measurement.record(collectionUsage.getUsed(), poolAttributes(pool));
                }
              }
            });
  }

  /** jvm.buffer.* (UpDownCounter, Development) — direct + mapped pool metrics. */
  private static void registerBufferMetrics(Meter meter) {
    List<BufferPoolMXBean> bufferPools =
        ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
    bufferPoolMetric(
        meter,
        "jvm.buffer.memory.used",
        "Measure of memory used by buffers.",
        "By",
        bufferPools,
        BufferPoolMXBean::getMemoryUsed);
    bufferPoolMetric(
        meter,
        "jvm.buffer.memory.limit",
        "Measure of total memory capacity of buffers.",
        "By",
        bufferPools,
        BufferPoolMXBean::getTotalCapacity);
    bufferPoolMetric(
        meter,
        "jvm.buffer.count",
        "Number of buffers in the pool.",
        "{buffer}",
        bufferPools,
        BufferPoolMXBean::getCount);
  }

  /** jvm.thread.count (UpDownCounter, Stable). */
  private static void registerThreadMetrics(Meter meter) {
    ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    meter
        .upDownCounterBuilder("jvm.thread.count")
        .setDescription("Number of executing platform threads.")
        .setUnit("{thread}")
        .buildWithCallback(measurement -> measurement.record(threadBean.getThreadCount()));
  }

  /**
   * jvm.class.loaded (Counter), jvm.class.unloaded (Counter), jvm.class.count (UpDownCounter) — all
   * Stable per spec.
   */
  private static void registerClassLoadingMetrics(Meter meter) {
    meter
        .counterBuilder("jvm.class.loaded")
        .setDescription("Number of classes loaded since JVM start.")
        .setUnit("{class}")
        .buildWithCallback(
            measurement ->
                measurement.record(
                    ManagementFactory.getClassLoadingMXBean().getTotalLoadedClassCount()));

    meter
        .upDownCounterBuilder("jvm.class.count")
        .setDescription("Number of classes currently loaded.")
        .setUnit("{class}")
        .buildWithCallback(
            measurement ->
                measurement.record(
                    ManagementFactory.getClassLoadingMXBean().getLoadedClassCount()));

    meter
        .counterBuilder("jvm.class.unloaded")
        .setDescription("Number of classes unloaded since JVM start.")
        .setUnit("{class}")
        .buildWithCallback(
            measurement ->
                measurement.record(
                    ManagementFactory.getClassLoadingMXBean().getUnloadedClassCount()));
  }

  /**
   * jvm.cpu.time (Counter), jvm.cpu.count (UpDownCounter), jvm.cpu.recent_utilization (Gauge) — all
   * Stable per spec.
   */
  private static void registerCpuMetrics(Meter meter) {
    meter
        .counterBuilder("jvm.cpu.time")
        .ofDoubles()
        .setDescription("CPU time used by the process as reported by the JVM.")
        .setUnit("s")
        .buildWithCallback(
            measurement -> {
              OperatingSystemMXBean osBean = sunOsBean();
              if (osBean == null) {
                return;
              }
              long nanos = osBean.getProcessCpuTime();
              if (nanos >= 0) {
                measurement.record(nanos / 1e9);
              }
            });

    meter
        .upDownCounterBuilder("jvm.cpu.count")
        .setDescription("Number of processors available to the JVM.")
        .setUnit("{cpu}")
        .buildWithCallback(
            measurement -> measurement.record(Runtime.getRuntime().availableProcessors()));

    meter
        .gaugeBuilder("jvm.cpu.recent_utilization")
        .setDescription("Recent CPU utilization for the process as reported by the JVM.")
        .setUnit("1")
        .buildWithCallback(
            measurement -> {
              OperatingSystemMXBean osBean = sunOsBean();
              if (osBean == null) {
                return;
              }
              double cpuLoad = osBean.getProcessCpuLoad();
              if (cpuLoad >= 0) {
                measurement.record(cpuLoad);
              }
            });
  }

  /**
   * Builds an UpDownCounter that iterates each platform buffer pool and records {@code getter} with
   * the {@code jvm.buffer.pool.name} attribute. Skips negative readings.
   */
  private static void bufferPoolMetric(
      Meter meter,
      String name,
      String description,
      String unit,
      List<BufferPoolMXBean> bufferPools,
      ToLongFunction<BufferPoolMXBean> getter) {
    meter
        .upDownCounterBuilder(name)
        .setDescription(description)
        .setUnit(unit)
        .buildWithCallback(
            measurement -> {
              for (BufferPoolMXBean pool : bufferPools) {
                long value = getter.applyAsLong(pool);
                if (value >= 0) {
                  measurement.record(value, Attributes.of(BUFFER_POOL, pool.getName()));
                }
              }
            });
  }

  /** Returns Attributes carrying jvm.memory.type and jvm.memory.pool.name for the given pool. */
  private static Attributes poolAttributes(MemoryPoolMXBean pool) {
    return Attributes.of(
        MEMORY_TYPE, pool.getType().name().toLowerCase(),
        MEMORY_POOL, pool.getName());
  }

  /** Returns the com.sun.management OperatingSystemMXBean if available, otherwise null. */
  private static OperatingSystemMXBean sunOsBean() {
    java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
    return bean instanceof OperatingSystemMXBean ? (OperatingSystemMXBean) bean : null;
  }

  private JvmOtlpRuntimeMetrics() {}
}
