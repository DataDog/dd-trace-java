package datadog.environment;

import static java.io.File.separator;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class CommandLineHelper {
  private static final String JVM_OPTIONS_MARKER = "-- JVM OPTIONS --";
  private static final String MAIN_CLASS_MARKER = "-- MAIN CLASS --";
  private static final String CMD_ARGUMENTS_MARKER = "-- CMD ARGUMENTS --";
  private static final String REAL_CMD_ARGUMENTS_MARKER = "-- REAL CMD ARGUMENTS --";
  static final String TEST_PROCESS_CLASS_NAME = CommandLineTestProcess.class.getName();

  static Result forkAndRunWithArgs(RunArguments arguments)
      throws IOException, InterruptedException {
    return forkAndRunWithArgs(arguments, emptyMap());
  }

  static Result forkAndRunWithArgs(RunArguments arguments, Map<String, String> environmentVariables)
      throws IOException, InterruptedException {
    // Build the command to run a new Java process
    List<String> command = new ArrayList<>();
    command.add(System.getProperty("java.home") + separator + "bin" + separator + "java");
    command.addAll(arguments.jvmOptions);
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(CommandLineTestProcess.class.getName());
    command.addAll(arguments.cmdArgs);
    // Start the process
    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.environment().putAll(environmentVariables);
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
      // Drop classpath from JVM options as only supported by /proc/fs but not the fallbacks
      int indexOfCp;
      if ((indexOfCp = result.jvmOptions.indexOf("-cp")) != -1
          && indexOfCp < result.jvmOptions.size()) {
        // Remove both "-cp" and the classpath
        result.jvmOptions.remove(indexOfCp);
        result.jvmOptions.remove(indexOfCp);
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

  static class Result extends RunArguments {
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
