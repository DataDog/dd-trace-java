package datadog.trace.api;

import static datadog.trace.api.config.AppSecConfig.APPSEC_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ENABLED;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Tests the resolution of {@link Config#isOtelContextExposureEnabled()}. */
@ExtendWith(WithConfigExtension.class)
class ConfigOtelContextExposureTest {

  /**
   * The Datadog profiler raw predicate is vetoed outright on platforms and JVM versions that cannot
   * run it, regardless of any explicit opt-in. Tests that expect the feature to be enabled are only
   * meaningful on a JVM where that veto does not apply.
   */
  private static void assumeDatadogProfilerNotVetoed() {
    assumeTrue(
        !Config.isDatadogProfilerEnablementOverridden(),
        "Datadog profiler is unavailable on this platform/JVM version");
  }

  @Test
  void disabledByDefault() {
    assertFalse(Config.get().isOtelContextExposureEnabled());
  }

  @Test
  @WithConfig(key = PROFILING_ENABLED, value = "true")
  @WithConfig(key = APPSEC_ENABLED, value = "false")
  @WithConfig(key = PROFILING_DATADOG_PROFILER_ENABLED, value = "true")
  void enabledWhenProfilingIsEnabled() {
    assumeDatadogProfilerNotVetoed();

    assertTrue(Config.get().isOtelContextExposureEnabled());
  }

  @Test
  @WithConfig(key = APPSEC_ENABLED, value = "true")
  @WithConfig(key = PROFILING_ENABLED, value = "false")
  @WithConfig(key = PROFILING_DATADOG_PROFILER_ENABLED, value = "true")
  void enabledWhenAppSecIsFullyEnabledWithoutProfiling() {
    assumeDatadogProfilerNotVetoed();

    Config config = Config.get();
    assertFalse(config.isProfilingEnabled());
    assertTrue(config.isOtelContextExposureEnabled());
  }

  @Test
  @WithConfig(key = APPSEC_ENABLED, value = "inactive")
  @WithConfig(key = PROFILING_ENABLED, value = "false")
  @WithConfig(key = PROFILING_DATADOG_PROFILER_ENABLED, value = "true")
  void disabledWhenAppSecIsOnlyEnabledInactive() {
    assertFalse(Config.get().isOtelContextExposureEnabled());
  }

  @Test
  @WithConfig(key = APPSEC_ENABLED, value = "true")
  @WithConfig(key = PROFILING_ENABLED, value = "false")
  @WithConfig(key = PROFILING_DATADOG_PROFILER_ENABLED, value = "false")
  void disabledWhenDatadogProfilerIsExplicitlyDisabled() {
    assertFalse(Config.get().isOtelContextExposureEnabled());
  }

  /**
   * An environment where the Datadog profiler cannot run (unsupported JVM version, Windows, GraalVM
   * native image) makes the raw ddprof predicate {@code false}. That environment detection reads
   * real, cached JVM and OS state that a unit test running on a normal JVM cannot fake, and there
   * is no existing test helper in this module to stub it. This test therefore drives the identical
   * boolean short-circuit through the {@code DD_PROFILING_DDPROF_ENABLED=false} env variable: from
   * {@link Config}'s point of view an environment-detected "unsafe" and an explicit "false"
   * collapse into the same raw-predicate value, so the downstream effect on {@link
   * Config#isOtelContextExposureEnabled()} is the same.
   */
  @Test
  @WithConfig(key = "APPSEC_ENABLED", value = "true", env = true)
  @WithConfig(key = "PROFILING_DDPROF_ENABLED", value = "false", env = true)
  void disabledInAnEnvironmentWhereTheDatadogProfilerIsUnsafe() {
    assertFalse(Config.get().isOtelContextExposureEnabled());
  }
}
