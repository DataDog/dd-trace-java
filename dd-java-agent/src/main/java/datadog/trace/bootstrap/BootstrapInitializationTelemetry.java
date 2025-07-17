package datadog.trace.bootstrap;

import datadog.json.JsonWriter;
import datadog.trace.bootstrap.environment.EnvironmentVariables;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.Closeable;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
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

    private final Map<String, String> meta;
    private final Map<String, List<String>> points;

    // one way false to true
    private volatile boolean incomplete = false;

    JsonBased(JsonSender sender) {
      this.sender = sender;
      this.meta = new LinkedHashMap<>();
      this.points = new LinkedHashMap<>();

      setMetaInfo("success", "success", "Successfully configured ddtrace package");
    }

    @Override
    public void initMetaInfo(String attr, String value) {
      synchronized (this.meta) {
        this.meta.put(attr, value);
      }
    }

    @Override
    public void onAbort(String reasonCode) {
      onPoint("library_entrypoint.abort", "reason:" + reasonCode);
      markIncomplete();
      setMetaInfo("abort", mapResultClass(reasonCode), reasonCode);
    }

    @Override
    public void onError(Throwable t) {
      setMetaInfo("error", "internal_error", t.getMessage());

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

      onPoint("library_entrypoint.error", causes.toArray(new String[0]));
    }

    @Override
    public void onError(String reasonCode) {
      onPoint("library_entrypoint.error", "error_type:" + reasonCode);
      setMetaInfo("error", mapResultClass(reasonCode), reasonCode);
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

    private void setMetaInfo(String result, String resultClass, String resultReason) {
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

    private void onPoint(String name, String... tags) {
      synchronized (this.points) {
        this.points.put(name, Arrays.asList(tags));
      }
    }

    @Override
    public void markIncomplete() {
      this.incomplete = true;
    }

    @Override
    public void finish() {
      if (!this.incomplete) {
        onPoint("library_entrypoint.complete");
      }

      this.sender.send(meta, points);
    }
  }

  public interface JsonSender {
    void send(Map<String, String> meta, Map<String, List<String>> points);
  }

  public static final class ForwarderJsonSender implements JsonSender {
    private final String forwarderPath;

    ForwarderJsonSender(String forwarderPath) {
      this.forwarderPath = forwarderPath;
    }

    @Override
    public void send(Map<String, String> meta, Map<String, List<String>> points) {
      ForwarderJsonSenderThread t = new ForwarderJsonSenderThread(forwarderPath, meta, points);
      t.setDaemon(true);
      t.start();
    }
  }

  public static final class ForwarderJsonSenderThread extends Thread {
    private final String forwarderPath;
    private final Map<String, String> meta;
    private final Map<String, List<String>> points;

    public ForwarderJsonSenderThread(
        String forwarderPath, Map<String, String> meta, Map<String, List<String>> points) {
      super("dd-forwarder-json-sender");
      this.forwarderPath = forwarderPath;
      this.meta = meta;
      this.points = points;
    }

    @SuppressForbidden
    @Override
    public void run() {
      ProcessBuilder builder = new ProcessBuilder(forwarderPath, "library_entrypoint");

      // Run forwarder and mute tracing for subprocesses executed in by dd-java-agent.
      try (final Closeable ignored = muteTracing()) {
        byte[] payload = json();

        Process process = builder.start();
        try (OutputStream out = process.getOutputStream()) {
          out.write(payload);
        }
      } catch (Throwable e) {
        // We don't have a log manager here, so just print.
        System.err.println("Failed to send telemetry: " + e.getMessage());
      }
    }

    private byte[] json() {
      try (JsonWriter writer = new JsonWriter()) {
        writer.beginObject();
        writer.name("metadata").beginObject();
        synchronized (this.meta) {
          for (Map.Entry<String, String> entry : meta.entrySet()) {
            writer.name(entry.getKey());
            writer.value(entry.getValue());
          }
        }
        writer.endObject();

        writer.name("points").beginArray();
        synchronized (this.points) {
          for (Map.Entry<String, List<String>> entry : points.entrySet()) {
            writer.beginObject();
            writer.name("name").value(entry.getKey());
            writer.name("tags").beginArray();
            for (String tag : entry.getValue()) {
              writer.value(tag);
            }
            writer.endArray();
            writer.endObject();
          }
          this.points.clear();
        }
        writer.endArray();
        writer.endObject();

        return writer.toByteArray();
      }
    }

    @SuppressForbidden
    private Closeable muteTracing() {
      try {
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
