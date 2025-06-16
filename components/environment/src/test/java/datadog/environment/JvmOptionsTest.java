package datadog.environment;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class JvmOptionsTest {
  static Stream<Arguments> data() {
    return Stream.of(
        arguments("", emptyList()),
        arguments("-Xmx512m", singletonList("-Xmx512m")),
        arguments("-Xms256m -Xmx512m", asList("-Xms256m", "-Xmx512m")),
        arguments("   -Xms256m     -Xmx512m  ", asList("-Xms256m", "-Xmx512m")),
        arguments("-Xms256m\t-Xmx512m", asList("-Xms256m", "-Xmx512m")),
        arguments("\t -Xms256m \t -Xmx512m \t", asList("-Xms256m", "-Xmx512m")),
        arguments(
            "-Xmx512m -Dprop=\"value with space\"", asList("-Xmx512m", "-Dprop=value with space")),
        arguments(
            "-Xmx512m -Dprop='value with space'", asList("-Xmx512m", "-Dprop=value with space")),
        arguments("-Xmx512m -Dprop='mixing\"quotes'", asList("-Xmx512m", "-Dprop=mixing\"quotes")),
        arguments("-Xmx512m -Dprop=\"mixing'quotes\"", asList("-Xmx512m", "-Dprop=mixing'quotes")));
  }

  @ParameterizedTest
  @MethodSource("data")
  void testParseToolOptions(String javaToolOptions, List<String> expectedVmOptions) {
    List<String> vmOptions = JvmOptions.parseToolOptions(javaToolOptions);
    assertEquals(expectedVmOptions, vmOptions);
  }
}
