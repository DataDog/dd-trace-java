package datadog.trace.bootstrap;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;

/** Special lightweight pre-main class to enable SSI inject for Java 6. */
public class AgentBootstrapJava6 {
  public static void premain(final String agentArgs, final Instrumentation inst) {
    agentmain(agentArgs, inst);
  }

  public static void agentmain(final String agentArgs, final Instrumentation inst) {
    try {
      String version = System.getProperty("java.version");
      // Found Java 6
      if (version.startsWith("1.6")) {
        reportUnsupportedJava6(version);
      } else {
        continueBootstrap(agentArgs, inst);
      }
    } catch (Throwable e) {
      error("Agent pre-main function failed", e);
    }
  }

  private static void error(String msg, Throwable e) {
    log(msg + ": " + e.getMessage());
  }

  private static void log(String msg) {
    // We don't have a log manager here, so just print.
    System.out.println(msg);
  }

  public static void reportUnsupportedJava6(String javaVersion) {
    try {
      String agentVersion = getAgentVersion();
      log(
          "Warning: "
              + agentVersion
              + " of dd-java-agent is not compatible with Java "
              + javaVersion
              + " and will not be installed.");
      log("Please upgrade your Java version to 8+");
      String forwarderPath = null;
      try {
        forwarderPath = System.getenv("DD_TELEMETRY_FORWARDER_PATH");
      } catch (SecurityException e) {
        log("Failed to get DD_TELEMETRY_FORWARDER_PATH: " + e.getMessage());
      }
      if (forwarderPath == null) {
        log("Telemetry forwarder path is null.");
        return;
      }
      String payload =
          "{\"metadata\":{"
              + "\"runtime_name\":\"jvm\","
              + "\"language_name\":\"jvm\","
              + "\"runtime_version\":\""
              + javaVersion
              + "\","
              + "\"language_version\":\""
              + javaVersion
              + "\","
              + "\"tracer_version\":\""
              + agentVersion
              + "\"},"
              + "\"points\":\"[{"
              + "\"name\":\"library_entrypoint.abort\","
              + "\"tags\":[\"reason:incompatible_runtime\"]"
              + "}]"
              + "}";

      ForwarderJsonSenderThread t = new ForwarderJsonSenderThread(forwarderPath, payload);
      t.start();

      try {
        t.join(1000);
      } catch (InterruptedException e) {
        // just for hygiene, reset the interrupt status
        Thread.currentThread().interrupt();
      }
    } catch (Throwable e) {
      error("Failed to report telemetry", e);
    }
  }

  public static String getAgentVersion() {
    try {
      // Get the resource as an InputStream
      InputStream is = AgentBootstrapJava6.class.getResourceAsStream("/dd-java-agent.version");
      if (is == null) {
        return "n/a";
      }

      BufferedReader reader = new BufferedReader(new InputStreamReader(is));
      final StringBuilder sb = new StringBuilder();
      for (int c = reader.read(); c != -1; c = reader.read()) {
        sb.append((char) c);
      }
      reader.close();

      return sb.toString().trim();
    } catch (Throwable e) {
      return "unknown";
    }
  }

  private static void continueBootstrap(final String agentArgs, final Instrumentation inst) {
    try {
      Class<?> clazz = Class.forName("datadog.trace.bootstrap.AgentBootstrap");
      Method agentMain = clazz.getMethod("agentmain", String.class, Instrumentation.class);
      agentMain.invoke(null, agentArgs, inst);
    } catch (Throwable e) {
      System.err.println("Failed to install dd-java-agent: " + e.getMessage());
    }
  }

  public static final class ForwarderJsonSenderThread extends Thread {
    private final String forwarderPath;
    private final String payload;

    public ForwarderJsonSenderThread(String forwarderPath, String payload) {
      super("dd-forwarder-json-sender");
      setDaemon(true);
      this.forwarderPath = forwarderPath;
      this.payload = payload;
    }

    @Override
    public void run() {
      ProcessBuilder builder = new ProcessBuilder(forwarderPath, "library_entrypoint");
      try {
        Process process = builder.start();
        OutputStream out = null;
        try {
          out = process.getOutputStream();
          out.write(payload.getBytes());
        } finally {
          if (out != null) {
            out.close();
          }
        }
      } catch (Throwable e) {
        error("Failed to send telemetry", e);
      }
    }
  }
}
