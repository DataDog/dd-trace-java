package datadog.environment;

import static datadog.environment.CommandLineHelper.RunArguments.of;
import static datadog.environment.CommandLineHelper.forkAndRunWithArgs;
import static datadog.environment.JvmOptions.JAVA_TOOL_OPTIONS;
import static datadog.environment.JvmOptions.JDK_JAVA_OPTIONS;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.environment.CommandLineHelper.Result;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class JvmOptionsTest {
  static Stream<Arguments> options() {
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
  @MethodSource("options")
  void testParseOptions(String javaToolOptions, List<String> expectedVmOptions) {
    List<String> vmOptions = JvmOptions.parseOptions(javaToolOptions);
    assertEquals(expectedVmOptions, vmOptions);
  }

  static Stream<Arguments> data() {
    // spotless:off
    return Stream.of(
        arguments(
            "CLI options only",
            emptyMap(),
            of(singletonList("-DcliOptions"), emptyList()),
            of(singletonList("-DcliOptions"), emptyList())
        ),
        arguments(
            "JAVA_TOOL_OPTIONS only",
            env(JAVA_TOOL_OPTIONS, "-DjavaToolOptions"),
            of(emptyList(), emptyList()),
            of(singletonList("-DjavaToolOptions"), emptyList())
        ),
        arguments(
            "JDK_JAVA_OPTIONS only",
            env(JDK_JAVA_OPTIONS, "-DjdkJavaOptions"),
            of(emptyList(), emptyList()),
            of(singletonList("-DjdkJavaOptions"), emptyList())
        ),
        arguments(
            "JAVA_TOOL_OPTIONS and JDK_JAVA_OPTIONS",
            env(
                JAVA_TOOL_OPTIONS, "-DjavaToolOptions",
                JDK_JAVA_OPTIONS, "-DjdkJavaOptions"
            ),
            of(emptyList(), emptyList()),
            of(asList("-DjavaToolOptions", "-DjdkJavaOptions"), emptyList())
        ),
        arguments(
            "CLI options, JAVA_TOOL_OPTIONS, and JDK_JAVA_OPTION",
            env(
                JAVA_TOOL_OPTIONS, "-DjavaToolOptions",
                JDK_JAVA_OPTIONS, "-DjdkJavaOptions"
            ),
            of(singletonList("-DcliOptions"), emptyList()),
            of(asList("-DjavaToolOptions", "-DjdkJavaOptions", "-DcliOptions"), emptyList())
        )

    );
    // spotless:on
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("data")
  void testFindVmOptions(
      String useCase,
      Map<String, String> environmentVariables,
      CommandLineHelper.RunArguments arguments,
      CommandLineHelper.RunArguments expectedArguments)
      throws Exception {
    // Skip unsupported test cases
    skipJdkJavaOptionsOnJava8(environmentVariables);
    // Run test process
    Result result = forkAndRunWithArgs(arguments, environmentVariables);
    // Check results
    assertEquals(expectedArguments.jvmOptions, result.jvmOptions, "Failed to get JVM options");
  }

  private void skipJdkJavaOptionsOnJava8(Map<String, String> environmentVariables) {
    assumeTrue(
        JavaVirtualMachine.isJavaVersionAtLeast(9)
            || !environmentVariables.containsKey("JDK_JAVA_OPTIONS"));
  }

  private static Map<String, String> env(String... keysAndValues) {
    if (keysAndValues.length % 2 != 0) {
      throw new IllegalArgumentException("Invalid key-value pair");
    }
    Map<String, String> env = new HashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      env.put(keysAndValues[i], keysAndValues[i + 1]);
    }
    return env;
  }
}
