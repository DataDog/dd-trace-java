package datadog.environment;

import static datadog.environment.CommandLineHelper.RunArguments.of;
import static datadog.environment.CommandLineHelper.TEST_PROCESS_CLASS_NAME;
import static datadog.environment.CommandLineHelper.forkAndRunWithArgs;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.environment.CommandLineHelper.Result;
import datadog.environment.CommandLineHelper.RunArguments;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CommandLineTest {

  static Stream<Arguments> data() {
    // spotless:off
    return Stream.of(
        arguments(
            "No JVM options nor command argument",
            of(emptyList(), emptyList()),
            of(emptyList(), emptyList())
        ),
        arguments(
            "JVM options only",
            of(asList("-Xmx128m", "-XX:+UseG1GC", "-Dtest.property=value"), emptyList()),
            of(asList("-Xmx128m", "-XX:+UseG1GC", "-Dtest.property=value"), emptyList())),
        arguments(
            "Command arguments only",
            of(emptyList(), asList("arg1", "arg2")),
            of(emptyList(), asList("arg1", "arg2"))),
        arguments(
            "Both JVM options and command arguments",
            of(asList("-Xmx128m", "-XX:+UseG1GC", "-Dtest.property=value"), asList("arg1", "arg2")),
            of(asList("-Xmx128m", "-XX:+UseG1GC", "-Dtest.property=value"), asList("arg1", "arg2"))),
        arguments(
            "JVM options from argfile",
            of(asList("-Dtest.property=value", argFile("carriage-return-separated")), asList("arg1", "arg2")),
            of(flatten("-Dtest.property=value", expectedArsFromArgFile("carriage-return-separated")), asList("arg1", "arg2"))),
        arguments(
            "JVM options from argfile",
            of(asList("-Dtest.property=value", argFile("new-line-separated")), asList("arg1", "arg2")),
            of(flatten("-Dtest.property=value", expectedArsFromArgFile("new-line-separated")), asList("arg1", "arg2"))),
        arguments(
            "JVM options from argfile",
            of(asList("-Dtest.property=value", argFile("space-separated")), asList("arg1", "arg2")),
            of(flatten("-Dtest.property=value", expectedArsFromArgFile("space-separated")), asList("arg1", "arg2"))),
        arguments(
            "JVM options from argfile",
            of(asList("-Dtest.property=value", argFile("tab-separated")), asList("arg1", "arg2")),
            of(flatten("-Dtest.property=value", expectedArsFromArgFile("tab-separated")), asList("arg1", "arg2")))
    );
    // spotless:on
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("data")
  void testGetVmArguments(String useCase, RunArguments arguments, RunArguments expectedArguments)
      throws Exception {
    // Skip unsupported test cases
    skipArgFileTestOnJava8(arguments);
    // Run test process
    Result result = forkAndRunWithArgs(arguments);
    // Check results
    assertEquals(expectedArguments.jvmOptions, result.jvmOptions, "Failed to get JVM options");
    assertEquals(TEST_PROCESS_CLASS_NAME, result.mainClass(), "Failed to get main class");
    assertEquals(result.realCmdArgs, result.cmdArgs, "Failed to get command arguments");
    assertEquals(result.realCmdArgs, expectedArguments.cmdArgs, "Unexpected command arguments");
  }

  private static void skipArgFileTestOnJava8(RunArguments arguments) {
    boolean useArgFile = false;
    for (String jvmOption : arguments.jvmOptions) {
      if (jvmOption.startsWith("@")) {
        useArgFile = true;
        break;
      }
    }
    if (!useArgFile) {
      for (String cmdArg : arguments.cmdArgs) {
        if (cmdArg.startsWith("@")) {
          useArgFile = true;
          break;
        }
      }
    }
    if (useArgFile) {
      assumeFalse(System.getProperty("java.home").matches(".*[-/]8[./].*"));
    }
  }

  private static String argFile(String name) {
    return "@src/test/resources/argfiles/" + name + ".txt";
  }

  private static List<String> expectedArsFromArgFile(String name) {
    List<String> arguments = new ArrayList<>();
    try (InputStream stream =
            requireNonNull(
                CommandLineTest.class.getResourceAsStream("/argfiles/" + name + "-expected.txt"));
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
      String line;
      while ((line = reader.readLine()) != null) {
        arguments.add(line);
      }
    } catch (IOException e) {
      Assertions.fail("Failed to read expected args from " + name + "argfile", e);
    }
    return arguments;
  }

  private static List<String> flatten(Object... values) {
    List<String> result = new ArrayList<>();
    for (Object value : values) {
      if (value instanceof Collection) {
        result.addAll((Collection<? extends String>) value);
      } else {
        result.add(value.toString());
      }
    }
    return result;
  }
}
