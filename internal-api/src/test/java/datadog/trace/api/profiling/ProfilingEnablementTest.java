package datadog.trace.api.profiling;

import static org.junit.jupiter.api.Assertions.*;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ProfilingEnablementTest {

  @ParameterizedTest
  @MethodSource("provideValues")
  void of(String value, ProfilingEnablement expected) {
    assertEquals(expected, ProfilingEnablement.of(value));
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
}
