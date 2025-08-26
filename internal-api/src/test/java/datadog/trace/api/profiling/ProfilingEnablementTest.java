package datadog.trace.api.profiling;

import static org.junit.jupiter.api.Assertions.*;

import datadog.config.ConfigProvider;
import datadog.trace.api.config.ProfilingConfig;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ProfilingEnablementTest {

  @ParameterizedTest
  @MethodSource("provideValues")
  void of(String enabledValue, ProfilingEnablement expected) {
    ProfilingEnablement.validate(enabledValue); // make jacoco happy
    assertEquals(expected, ProfilingEnablement.of(enabledValue));
  }

  @ParameterizedTest
  @MethodSource("provideValues1")
  void from(String enabledValue, String ssi, ProfilingEnablement expected) {
    Properties props = new Properties();
    if (enabledValue != null) {
      props.setProperty(ProfilingConfig.PROFILING_ENABLED, enabledValue);
    }
    if (ssi != null) {
      props.setProperty("injection.enabled", ssi);
    }
    ConfigProvider config = ConfigProvider.withPropertiesOverride(props);
    assertEquals(expected, ProfilingEnablement.from(config));
  }

  private static Stream<Arguments> provideValues() {
    return Stream.of(
        Arguments.of("true", ProfilingEnablement.ENABLED),
        Arguments.of("TRUE", ProfilingEnablement.ENABLED),
        Arguments.of("tRuE", ProfilingEnablement.ENABLED),
        Arguments.of("1", ProfilingEnablement.ENABLED),
        Arguments.of("auto", ProfilingEnablement.AUTO),
        Arguments.of("AUTO", ProfilingEnablement.AUTO),
        Arguments.of("aUtO", ProfilingEnablement.AUTO),
        Arguments.of("false", ProfilingEnablement.DISABLED),
        Arguments.of("FALSE", ProfilingEnablement.DISABLED),
        Arguments.of("fAlSe", ProfilingEnablement.DISABLED),
        Arguments.of("0", ProfilingEnablement.DISABLED),
        Arguments.of("anything", ProfilingEnablement.DISABLED),
        Arguments.of(null, ProfilingEnablement.DISABLED));
  }

  private static Stream<Arguments> provideValues1() {
    return Stream.of(
        Arguments.of("true", null, ProfilingEnablement.ENABLED),
        Arguments.of("true", "tracer", ProfilingEnablement.ENABLED),
        Arguments.of("true", "tracer,profiler", ProfilingEnablement.ENABLED),
        Arguments.of("1", null, ProfilingEnablement.ENABLED),
        Arguments.of("1", "tracer", ProfilingEnablement.ENABLED),
        Arguments.of("1", "tracer,profiler", ProfilingEnablement.ENABLED),
        Arguments.of("auto", null, ProfilingEnablement.AUTO),
        Arguments.of("auto", "tracer", ProfilingEnablement.AUTO),
        Arguments.of("auto", "tracer,profiler", ProfilingEnablement.AUTO),
        Arguments.of("false", null, ProfilingEnablement.DISABLED),
        Arguments.of("false", "tracer", ProfilingEnablement.DISABLED),
        Arguments.of("false", "tracer,profiler", ProfilingEnablement.INJECTED),
        Arguments.of("0", null, ProfilingEnablement.DISABLED),
        Arguments.of("0", "tracer", ProfilingEnablement.DISABLED),
        Arguments.of("0", "tracer,profiler", ProfilingEnablement.INJECTED),
        Arguments.of("anything", null, ProfilingEnablement.DISABLED),
        Arguments.of("anything", "tracer", ProfilingEnablement.DISABLED),
        Arguments.of("anything", "tracer,profiler", ProfilingEnablement.INJECTED),
        Arguments.of(null, null, ProfilingEnablement.DISABLED),
        Arguments.of(null, "tracer", ProfilingEnablement.DISABLED),
        Arguments.of(null, "tracer,profiler", ProfilingEnablement.INJECTED));
  }
}
