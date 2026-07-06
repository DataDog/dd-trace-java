package datadog.trace.core.util;

import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ENABLED;
import static datadog.trace.junit.utils.config.WithConfigExtension.injectSysConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.test.util.DDJavaSpecification;
import org.junit.jupiter.api.AfterEach;
import org.tabletest.junit.TableTest;

class SystemAccessTest extends DDJavaSpecification {

  @AfterEach
  void cleanupJmx() {
    SystemAccess.disableJmx();
  }

  @TableTest({
    "scenario                               | providerEnabled | profilingEnabled | healthMetricsEnabled | hasCpuTime",
    "all disabled                           | false           | false            | false                | false     ",
    "health metrics only                    | false           | false            | true                 | false     ",
    "profiling only                         | false           | true             | false                | false     ",
    "profiling and health metrics           | false           | true             | true                 | false     ",
    "provider only                          | true            | false            | false                | false     ",
    "provider and health metrics            | true            | false            | true                 | true      ",
    "provider and profiling                 | true            | true             | false                | true      ",
    "provider, profiling and health metrics | true            | true             | true                 | true      "
  })
  void testCpuTime(
      boolean providerEnabled,
      boolean profilingEnabled,
      boolean healthMetricsEnabled,
      boolean hasCpuTime) {
    injectSysConfig(PROFILING_ENABLED, String.valueOf(profilingEnabled));
    injectSysConfig(HEALTH_METRICS_ENABLED, String.valueOf(healthMetricsEnabled));

    if (providerEnabled) {
      SystemAccess.enableJmx();
    } else {
      SystemAccess.disableJmx();
    }

    long threadCpuTime1 = SystemAccess.getCurrentThreadCpuTime();
    // burn some cpu
    int sum = 0;
    for (int i = 0; i < 10_000; i++) {
      sum += i;
    }
    long threadCpuTime2 = SystemAccess.getCurrentThreadCpuTime();

    assertTrue(sum > 0);

    if (hasCpuTime) {
      assertNotEquals(Long.MIN_VALUE, threadCpuTime1);
      assertNotEquals(Long.MIN_VALUE, threadCpuTime2);
      assertTrue(threadCpuTime2 > threadCpuTime1);
    } else {
      assertEquals(Long.MIN_VALUE, threadCpuTime1);
      assertEquals(Long.MIN_VALUE, threadCpuTime2);
    }
  }
}
