package datadog.trace.bootstrap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;

/** Entry point when running the agent as a sample application with -jar. */
public final class AgentJar {
  private static final Class<?> thisClass = AgentJar.class;

  public static void main(final String[] args) {
    if (args.length == 0) {
      printAgentVersion();
    } else {
      try {
        switch (args[0]) {
          case "--list-integrations":
          case "-li":
            printIntegrationNames();
            break;
          case "--help":
          case "-h":
            printUsage();
            break;
          case "--version":
          case "-v":
            printAgentVersion();
            break;
          default:
            throw new IllegalArgumentException("unknown option: " + args[0]);
        }
      } catch (IllegalArgumentException e) {
        System.out.println(e.getMessage());
        printUsage();
      } catch (Throwable e) {
        System.out.println("Failed to process agent option");
        e.printStackTrace();
      }
    }
  }

  private static void printUsage() {
    System.out.println("usage: ");
    System.out.println("       [-li | --list-integrations]");
    System.out.println("       [-h  | --help]");
    System.out.println("       [-v  | --version]");
  }

  private static void printIntegrationNames() throws Exception {
    CodeSource codeSource = thisClass.getProtectionDomain().getCodeSource();
    if (codeSource == null || codeSource.getLocation() == null) {
      throw new MalformedURLException("Could not get jar location from code source");
    }

    Class<?> agentClass =
        ClassLoader.getSystemClassLoader().loadClass("datadog.trace.bootstrap.Agent");
    Method listMethod = agentClass.getMethod("listIntegrationNames", URL.class);
    for (String name : (Iterable<String>) listMethod.invoke(null, codeSource.getLocation())) {
      System.out.println(name);
    }
  }

  private static void printAgentVersion() {
    try {
      System.out.println(getAgentVersion());
    } catch (final Exception e) {
      System.out.println("Failed to parse agent version");
      e.printStackTrace();
    }
  }

  public static String getAgentVersion() throws IOException {
    final StringBuilder sb = new StringBuilder();
    try (final BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(
                thisClass.getResourceAsStream("/dd-java-agent.version"), StandardCharsets.UTF_8))) {

      for (int c = reader.read(); c != -1; c = reader.read()) {
        sb.append((char) c);
      }
    }

    return sb.toString().trim();
  }
}
