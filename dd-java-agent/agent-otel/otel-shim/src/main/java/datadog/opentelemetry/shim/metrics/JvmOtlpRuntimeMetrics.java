package datadog.opentelemetry.shim.metrics;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.ThreadMXBean;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers JVM runtime metrics using OTel semantic convention names via the dd-trace-java OTLP
 * metrics pipeline. These metrics flow via OTLP without requiring a Datadog Agent or DogStatsD.
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
      registerGcMetrics(meter);
      registerThreadMetrics(meter);
      registerClassLoadingMetrics(meter);
      registerCpuMetrics(meter);
      log.debug("Started OTLP runtime metrics with OTel-native naming (jvm.*)");
    } catch (Exception e) {
      log.error("Failed to start JVM OTLP runtime metrics", e);
    }
  }

  /**
   * jvm.memory.used - JVM memory used, split by type (heap/non_heap) and pool.
   *
   * <p>Maps to: jvm.heap_memory, jvm.non_heap_memory (via semantic-core, requires Sum By)
   */
  private static void registerMemoryMetrics(Meter meter) {
    MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();

    // jvm.memory.used - Measure of memory used
    meter
        .upDownCounterBuilder("jvm.memory.used")
        .setDescription("Measure of memory used.")
        .setUnit("By")
        .buildWithCallback(
            measurement -> {
              // Heap total
              measurement.record(
                  memoryBean.getHeapMemoryUsage().getUsed(),
                  Attributes.of(AttributeKey.stringKey("jvm.memory.type"), "heap"));
              // Non-heap total
              measurement.record(
                  memoryBean.getNonHeapMemoryUsage().getUsed(),
                  Attributes.of(AttributeKey.stringKey("jvm.memory.type"), "non_heap"));
              // Per-pool breakdown
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

    // jvm.memory.committed - Measure of memory committed
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

    // jvm.memory.limit - Measure of max obtainable memory
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

    // jvm.memory.init - Measure of initial memory requested
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
  }

  /**
   * jvm.buffer.* - JVM buffer pool metrics (direct, mapped). Maps to: jvm.buffer_pool.* (via
   * semantic-core)
   */
  private static void registerBufferMetrics(Meter meter) {
    List<BufferPoolMXBean> bufferPools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);

    // jvm.buffer.memory.used
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

    // jvm.buffer.memory.limit
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

    // jvm.buffer.count
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

  /**
   * jvm.gc.duration - Duration of JVM garbage collection actions. Maps to: jvm.gc.pause_time (via
   * semantic-core)
   */
  private static void registerGcMetrics(Meter meter) {
    List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

    // jvm.gc.duration - GC collection time (monotonic counter, in seconds)
    meter
        .counterBuilder("jvm.gc.duration")
        .ofDoubles()
        .setDescription("Duration of JVM garbage collection actions.")
        .setUnit("s")
        .buildWithCallback(
            measurement -> {
              for (GarbageCollectorMXBean gc : gcBeans) {
                long timeMs = gc.getCollectionTime();
                if (timeMs >= 0) {
                  measurement.record(
                      timeMs / 1000.0,
                      Attributes.of(
                          AttributeKey.stringKey("jvm.gc.name"),
                          gc.getName(),
                          AttributeKey.stringKey("jvm.gc.action"),
                          gc.getName()));
                }
              }
            });

    // jvm.gc.count - Number of GC collections
    meter
        .counterBuilder("jvm.gc.count")
        .setDescription("Number of executions of the garbage collector.")
        .setUnit("{collection}")
        .buildWithCallback(
            measurement -> {
              for (GarbageCollectorMXBean gc : gcBeans) {
                long count = gc.getCollectionCount();
                if (count >= 0) {
                  measurement.record(
                      count,
                      Attributes.of(AttributeKey.stringKey("jvm.gc.name"), gc.getName()));
                }
              }
            });
  }

  /** jvm.thread.count - Number of executing threads. Maps to: jvm.thread_count (via semantic-core) */
  private static void registerThreadMetrics(Meter meter) {
    ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

    meter
        .upDownCounterBuilder("jvm.thread.count")
        .setDescription("Number of executing platform threads.")
        .setUnit("{thread}")
        .buildWithCallback(
            measurement -> {
              measurement.record(threadBean.getThreadCount());
            });
  }

  /** jvm.class.* - Class loading metrics. */
  private static void registerClassLoadingMetrics(Meter meter) {
    meter
        .upDownCounterBuilder("jvm.class.loaded")
        .setDescription("Number of classes loaded since JVM start.")
        .setUnit("{class}")
        .buildWithCallback(
            measurement -> {
              measurement.record(
                  ManagementFactory.getClassLoadingMXBean().getTotalLoadedClassCount());
            });

    meter
        .upDownCounterBuilder("jvm.class.count")
        .setDescription("Number of classes currently loaded.")
        .setUnit("{class}")
        .buildWithCallback(
            measurement -> {
              measurement.record(
                  ManagementFactory.getClassLoadingMXBean().getLoadedClassCount());
            });

    meter
        .counterBuilder("jvm.class.unloaded")
        .setDescription("Number of classes unloaded since JVM start.")
        .setUnit("{class}")
        .buildWithCallback(
            measurement -> {
              measurement.record(
                  ManagementFactory.getClassLoadingMXBean().getUnloadedClassCount());
            });
  }

  /** jvm.cpu.recent_utilization - Recent CPU utilization by the JVM process. */
  private static void registerCpuMetrics(Meter meter) {
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
                // com.sun.management may not be available on all JVMs
              }
            });

    meter
        .upDownCounterBuilder("jvm.cpu.count")
        .setDescription("Number of processors available to the JVM.")
        .setUnit("{cpu}")
        .buildWithCallback(
            measurement -> {
              measurement.record(Runtime.getRuntime().availableProcessors());
            });

    // jvm.system.cpu.utilization - Recent CPU utilization for the whole system
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

  private JvmOtlpRuntimeMetrics() {}
}
