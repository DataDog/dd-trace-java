package datadog.environment;

import static datadog.environment.CommandLineTest.RunArguments.of;
import static java.io.File.separator;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
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
  private static final String MAIN_CLASS_MARKER = "-- MAIN CLASS --";
  private static final String CMD_ARGUMENTS_MARKER = "-- CMD ARGUMENTS --";
  private static final String REAL_CMD_ARGUMENTS_MARKER = "-- REAL CMD ARGUMENTS --";
  private static final String TEST_PROCESS_CLASS_NAME = CommandLineTestProcess.class.getName();

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
    Result result = forkAndRunWithArgs(CommandLineTestProcess.class, arguments);
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
        } else if (MAIN_CLASS_MARKER.equals(line)) {
          current = result.mainClasses;
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
    List<String> mainClasses = new ArrayList<>();

    String mainClass() {
      return String.join(",", this.mainClasses);
    }
  }

  // This class will be executed in the subprocess
  public static class CommandLineTestProcess {
    public static void main(String[] args) {
      // Print each VM argument on a new line
      System.out.println(JVM_OPTIONS_MARKER);
      for (String option : JavaVirtualMachine.getVmOptions()) {
        System.out.println(option);
      }
      // Print main class
      System.out.println(MAIN_CLASS_MARKER);
      System.out.println(JavaVirtualMachine.getMainClass());
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
