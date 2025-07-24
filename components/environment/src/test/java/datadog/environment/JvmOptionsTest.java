package datadog.environment;

import static datadog.environment.CommandLineHelper.RunArguments.of;
import static datadog.environment.CommandLineHelper.forkAndRunWithArgs;
import static datadog.environment.JvmOptions.JAVA_TOOL_OPTIONS;
import static datadog.environment.JvmOptions.JDK_JAVA_OPTIONS;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.environment.CommandLineHelper.Result;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
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

  @MethodSource
  private static Stream<Arguments> procFsCmdLine() {
    // spotless:off
    return Stream.of(
        arguments(
            "No arguments",
            new String[0],
            emptyList()
        ),
        arguments(
            "Native image launcher",
            new String[]{"native-image-launcher", "-Xmx512m"},
            singletonList("-Xmx512m")
        ),
        arguments(
            "Java with JAR and options",
            new String[]{"java", "-Xmx512m", "-Xms256m", "-jar", "app.jar"},
            asList("-Xmx512m", "-Xms256m")
        ),
        arguments(
            "Java from class and options",
            new String[]{"java", "-Xmx512m", "-Xms256m", "-cp", "app.jar", "Main"},
            asList("-Xmx512m", "-Xms256m", "-cp", "app.jar")
        ),
        arguments(
            "Java from class and options, mixed",
            new String[]{"java", "-Xms256m", "-cp", "app.jar", "-Xmx512m", "Main"},
            asList("-Xms256m", "-cp", "app.jar", "-Xmx512m")
        ),
        arguments(
            "Args from file",
            new String[]{"java", "-Dargfile.prop=test", "-verbose:class", argFile("carriage-return-separated"), "-jar", "app.jar"},
            flatten("-Dargfile.prop=test", "-verbose:class", expectedArsFromArgFile("carriage-return-separated"))
        ),
        arguments(
            "Args from file",
            new String[]{"java", "-Dargfile.prop=test", "-verbose:class", argFile("new-line-separated"), "-jar", "app.jar"},
            flatten("-Dargfile.prop=test", "-verbose:class", expectedArsFromArgFile("new-line-separated"))
        ),
        arguments(
            "Args from file",
            new String[]{"java", "-Dargfile.prop=test", "-verbose:class", argFile("space-separated"), "-jar", "app.jar"},
            flatten("-Dargfile.prop=test", "-verbose:class", expectedArsFromArgFile("space-separated"))
        ),
        arguments(
            "Args from file",
            new String[]{"java", "-Dargfile.prop=test", "-verbose:class", argFile("tab-separated"), "-jar", "app.jar"},
            flatten("-Dargfile.prop=test", "-verbose:class", expectedArsFromArgFile("tab-separated"))
        ));
    // spotless:on
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("procFsCmdLine")
  void testFindVmOptionsWithProcFsCmdLine(
      String useCase, String[] procfsCmdline, List<String> expected) throws Exception {
    JvmOptions vmOptions = new JvmOptions();
    List<String> found = vmOptions.findVmOptions(procfsCmdline);
    assertEquals(expected, found);
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
