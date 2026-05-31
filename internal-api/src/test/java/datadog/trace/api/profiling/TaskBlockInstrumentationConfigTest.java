package datadog.trace.api.profiling;

import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_PRECHECK;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TaskBlockInstrumentationConfigTest {

  @ParameterizedTest
  @MethodSource("wallPrecheckModes")
  void isWallPrecheckEnabledReadsProfilerWallPrecheckFlag(boolean wallPrecheck, boolean expected) {
    ConfigProvider configProvider = configProvider(wallPrecheck);

    assertEquals(expected, TaskBlockInstrumentationConfig.isWallPrecheckEnabled(configProvider));
  }

  private static Stream<Arguments> wallPrecheckModes() {
    return Stream.of(Arguments.of(true, true), Arguments.of(false, false));
  }

  private static ConfigProvider configProvider(boolean wallPrecheck) {
    Properties properties = new Properties();
    properties.setProperty(
        PROFILING_DATADOG_PROFILER_WALL_PRECHECK, Boolean.toString(wallPrecheck));
    return ConfigProvider.withPropertiesOverride(properties);
  }
}
