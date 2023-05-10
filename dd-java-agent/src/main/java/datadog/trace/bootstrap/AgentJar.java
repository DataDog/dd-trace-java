package datadog.trace.bootstrap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Entry point when running the agent as a sample application with -jar. */
public final class AgentJar {
  private static final Class<?> thisClass = AgentJar.class;

  private static Class<?> agentClass;

  public static void main(final String[] args) {
    if (args.length == 0) {
      printAgentVersion();
    } else {
      try {
        // load Agent class
        agentClass = ClassLoader.getSystemClassLoader().loadClass("datadog.trace.bootstrap.Agent");

        switch (args[0]) {
          case "sampleTrace":
            sendSampleTrace(args);
            break;
          case "uploadCrash":
            uploadCrash(args);
            break;
          case "scanDependencies":
            scanDependencies(args);
            break;
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
            throw new IllegalArgumentException(args[0]);
        }
      } catch (IllegalArgumentException e) {
        System.out.println("unknown option: " + e.getMessage());
        printUsage();
      } catch (Throwable e) {
        System.out.println("Failed to process agent option");
        e.printStackTrace();
      }
    }
  }

  private static void printUsage() {
    System.out.println("usage:");
    System.out.println("  sampleTrace [-c count] [-i interval]");
    System.out.println("  uploadCrash file ...");
    System.out.println("  scanDependencies <path> ...");
    System.out.println("  [-li | --list-integrations]");
    System.out.println("  [-h  | --help]");
    System.out.println("  [-v  | --version]");
  }

  private static void sendSampleTrace(final String[] args) throws Exception {
    int count = -1;
    double interval = 1;

    if (args.length % 2 == 0) {
      throw new IllegalArgumentException("missing value");
    }

    for (int i = 1; i < args.length; i += 2) {
      switch (args[i]) {
        case "-c":
          count = Integer.parseInt(args[i + 1]);
          break;
        case "-i":
          interval = Double.parseDouble(args[i + 1]);
          break;
        default:
          throw new IllegalArgumentException(args[i]);
      }
    }

    installAgentCLI()
        .getMethod("sendSampleTraces", int.class, double.class)
        .invoke(null, count, interval);
  }

  private static void uploadCrash(final String[] args) throws Exception {
    if (args.length < 2) {
      throw new IllegalArgumentException("missing file");
    }

    installAgentCLI()
        .getMethod("uploadCrash", String[].class)
        .invoke(null, new Object[] {Arrays.copyOfRange(args, 1, args.length)});
  }

  private static void scanDependencies(final String[] args) throws Exception {
    if (args.length < 2) {
      throw new IllegalArgumentException("missing path");
    }

    installAgentCLI()
        .getMethod("scanDependencies", String[].class)
        .invoke(null, new Object[] {Arrays.copyOfRange(args, 1, args.length)});
  }

  private static void printIntegrationNames() throws Exception {
    installAgentCLI().getMethod("printIntegrationNames").invoke(null);
  }

  private static Class<?> installAgentCLI() throws Exception {
    return (Class<?>) agentClass.getMethod("installAgentCLI").invoke(null);
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
