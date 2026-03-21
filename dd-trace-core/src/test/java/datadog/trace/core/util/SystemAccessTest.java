package datadog.trace.core.util;

import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ENABLED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.core.test.DDCoreSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.tabletest.junit.TableTest;

public class SystemAccessTest extends DDCoreSpecification {

  @AfterEach
  public void cleanup() {
    SystemAccess.disableJmx();
  }

  @TableTest({
    "scenario                                 | providerEnabled | profilingEnabled | healthMetricsEnabled | hasCpuTime",
    "no provider no profiling no health       | false           | false            | false                | false     ",
    "no provider no profiling with health     | false           | false            | true                 | false     ",
    "no provider with profiling no health     | false           | true             | false                | false     ",
    "no provider with profiling with health   | false           | true             | true                 | false     ",
    "with provider no profiling no health     | true            | false            | false                | false     ",
    "with provider no profiling with health   | true            | false            | true                 | true      ",
    "with provider with profiling no health   | true            | true             | false                | true      ",
    "with provider with profiling with health | true            | true             | true                 | true      "
  })
  @ParameterizedTest(name = "Test cpu time [{index}] {0}")
  public void testCpuTime(
      String scenario,
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
    long sum = 0;
    for (int i = 0; i < 10_000; i++) {
      sum += i;
    }
    long threadCpuTime2 = SystemAccess.getCurrentThreadCpuTime();

    assertNotEquals(0, sum);

    if (hasCpuTime) {
      assertNotEquals(Long.MIN_VALUE, threadCpuTime1);
      assertNotEquals(Long.MIN_VALUE, threadCpuTime2);
      assertTrue(
          threadCpuTime2 > threadCpuTime1, "threadCpuTime2 should be greater than threadCpuTime1");
    } else {
      assertEquals(Long.MIN_VALUE, threadCpuTime1);
      assertEquals(Long.MIN_VALUE, threadCpuTime2);
    }
  }
}
