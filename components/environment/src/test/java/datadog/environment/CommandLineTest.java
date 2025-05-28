package datadog.environment;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CommandLineTest {

  public static Stream<Arguments> arguments() {
    return Stream.of(
        Arguments.arguments(
            asList("-Xmx128m", "-XX:+UseG1GC", "-Dtest.property=value"),
            asList("-Xmx128m", "-XX:+UseG1GC", "-Dtest.property=value")),
        // Different memory settings
        Arguments.arguments(
            asList("-Xms256m", "-Xmx512m", "-XX:+UseParallelGC", "-Dcustom.prop=test"),
            asList("-Xms256m", "-Xmx512m", "-XX:+UseParallelGC", "-Dcustom.prop=test")),
        // Empty list
        Arguments.arguments(emptyList(), emptyList()),
        // System properties
        Arguments.arguments(
            asList("-Dprop1=value1", "-Dprop2=value2", "-Dprop3=value3"),
            asList("-Dprop1=value1", "-Dprop2=value2", "-Dprop3=value3")));
  }

  @ParameterizedTest
  @MethodSource("arguments")
  public void testGetVmArguments(List<String> inputArgs, List<String> expectedArgs)
      throws Exception {
    // Build the command to run a new Java process
    List<String> command = new ArrayList<>();
    command.add("java");
    command.addAll(inputArgs);
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(CommandLineTestProcess.class.getName());

    // Start the process
    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.redirectErrorStream(true);
    Process process = processBuilder.start();

    // Read the output
    List<String> actualArgs = new ArrayList<>();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        actualArgs.add(line);
      }
    }

    // Wait for the process to complete
    int exitCode = process.waitFor();
    assertEquals(0, exitCode, "Process should exit normally");

    // Verify the arguments
    assertEquals(expectedArgs.size(), actualArgs.size(), "Number of VM arguments should match");
    for (int i = 0; i < expectedArgs.size(); i++) {
      assertEquals(expectedArgs.get(i), actualArgs.get(i), "VM argument should match");
    }
  }

  // This class will be executed in the subprocess
  public static class CommandLineTestProcess {
    public static void main(String[] args) {
      // Print each VM argument on a new line
      for (String arg : JavaVirtualMachine.getVmArguments()) {
        System.out.println(arg);
      }
    }
  }
}
