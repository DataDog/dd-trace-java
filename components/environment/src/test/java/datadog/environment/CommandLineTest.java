package datadog.environment;

import static datadog.environment.CommandLineTest.RunArguments.of;
import static java.io.File.separator;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

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
  private static final String JVM_OPTIONS_MARKER = "-- JVM OPTIONS --";
  private static final String CMD_ARGUMENTS_MARKER = "-- CMD ARGUMENTS --";
  private static final String REAL_CMD_ARGUMENTS_MARKER = "-- REAL CMD ARGUMENTS --";

  public static Stream<Arguments> data() {
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
            of(asList("-Dtest.property=value", argFile("space-separated")), asList("arg1", "arg2")),
            of(flatten("-Dtest.property=value", expectedArsFromArgFile("space-separated")), asList("arg1", "arg2")))
    );
    // spotless:on
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("data")
  public void testGetVmArguments(
      String useCase, RunArguments arguments, RunArguments expectedArguments) throws Exception {
    // Skip unsupported test cases
    skipArgFileTestOnJava8(arguments);
    // keepDisabledArgFileOnLinuxOnly(arguments);
    // Run test process
    Result result = forkAndRunWithArgs(CommandLineTestProcess.class, arguments);
    // Check results
    assertEquals(expectedArguments.jvmOptions, result.jvmOptions, "Failed to get JVM options");
    assertEquals(result.realCmdArgs, result.cmdArgs, "Failed to get command arguments");
    assertEquals(result.realCmdArgs, expectedArguments.cmdArgs, "Unexpected command arguments");
  }

  //  @Test
  //  // Disable the test for Java 8. Using -PtestJvm will set Java HOME to the JVM to use to run
  // this
  //  // test.
  //  @DisabledIfSystemProperty(named = "java.home", matches = ".*[-/]8\\..*")
  //  public void testGetVmArgumentsFromArgFile() throws Exception {
  //    List<String> jvmOptions = asList("-Dproperty1=value1", argFile("space-separated"));
  //    List<String> expectedJvmOptions = flatten("-Dproperty1=value1",
  // expectedArsFromArgFile("space-separated"));
  //    Result result = forkAndRunWithArgs(CommandLineTestProcess.class, of(jvmOptions,
  // emptyList()));
  //    assertEquals(expectedJvmOptions, result.jvmOptions, "Failed to get JVM options");
  //    // TODO CMD ARGS
  //  }
  //
  //  @Test
  //  // Enable only for Java 20+: https://bugs.openjdk.org/browse/JDK-8297258
  //  // Using -PtestJvm will set Java HOME to the JVM to use to run this test.
  //  @EnabledIfSystemProperty(named = "java.home", matches = ".*[-/]21\\..*")
  //  public void testGetVmArgumentsFromDisabledArgFile() throws Exception {
  //    List<String> jvmArgs = asList("-Dproperty1=value1", "--disable-@files");
  //    List<String> cmdArgs = asList("arg1", argFile("space-separated"), "arg2");
  //    // --disable-@files won't be reported
  //    List<String> expectedJvmOptions = singletonList("-Dproperty1=value1");
  //    List<String> expectedCmdArgs = flatten(CommandLineTestProcess.class.getName(), cmdArgs);
  //    Result result = forkAndRunWithArgs(CommandLineTestProcess.class, of(jvmArgs, cmdArgs));
  //    assertEquals(expectedJvmOptions, result.jvmOptions, "Failed to get JVM options");
  //    assertEquals(expectedCmdArgs, result.cmdArgs, "Failed to get command arguments");
  //  }

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
      assumeFalse(System.getProperty("java.home").matches(".*[-/]8\\..*"));
    }
  }

  private static void keepDisabledArgFileOnLinuxOnly(RunArguments arguments) {
    boolean disableArgFile = false;
    for (String jvmOptions : arguments.jvmOptions) {
      if (jvmOptions.startsWith("--disable-@files")) {
        disableArgFile = true;
        break;
      }
    }
    if (disableArgFile) {
      assumeTrue(OperatingSystem.isLinux());
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

  private Result forkAndRunWithArgs(Class<?> clazz, RunArguments arguments)
      throws IOException, InterruptedException {
    // Build the command to run a new Java process
    List<String> command = new ArrayList<>();
    command.add(System.getProperty("java.home") + separator + "bin" + separator + "java");
    command.addAll(arguments.jvmOptions);
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(clazz.getName());
    command.addAll(arguments.cmdArgs);
    // Start the process
    ProcessBuilder processBuilder = new ProcessBuilder(command);
    Process process = processBuilder.start();
    // Read and parse output and error streams
    Result result = new Result();
    List<String> current = null;
    String output = "";
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (JVM_OPTIONS_MARKER.equals(line)) {
          current = result.jvmOptions;
        } else if (CMD_ARGUMENTS_MARKER.equals(line)) {
          current = result.cmdArgs;
        } else if (REAL_CMD_ARGUMENTS_MARKER.equals(line)) {
          current = result.realCmdArgs;
        } else if (current != null) {
          current.add(line);
        }
        output += line + "\n";
      }
    }
    String error = "";
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        error += line + "\n";
      }
    }
    // Wait for the process to complete
    int exitCode = process.waitFor();
    // Dumping state on error
    if (exitCode != 0) {
      System.err.println("Error running command: " + String.join(" ", command));
      System.err.println("Exit code " + exitCode + " with output:");
      System.err.println(output);
      System.err.println("and error:");
      System.err.println(error);
    }
    assertEquals(0, exitCode, "Process should exit normally");
    return result;
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

  static class RunArguments {
    List<String> jvmOptions = new ArrayList<>();
    List<String> cmdArgs = new ArrayList<>();

    static RunArguments of(List<String> jvmArgs, List<String> cmdArgs) {
      RunArguments arguments = new RunArguments();
      arguments.jvmOptions = jvmArgs;
      arguments.cmdArgs = cmdArgs;
      return arguments;
    }
  }

  static class Result extends CommandLineTest.RunArguments {
    List<String> realCmdArgs = new ArrayList<>();
    String command; // TODO
  }

  // This class will be executed in the subprocess
  public static class CommandLineTestProcess {
    public static void main(String[] args) {
      // Print each VM argument on a new line
      System.out.println(JVM_OPTIONS_MARKER);
      for (String option : JavaVirtualMachine.getVmOptions()) {
        System.out.println(option);
      }
      // Print each command argument on a new line
      System.out.println(CMD_ARGUMENTS_MARKER);
      for (String arg : JavaVirtualMachine.getCommandArguments()) {
        System.out.println(arg);
      }
      // Print each real command argument on a new line
      System.out.println(REAL_CMD_ARGUMENTS_MARKER);
      for (String arg : args) {
        System.out.println(arg);
      }
    }
  }
}
