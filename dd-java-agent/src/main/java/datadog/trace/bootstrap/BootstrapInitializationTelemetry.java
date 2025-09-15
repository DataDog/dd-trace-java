package datadog.trace.bootstrap;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

import datadog.json.JsonWriter;
import datadog.trace.bootstrap.environment.EnvironmentVariables;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.Closeable;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Thread safe telemetry class used to relay information about tracer activation. */
public abstract class BootstrapInitializationTelemetry {
  private static final int DEFAULT_MAX_TAGS = 5;

  /** Returns a singleton no op instance of initialization telemetry */
  public static BootstrapInitializationTelemetry noOpInstance() {
    return NoOp.INSTANCE;
  }

  /**
   * Constructs a JSON-based instrumentation telemetry that forwards through a helper executable -
   * indicated by forwarderPath
   *
   * @param forwarderPath - a String - path to forwarding executable
   */
  public static BootstrapInitializationTelemetry createFromForwarderPath(String forwarderPath) {
    return new JsonBased(new ForwarderJsonSender(forwarderPath));
  }

  /**
   * Adds meta information about the process to the initialization telemetry Does NOT support
   * overriding an attr, each attr should be once and only once
   */
  public abstract void initMetaInfo(String attr, String value);

  /**
   * Indicates that an abort condition occurred during the bootstrapping process Abort conditions
   * are assumed to leave the bootstrapping process incomplete. {@link #markIncomplete()}
   */
  public abstract void onAbort(String reasonCode);

  /**
   * Indicates that an exception occurred during the bootstrapping process By default the exception
   * is assumed to NOT have fully stopped the initialization of the tracer.
   *
   * <p>If this exception stops the core bootstrapping of the tracer, then {@link #markIncomplete()}
   * should also be called.
   */
  public abstract void onError(Throwable t);

  public abstract void onError(String reasonCode);

  /**
   * Indicates an exception that occurred during the bootstrapping process that left initialization
   * incomplete. Equivalent to calling {@link #onError(Throwable)} and {@link #markIncomplete()}
   */
  public abstract void onFatalError(Throwable t);

  public abstract void markIncomplete();

  public abstract void finish();

  public static final class NoOp extends BootstrapInitializationTelemetry {
    static final NoOp INSTANCE = new NoOp();

    private NoOp() {}

    @Override
    public void initMetaInfo(String attr, String value) {}

    @Override
    public void onAbort(String reasonCode) {}

    @Override
    public void onError(String reasonCode) {}

    @Override
    public void onError(Throwable t) {}

    @Override
    public void onFatalError(Throwable t) {}

    @Override
    public void markIncomplete() {}

    @Override
    public void finish() {}
  }

  public static final class JsonBased extends BootstrapInitializationTelemetry {
    private final JsonSender sender;

    private final Telemetry telemetry;

    // one way false to true
    private volatile boolean incomplete = false;

    JsonBased(JsonSender sender) {
      this.sender = sender;
      this.telemetry = new Telemetry();
    }

    @Override
    public void initMetaInfo(String attr, String value) {
      telemetry.setMetadata(attr, value);
    }

    @Override
    public void onAbort(String reasonCode) {
      onPoint("library_entrypoint.abort", singletonList("reason:" + reasonCode));
      markIncomplete();
      setResultMeta("abort", mapResultClass(reasonCode), reasonCode);
    }

    @Override
    public void onError(Throwable t) {
      setResultMeta("error", "internal_error", t.getMessage());

      List<String> causes = new ArrayList<>();

      Throwable cause = t.getCause();
      while (cause != null) {
        causes.add("error_type:" + cause.getClass().getName());
        cause = cause.getCause();
      }
      causes.add("error_type:" + t.getClass().getName());

      // Limit the number of tags to avoid overpopulating the JSON payload.
      int maxTags = maxTags();
      int numCauses = causes.size();
      if (numCauses > maxTags) {
        causes = causes.subList(numCauses - maxTags, numCauses);
      }

      onPoint("library_entrypoint.error", causes);
    }

    @Override
    public void onError(String reasonCode) {
      onPoint("library_entrypoint.error", singletonList("error_type:" + reasonCode));
      setResultMeta("error", mapResultClass(reasonCode), reasonCode);
    }

    private int maxTags() {
      String maxTags = EnvironmentVariables.get("DD_TELEMETRY_FORWARDER_MAX_TAGS");

      if (maxTags != null) {
        try {
          return Integer.parseInt(maxTags);
        } catch (Throwable ignore) {
          // Ignore and return default value.
        }
      }

      return DEFAULT_MAX_TAGS;
    }

    @Override
    public void onFatalError(Throwable t) {
      onError(t);
      markIncomplete();
    }

    private void setResultMeta(String result, String resultClass, String resultReason) {
      initMetaInfo("result", result);
      initMetaInfo("result_class", resultClass);
      initMetaInfo("result_reason", resultReason);
    }

    private String mapResultClass(String reasonCode) {
      if (reasonCode == null) {
        return "success";
      }

      switch (reasonCode) {
        case "already_initialized":
          return "already_instrumented";
        case "other-java-agents":
          return "incompatible_library";
        case "jdk_tool":
          return "unsupported_binary";
        default:
          return "unknown";
      }
    }

    private void onPoint(String name, List<String> tags) {
      telemetry.addPoint(name, tags);
    }

    @Override
    public void markIncomplete() {
      this.incomplete = true;
    }

    @Override
    public void finish() {
      if (!this.incomplete) {
        onPoint("library_entrypoint.complete", emptyList());
      }

      this.sender.send(telemetry);
    }
  }

  public static class Telemetry {
    private final Map<String, String> metadata;
    private final Map<String, List<String>> points;

    public Telemetry() {
      metadata = new LinkedHashMap<>();
      points = new LinkedHashMap<>();

      setResults("success", "success", "Successfully configured ddtrace package");
    }

    public void setMetadata(String name, String value) {
      synchronized (metadata) {
        metadata.put(name, value);
      }
    }

    public void setResults(String result, String resultClass, String resultReason) {
      synchronized (metadata) {
        metadata.put("result", result);
        metadata.put("result_class", resultClass);
        metadata.put("result_reason", resultReason);
      }
    }

    public void addPoint(String name, List<String> tags) {
      synchronized (points) {
        points.put(name, tags);
      }
    }

    @Override
    public String toString() {
      try (JsonWriter writer = new JsonWriter()) {
        writer.beginObject();
        writer.name("metadata").beginObject();
        synchronized (metadata) {
          for (Map.Entry<String, String> entry : metadata.entrySet()) {
            writer.name(entry.getKey());
            writer.value(entry.getValue());
          }

          metadata.clear();
        }
        writer.endObject();

        writer.name("points").beginArray();
        synchronized (points) {
          for (Map.Entry<String, List<String>> entry : points.entrySet()) {
            writer.beginObject();
            writer.name("name").value(entry.getKey());
            if (!entry.getValue().isEmpty()) {
              writer.name("tags").beginArray();
              for (String tag : entry.getValue()) {
                writer.value(tag);
              }
              writer.endArray();
            }
            writer.endObject();
          }

          points.clear();
        }
        writer.endArray();
        writer.endObject();

        return writer.toString();
      }
    }
  }

  /**
   * Declare telemetry as {@code Object} to avoid issue with double class loading from different
   * classloaders.
   */
  public interface JsonSender {
    void send(Object telemetry);
  }

  public static final class ForwarderJsonSender implements JsonSender {
    private final String forwarderPath;

    ForwarderJsonSender(String forwarderPath) {
      this.forwarderPath = forwarderPath;
    }

    @Override
    public void send(Object telemetry) {
      ForwarderJsonSenderThread t = new ForwarderJsonSenderThread(forwarderPath, telemetry);
      t.setDaemon(true);
      t.start();
    }
  }

  public static final class ForwarderJsonSenderThread extends Thread {
    private final String forwarderPath;
    private final Object telemetry;

    public ForwarderJsonSenderThread(String forwarderPath, Object telemetry) {
      super("dd-forwarder-json-sender");
      this.forwarderPath = forwarderPath;
      this.telemetry = telemetry;
    }

    @SuppressForbidden
    @Override
    public void run() {
      ProcessBuilder builder = new ProcessBuilder(forwarderPath, "library_entrypoint");

      // Run forwarder and mute tracing for subprocesses executed in by dd-java-agent.
      try (final Closeable ignored = muteTracing()) {
        byte[] payload = telemetry.toString().getBytes();

        Process process = builder.start();
        try (OutputStream out = process.getOutputStream()) {
          out.write(payload);
        }
      } catch (Throwable e) {
        // We don't have a log manager here, so just print.
        System.err.println("Failed to send telemetry: " + e.getMessage());
      }
    }

    @SuppressForbidden
    private Closeable muteTracing() {
      try {
       // process.waitFor();
        Class<?> agentTracerClass =
            Class.forName("datadog.trace.bootstrap.instrumentation.api.AgentTracer");
        Object tracerAPI = agentTracerClass.getMethod("get").invoke(null);
        Object scope = tracerAPI.getClass().getMethod("muteTracing").invoke(tracerAPI);
        return (Closeable) scope;
      } catch (Throwable e) {
        // Ignore all exceptions and fallback to No-Op Closable.
        return () -> {};
      }
    }
  }
}
