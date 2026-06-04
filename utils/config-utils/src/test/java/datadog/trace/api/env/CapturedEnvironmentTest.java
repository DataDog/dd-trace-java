package datadog.trace.api.env;

import static java.io.File.separator;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.config.GeneralConfig;
import datadog.trace.test.util.DDJavaSpecification;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class CapturedEnvironmentTest extends DDJavaSpecification {

  @Test
  void nonAutodetectedServiceNameWithNullCommand() throws IOException, InterruptedException {
    String serviceName = forkAndRunProperties("null");

    assertNull(serviceName);
  }

  @Test
  void nonAutodetectedServiceNameWithEmptyCommand() throws IOException, InterruptedException {
    String serviceName = forkAndRunProperties("");

    assertNull(serviceName);
  }

  @Test
  void nonAutodetectedServiceNameWithAllBlanksCommand() throws IOException, InterruptedException {
    String serviceName = forkAndRunProperties(" ");

    assertNull(serviceName);
  }

  @Test
  void setServiceNameBySyspropSunJavaCommandWithClass() throws IOException, InterruptedException {
    String serviceName = forkAndRunProperties("org.example.App -Dfoo=bar arg2 arg3");

    assertEquals("org.example.App", serviceName);
  }

  @Test
  void setServiceNameBySyspropSunJavaCommandWithJar() throws IOException, InterruptedException {
    String serviceName = forkAndRunProperties("foo/bar/example.jar -Dfoo=bar arg2 arg3");

    assertEquals("example", serviceName);
  }

  @Test
  void setServiceNameWithRealSunJavaCommandProperty() throws IOException, InterruptedException {
    String serviceName = forkAndRunProperties(null);

    assertEquals(ServiceNamePrinter.class.getName(), serviceName);
  }

  @Test
  void useAzureSiteNameInAzure() throws IOException, InterruptedException {
    HashMap<String, String> azureEnvVars = new HashMap<>();
    azureEnvVars.put("DD_AZURE_APP_SERVICES", "1");
    azureEnvVars.put("WEBSITE_SITE_NAME", "siteService");

    String serviceName =
        forkAndRunProperties("foo/bar/example.jar -Dfoo=bar arg2 arg3", azureEnvVars);

    assertEquals("siteService", serviceName);
  }

  @Test
  void dontUseSiteNameWhenNotInAzure() throws IOException, InterruptedException {
    String serviceName =
        forkAndRunProperties(
            "foo/bar/example.jar -Dfoo=bar arg2 arg3",
            Collections.singletonMap("WEBSITE_SITE_NAME", "siteService"));

    assertEquals("example", serviceName);
  }

  @Test
  void dontUseAzureSiteNameWhenNull() throws IOException, InterruptedException {
    String serviceName =
        forkAndRunProperties(
            "foo/bar/example.jar -Dfoo=bar arg2 arg3",
            Collections.singletonMap("DD_AZURE_APP_SERVICES", "true"));

    assertEquals("example", serviceName);
  }

  private static String forkAndRunProperties(String arg) throws IOException, InterruptedException {
    return forkAndRunProperties(arg, Collections.<String, String>emptyMap());
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
