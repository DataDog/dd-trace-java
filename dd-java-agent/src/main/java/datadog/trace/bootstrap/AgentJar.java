package datadog.trace.bootstrap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.instrument.Instrumentation;
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
          case "sampleTrace":
            sendSampleTrace(args);
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
    System.out.println("usage: sampleTrace [-c count] [-i interval]");
    System.out.println("       [-li | --list-integrations]");
    System.out.println("       [-h  | --help]");
    System.out.println("       [-v  | --version]");
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

    CodeSource codeSource = thisClass.getProtectionDomain().getCodeSource();
    if (codeSource == null || codeSource.getLocation() == null) {
      throw new MalformedURLException("Could not get jar location from code source");
    }

    Class<?> agentClass =
        ClassLoader.getSystemClassLoader().loadClass("datadog.trace.bootstrap.Agent");
    Method startMethod = agentClass.getMethod("start", Instrumentation.class, URL.class);
    startMethod.invoke(null, null, codeSource.getLocation());

    Class<?> tracerClass =
        ClassLoader.getSystemClassLoader()
            .loadClass("datadog.trace.bootstrap.instrumentation.api.AgentTracer");
    Method startSpanMethod = tracerClass.getMethod("startSpan", CharSequence.class);

    Class<?> spanClass =
        ClassLoader.getSystemClassLoader()
            .loadClass("datadog.trace.bootstrap.instrumentation.api.AgentSpan");
    Method finishSpanMethod = spanClass.getMethod("finish");

    int numTraces = 0;
    while (++numTraces <= count || count < 0) {
      Object span = startSpanMethod.invoke(null, "sample");
      Thread.sleep(Math.max((long) (1000.0 * interval), 1L));
      finishSpanMethod.invoke(span);
      if (count < 0) {
        System.out.println("... sent " + numTraces + (numTraces < 2 ? " trace" : " traces"));
      } else {
        System.out.println("... sent " + numTraces + "/" + count + " traces");
      }
    }
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
