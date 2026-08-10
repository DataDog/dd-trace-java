package datadog.trace.api.env;

import static java.io.File.separator;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.config.GeneralConfig;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.tabletest.junit.TableTest;

public class CapturedEnvironmentTest {

  @TableTest({
    "scenario           | sunJavaCommand                          | envVars                                                    | expectedServiceName                                             ",
    "null command       | null                                    | [:]                                                        |                                                                 ",
    "empty command      | ''                                      | [:]                                                        |                                                                 ",
    "all blanks         | ' '                                     | [:]                                                        |                                                                 ",
    "class in command   | org.example.App -Dfoo=bar arg2 arg3     | [:]                                                        | org.example.App                                                 ",
    "jar in command     | foo/bar/example.jar -Dfoo=bar arg2 arg3 | [:]                                                        | example                                                         ",
    "real sun command   |                                         | [:]                                                        | datadog.trace.api.env.CapturedEnvironmentTest$ServiceNamePrinter",
    "azure site name    | foo/bar/example.jar -Dfoo=bar arg2 arg3 | [DD_AZURE_APP_SERVICES: 1, WEBSITE_SITE_NAME: siteService] | siteService                                                     ",
    "site name no azure | foo/bar/example.jar -Dfoo=bar arg2 arg3 | [WEBSITE_SITE_NAME: siteService]                           | example                                                         ",
    "azure flag no site | foo/bar/example.jar -Dfoo=bar arg2 arg3 | [DD_AZURE_APP_SERVICES: true]                              | example                                                         "
  })
  void capturesServiceName(
      String sunJavaCommand, Map<String, String> envVars, String expectedServiceName)
      throws IOException, InterruptedException {
    assertEquals(expectedServiceName, forkAndRunProperties(sunJavaCommand, envVars));
  }

  private static String forkAndRunProperties(String arg, Map<String, String> envVars)
      throws IOException, InterruptedException {
    // Build the command to run a new Java process
    List<String> command = new ArrayList<>();
    command.add(System.getProperty("java.home") + separator + "bin" + separator + "java");
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(ServiceNamePrinter.class.getName());
    if (arg != null) {
      command.add(arg);
    }
    // Start the process
    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.environment().putAll(envVars);
    Process process = processBuilder.start();
    // Read and parse output and error streams
    String serviceName = "";
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (!serviceName.isEmpty()) {
          serviceName += "\n";
        }
        serviceName += line;
      }
    }
    if ("null".equals(serviceName)) {
      serviceName = null;
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
      System.out.println(
          "Error printing service name. Exit code "
              + exitCode
              + " with service name: '"
              + serviceName
              + "' and error:\n"
              + error);
      throw new IllegalStateException("Process should exit without error");
    }
    return serviceName;
  }

  public static class ServiceNamePrinter {
    public static void main(String[] args) {
      if (args.length > 0) {
        String sunJavaCommand = args[0];
        if ("null".equals(sunJavaCommand)) {
          System.clearProperty("sun.java.command");
        } else {
          System.setProperty("sun.java.command", sunJavaCommand);
        }
      }
      CapturedEnvironment capturedEnv = CapturedEnvironment.get();
      Map<String, String> props = capturedEnv.getProperties();
      System.out.println(props.get(GeneralConfig.SERVICE_NAME));
    }
  }
}
