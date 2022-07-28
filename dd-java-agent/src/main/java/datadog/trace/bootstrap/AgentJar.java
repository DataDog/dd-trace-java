package datadog.trace.bootstrap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
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
    System.out.println("  uploadCrash [FILES]");
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
    Object cookie = installAgentClassLoader();
    try {
      installAgentCLI()
          .getMethod("uploadCrash", String[].class)
          .invoke(null, new Object[] {Arrays.copyOfRange(args, 1, args.length)});
    } finally {
      uninstallAgentClassLoader(cookie);
    }
  }

  private static void printIntegrationNames() throws Exception {
    installAgentCLI().getMethod("printIntegrationNames").invoke(null);
  }

  private static Class<?> installAgentCLI() throws Exception {
    CodeSource codeSource = thisClass.getProtectionDomain().getCodeSource();
    if (codeSource == null || codeSource.getLocation() == null) {
      throw new MalformedURLException("Could not get jar location from code source");
    }

    return (Class<?>)
        agentClass.getMethod("installAgentCLI", URL.class).invoke(null, codeSource.getLocation());
  }

  private static Object installAgentClassLoader() throws Exception {
    CodeSource codeSource = thisClass.getProtectionDomain().getCodeSource();
    if (codeSource == null || codeSource.getLocation() == null) {
      throw new MalformedURLException("Could not get jar location from code source");
    }

    return agentClass
        .getMethod("installAgentClassLoader", URL.class)
        .invoke(null, codeSource.getLocation());
  }

  private static void uninstallAgentClassLoader(Object cookie) throws Exception {
    agentClass.getMethod("uninstallAgentClassLoader", Object.class).invoke(null, cookie);
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
