package datadog.trace.bootstrap;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;

/** Special lightweight pre-main class that skips installation on incompatible JVMs. */
public class AgentPreCheck {
  public static void premain(final String agentArgs, final Instrumentation inst) {
    agentmain(agentArgs, inst);
  }

  public static void agentmain(final String agentArgs, final Instrumentation inst) {
    try {
      if (compatible()) {
        continueBootstrap(agentArgs, inst);
      }
    } catch (Throwable e) {
      // If agent failed we should not fail the application.
      // We don't have a log manager here, so just print.
      System.err.println("ERROR: " + e.getMessage());
    }
  }

  private static void reportIncompatibleJava(
      String javaVersion, int majorJavaVersion, String agentVersion, PrintStream output) {
    output.println(
        "Warning: "
            + (agentVersion == null ? "This version" : "Version " + agentVersion)
            + " of dd-java-agent is not compatible with Java "
            + javaVersion
            + " and will not be installed.");

    String msg = "Please upgrade your Java version to 8+";
    if (majorJavaVersion == 7) {
      msg +=
          " or use the 0.x version of dd-java-agent in your build tool or download it from https://dtdg.co/java-tracer-v0";
    }
    output.println(msg);
  }

  static void sendTelemetry(String forwarderPath, String javaVersion, String agentVersion) {
    // Hardcoded payload for unsupported Java version.
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
            + "\"points\":[{"
            + "\"name\":\"library_entrypoint.abort\","
            + "\"tags\":[\"reason:incompatible_runtime\"]"
            + "}]"
            + "}";

    ForwarderJsonSenderThread t = new ForwarderJsonSenderThread(forwarderPath, payload);
    t.setDaemon(true);
    t.start();
  }

  @SuppressForbidden
  private static boolean compatible() {
    return compatible(System.getProperty("java.version"), System.err);
  }

  // Reachable for testing
  static boolean compatible(String javaVersion, PrintStream output) {
    int majorJavaVersion = parseJavaMajorVersion(javaVersion);

    if (majorJavaVersion >= 8) {
      return true;
    }

    String agentVersion = getAgentVersion();

    reportIncompatibleJava(javaVersion, majorJavaVersion, agentVersion, output);

    String forwarderPath = System.getenv("DD_TELEMETRY_FORWARDER_PATH");
    if (forwarderPath != null) {
      sendTelemetry(forwarderPath, javaVersion, agentVersion);
    }

    return false;
  }

  // Reachable for testing
  static int parseJavaMajorVersion(String javaVersion) {
    int major = 0;
    if (javaVersion == null || javaVersion.isEmpty()) {
      return major;
    }

    int start = 0;
    if (javaVersion.charAt(0) == '1'
        && javaVersion.length() >= 3
        && javaVersion.charAt(1) == '.'
        && Character.isDigit(javaVersion.charAt(2))) {
      start = 2;
    }

    // Parse the major digit and be a bit lenient, allowing digits followed by any non digit
    for (int i = start; i < javaVersion.length(); i++) {
      char c = javaVersion.charAt(i);
      if (Character.isDigit(c)) {
        major *= 10;
        major += Character.digit(c, 10);
      } else {
        break;
      }
    }
    return major;
  }

  private static String getAgentVersion() {
    try {
      // Get the resource as an InputStream
      InputStream is = AgentPreCheck.class.getResourceAsStream("/dd-java-agent.version");
      if (is == null) {
        return null;
      }

      BufferedReader reader = new BufferedReader(new InputStreamReader(is));
      final StringBuilder sb = new StringBuilder();
      for (int c = reader.read(); c != -1; c = reader.read()) {
        sb.append((char) c);
      }
      reader.close();

      return sb.toString().trim();
    } catch (Throwable e) {
      return null;
    }
  }

  private static void continueBootstrap(final String agentArgs, final Instrumentation inst)
      throws Exception {
    Class<?> clazz = Class.forName("datadog.trace.bootstrap.AgentBootstrap");
    Method agentMain = clazz.getMethod("agentmain", String.class, Instrumentation.class);
    agentMain.invoke(null, agentArgs, inst);
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
        System.err.println("Failed to send telemetry: " + e.getMessage());
      }
    }
  }
}
