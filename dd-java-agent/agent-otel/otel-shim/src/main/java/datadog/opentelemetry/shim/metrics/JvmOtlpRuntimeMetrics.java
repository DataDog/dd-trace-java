package datadog.opentelemetry.shim.metrics;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers JVM runtime metrics using OTel semantic convention names via the dd-trace-java OTLP
 * metrics pipeline. These metrics flow via OTLP without requiring a Datadog Agent or DogStatsD.
 *
 * <p>Only includes metrics where we can match the exact OTel spec type. Metrics requiring Histogram
 * type (jvm.gc.duration) are excluded because JMX cannot produce distribution data.
 *
 * <p>OTel JVM runtime metrics conventions:
 * https://opentelemetry.io/docs/specs/semconv/runtime/jvm-metrics/
 *
 * <p>Semantic-core equivalence mappings:
 * https://github.com/DataDog/semantic-core/blob/main/sor/domains/metrics/integrations/java/_equivalence/
 */
public final class JvmOtlpRuntimeMetrics {

  private static final Logger log = LoggerFactory.getLogger(JvmOtlpRuntimeMetrics.class);
  private static final String INSTRUMENTATION_SCOPE = "datadog.jvm.runtime";

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
      registerFileDescriptorMetrics(meter);
      log.debug("Started OTLP runtime metrics with OTel-native naming (jvm.*)");
    } catch (Exception e) {
      log.error("Failed to start JVM OTLP runtime metrics", e);
    }
  }

  // Note: jvm.gc.duration is excluded — OTel spec requires Histogram type but JMX only provides
  // cumulative milliseconds via GarbageCollectorMXBean.getCollectionTime(), not individual
  // GC event durations needed to build a distribution.

  /**
   * jvm.memory.used, jvm.memory.committed, jvm.memory.limit, jvm.memory.init,
   * jvm.memory.used_after_last_gc — all UpDownCounter per spec.
   */
  private static void registerMemoryMetrics(Meter meter) {
    MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();

    // jvm.memory.used (UpDownCounter, Stable)
    meter
        .upDownCounterBuilder("jvm.memory.used")
        .setDescription("Measure of memory used.")
        .setUnit("By")
        .buildWithCallback(
            measurement -> {
              measurement.record(
                  memoryBean.getHeapMemoryUsage().getUsed(),
                  Attributes.of(AttributeKey.stringKey("jvm.memory.type"), "heap"));
              measurement.record(
                  memoryBean.getNonHeapMemoryUsage().getUsed(),
                  Attributes.of(AttributeKey.stringKey("jvm.memory.type"), "non_heap"));
              for (MemoryPoolMXBean pool : pools) {
                measurement.record(
                    pool.getUsage().getUsed(),
                    Attributes.of(
                        AttributeKey.stringKey("jvm.memory.type"),
                        pool.getType().name().toLowerCase(),
                        AttributeKey.stringKey("jvm.memory.pool.name"),
                        pool.getName()));
              }
            });

    // jvm.memory.committed (UpDownCounter, Stable)
    meter
        .upDownCounterBuilder("jvm.memory.committed")
        .setDescription("Measure of memory committed.")
        .setUnit("By")
        .buildWithCallback(
            measurement -> {
              measurement.record(
                  memoryBean.getHeapMemoryUsage().getCommitted(),
                  Attributes.of(AttributeKey.stringKey("jvm.memory.type"), "heap"));
              measurement.record(
                  memoryBean.getNonHeapMemoryUsage().getCommitted(),
                  Attributes.of(AttributeKey.stringKey("jvm.memory.type"), "non_heap"));
              for (MemoryPoolMXBean pool : pools) {
                measurement.record(
                    pool.getUsage().getCommitted(),
                    Attributes.of(
                        AttributeKey.stringKey("jvm.memory.type"),
                        pool.getType().name().toLowerCase(),
                        AttributeKey.stringKey("jvm.memory.pool.name"),
                        pool.getName()));
              }
            });

    // jvm.memory.limit (UpDownCounter, Stable)
    meter
        .upDownCounterBuilder("jvm.memory.limit")
        .setDescription("Measure of max obtainable memory.")
        .setUnit("By")
        .buildWithCallback(
            measurement -> {
              long heapMax = memoryBean.getHeapMemoryUsage().getMax();
              if (heapMax > 0) {
                measurement.record(
                    heapMax,
                    Attributes.of(AttributeKey.stringKey("jvm.memory.type"), "heap"));
              }
              long nonHeapMax = memoryBean.getNonHeapMemoryUsage().getMax();
              if (nonHeapMax > 0) {
                measurement.record(
                    nonHeapMax,
                    Attributes.of(AttributeKey.stringKey("jvm.memory.type"), "non_heap"));
              }
              for (MemoryPoolMXBean pool : pools) {
                long max = pool.getUsage().getMax();
                if (max > 0) {
                  measurement.record(
                      max,
                      Attributes.of(
                          AttributeKey.stringKey("jvm.memory.type"),
                          pool.getType().name().toLowerCase(),
                          AttributeKey.stringKey("jvm.memory.pool.name"),
                          pool.getName()));
                }
              }
            });

    // jvm.memory.init (UpDownCounter, Development)
    meter
        .upDownCounterBuilder("jvm.memory.init")
        .setDescription("Measure of initial memory requested.")
        .setUnit("By")
        .buildWithCallback(
            measurement -> {
              long heapInit = memoryBean.getHeapMemoryUsage().getInit();
              if (heapInit > 0) {
                measurement.record(
                    heapInit,
                    Attributes.of(AttributeKey.stringKey("jvm.memory.type"), "heap"));
              }
              long nonHeapInit = memoryBean.getNonHeapMemoryUsage().getInit();
              if (nonHeapInit > 0) {
                measurement.record(
                    nonHeapInit,
                    Attributes.of(AttributeKey.stringKey("jvm.memory.type"), "non_heap"));
              }
            });

    // jvm.memory.used_after_last_gc (UpDownCounter, Stable)
    meter
        .upDownCounterBuilder("jvm.memory.used_after_last_gc")
        .setDescription("Measure of memory used after the most recent garbage collection event.")
        .setUnit("By")
        .buildWithCallback(
            measurement -> {
              for (MemoryPoolMXBean pool : pools) {
                MemoryUsage collectionUsage = pool.getCollectionUsage();
                if (collectionUsage != null) {
                  long used = collectionUsage.getUsed();
                  if (used >= 0) {
                    measurement.record(
                        used,
                        Attributes.of(
                            AttributeKey.stringKey("jvm.memory.type"),
                            pool.getType().name().toLowerCase(),
                            AttributeKey.stringKey("jvm.memory.pool.name"),
                            pool.getName()));
                  }
                }
              }
            });
  }

  /** jvm.buffer.* (UpDownCounter, Development) — JVM buffer pool metrics (direct, mapped). */
  private static void registerBufferMetrics(Meter meter) {
    List<BufferPoolMXBean> bufferPools =
        ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);

    meter
        .upDownCounterBuilder("jvm.buffer.memory.used")
        .setDescription("Measure of memory used by buffers.")
        .setUnit("By")
        .buildWithCallback(
            measurement -> {
              for (BufferPoolMXBean pool : bufferPools) {
                long used = pool.getMemoryUsed();
                if (used >= 0) {
                  measurement.record(
                      used,
                      Attributes.of(
                          AttributeKey.stringKey("jvm.buffer.pool.name"), pool.getName()));
                }
              }
            });

    meter
        .upDownCounterBuilder("jvm.buffer.memory.limit")
        .setDescription("Measure of total memory capacity of buffers.")
        .setUnit("By")
        .buildWithCallback(
            measurement -> {
              for (BufferPoolMXBean pool : bufferPools) {
                long limit = pool.getTotalCapacity();
                if (limit >= 0) {
                  measurement.record(
                      limit,
                      Attributes.of(
                          AttributeKey.stringKey("jvm.buffer.pool.name"), pool.getName()));
                }
              }
            });

    meter
        .upDownCounterBuilder("jvm.buffer.count")
        .setDescription("Number of buffers in the pool.")
        .setUnit("{buffer}")
        .buildWithCallback(
            measurement -> {
              for (BufferPoolMXBean pool : bufferPools) {
                long count = pool.getCount();
                if (count >= 0) {
                  measurement.record(
                      count,
                      Attributes.of(
                          AttributeKey.stringKey("jvm.buffer.pool.name"), pool.getName()));
                }
              }
            });
  }

  /** jvm.thread.count (UpDownCounter, Stable) */
  private static void registerThreadMetrics(Meter meter) {
    ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

    meter
        .upDownCounterBuilder("jvm.thread.count")
        .setDescription("Number of executing platform threads.")
        .setUnit("{thread}")
        .buildWithCallback(measurement -> measurement.record(threadBean.getThreadCount()));
  }

  /**
   * jvm.class.loaded (Counter, Stable) — cumulative total loaded since JVM start.
   * jvm.class.unloaded (Counter, Stable) — cumulative total unloaded since JVM start.
   * jvm.class.count (UpDownCounter, Stable) — currently loaded count.
   */
  private static void registerClassLoadingMetrics(Meter meter) {
    // jvm.class.loaded — Counter per spec (cumulative total, only goes up)
    meter
        .counterBuilder("jvm.class.loaded")
        .setDescription("Number of classes loaded since JVM start.")
        .setUnit("{class}")
        .buildWithCallback(
            measurement ->
                measurement.record(
                    ManagementFactory.getClassLoadingMXBean().getTotalLoadedClassCount()));

    // jvm.class.count — UpDownCounter per spec (current count, can decrease)
    meter
        .upDownCounterBuilder("jvm.class.count")
        .setDescription("Number of classes currently loaded.")
        .setUnit("{class}")
        .buildWithCallback(
            measurement ->
                measurement.record(
                    ManagementFactory.getClassLoadingMXBean().getLoadedClassCount()));

    // jvm.class.unloaded — Counter per spec
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
   * jvm.cpu.time (Counter, Stable), jvm.cpu.count (UpDownCounter, Stable),
   * jvm.cpu.recent_utilization (Gauge, Stable), jvm.system.cpu.utilization (Gauge, Development).
   */
  private static void registerCpuMetrics(Meter meter) {
    // jvm.cpu.time — Counter per spec (cumulative CPU time in seconds)
    meter
        .counterBuilder("jvm.cpu.time")
        .ofDoubles()
        .setDescription("CPU time used by the process as reported by the JVM.")
        .setUnit("s")
        .buildWithCallback(
            measurement -> {
              try {
                java.lang.management.OperatingSystemMXBean osBean =
                    ManagementFactory.getOperatingSystemMXBean();
                if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                  long nanos =
                      ((com.sun.management.OperatingSystemMXBean) osBean).getProcessCpuTime();
                  if (nanos >= 0) {
                    measurement.record(nanos / 1e9);
                  }
                }
              } catch (Exception e) {
                // com.sun.management may not be available
              }
            });

    // jvm.cpu.count — UpDownCounter per spec
    meter
        .upDownCounterBuilder("jvm.cpu.count")
        .setDescription("Number of processors available to the JVM.")
        .setUnit("{cpu}")
        .buildWithCallback(
            measurement -> measurement.record(Runtime.getRuntime().availableProcessors()));

    // jvm.cpu.recent_utilization — Gauge per spec
    meter
        .gaugeBuilder("jvm.cpu.recent_utilization")
        .setDescription("Recent CPU utilization for the process as reported by the JVM.")
        .setUnit("1")
        .buildWithCallback(
            measurement -> {
              try {
                java.lang.management.OperatingSystemMXBean osBean =
                    ManagementFactory.getOperatingSystemMXBean();
                if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                  double cpuLoad =
                      ((com.sun.management.OperatingSystemMXBean) osBean).getProcessCpuLoad();
                  if (cpuLoad >= 0) {
                    measurement.record(cpuLoad);
                  }
                }
              } catch (Exception e) {
                // com.sun.management may not be available
              }
            });

    // jvm.system.cpu.utilization — Gauge, Development
    meter
        .gaugeBuilder("jvm.system.cpu.utilization")
        .setDescription("Recent CPU utilization for the whole system as reported by the JVM.")
        .setUnit("1")
        .buildWithCallback(
            measurement -> {
              try {
                java.lang.management.OperatingSystemMXBean osBean =
                    ManagementFactory.getOperatingSystemMXBean();
                if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                  double load =
                      ((com.sun.management.OperatingSystemMXBean) osBean).getSystemCpuLoad();
                  if (load >= 0) {
                    measurement.record(load);
                  }
                }
              } catch (Exception e) {
                // com.sun.management may not be available
              }
            });
  }

  /** jvm.file_descriptor.count and jvm.file_descriptor.limit (UpDownCounter, Development). */
  private static void registerFileDescriptorMetrics(Meter meter) {
    meter
        .upDownCounterBuilder("jvm.file_descriptor.count")
        .setDescription("Number of open file descriptors.")
        .setUnit("{file_descriptor}")
        .buildWithCallback(
            measurement -> {
              try {
                java.lang.management.OperatingSystemMXBean osBean =
                    ManagementFactory.getOperatingSystemMXBean();
                if (osBean instanceof com.sun.management.UnixOperatingSystemMXBean) {
                  long count =
                      ((com.sun.management.UnixOperatingSystemMXBean) osBean)
                          .getOpenFileDescriptorCount();
                  if (count >= 0) {
                    measurement.record(count);
                  }
                }
              } catch (Exception e) {
                // UnixOperatingSystemMXBean not available on Windows
              }
            });

    meter
        .upDownCounterBuilder("jvm.file_descriptor.limit")
        .setDescription("Maximum number of open file descriptors allowed.")
        .setUnit("{file_descriptor}")
        .buildWithCallback(
            measurement -> {
              try {
                java.lang.management.OperatingSystemMXBean osBean =
                    ManagementFactory.getOperatingSystemMXBean();
                if (osBean instanceof com.sun.management.UnixOperatingSystemMXBean) {
                  long limit =
                      ((com.sun.management.UnixOperatingSystemMXBean) osBean)
                          .getMaxFileDescriptorCount();
                  if (limit >= 0) {
                    measurement.record(limit);
                  }
                }
              } catch (Exception e) {
                // UnixOperatingSystemMXBean not available on Windows
              }
            });
  }

  private JvmOtlpRuntimeMetrics() {}
}
