package datadog.trace.bootstrap;

import datadog.json.JsonWriter;
import datadog.trace.bootstrap.environment.EnvironmentVariables;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.Closeable;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Thread safe telemetry class used to relay information about tracer activation. */
public abstract class BootstrapInitializationTelemetry {
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

  /**
   * Indicates an exception that occurred during the bootstrapping process that left initialization
   * incomplete. Equivalent to calling {@link #onError(Throwable)} and {@link #markIncomplete()}
   */
  public abstract void onFatalError(Throwable t);

  public abstract void onError(String reasonCode);

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
    public void onFatalError(Throwable t) {}

    @Override
    public void onError(Throwable t) {}

    @Override
    public void markIncomplete() {}

    @Override
    public void finish() {}
  }

  public static final class JsonBased extends BootstrapInitializationTelemetry {
    private final JsonSender sender;

    private final List<String> meta;
    private final Map<String, List<String>> points;

    // one way false to true
    private volatile boolean incomplete = false;

    JsonBased(JsonSender sender) {
      this.sender = sender;
      this.meta = new ArrayList<>();
      this.points = new LinkedHashMap<>();
    }

    @Override
    public void initMetaInfo(String attr, String value) {
      synchronized (this.meta) {
        this.meta.add(attr);
        this.meta.add(value);
      }
    }

    @Override
    public void onAbort(String reasonCode) {
      onPoint("library_entrypoint.abort", "reason:" + reasonCode);
      markIncomplete();
    }

    @Override
    public void onError(Throwable t) {
      List<String> causes = new ArrayList<>();

      Throwable cause = t.getCause();
      if (cause != null) {
        while (cause != null) {
          causes.add("error_type:" + cause.getClass().getName());
          cause = cause.getCause();
        }
      }
      causes.add("error_type:" + t.getClass().getName());

      // Limit the number of tags to avoid overpopulating the JSON payload.
      int maxTags = EnvironmentVariables.getOrDefault("DD_TELEMETRY_FORWARDER_MAX_TAGS", 5);
      int sz = causes.size();
      int cnt = Math.min(maxTags, sz);

      onPoint("library_entrypoint.error", causes.subList(sz - cnt, sz));
    }

    @Override
    public void onFatalError(Throwable t) {
      onError(t);
      markIncomplete();
    }

    @Override
    public void onError(String reasonCode) {
      onPoint("library_entrypoint.error", "error_type:" + reasonCode);
    }

    private void onPoint(String name, String tag) {
      onPoint(name, Collections.singletonList(tag));
    }

    private void onPoint(String name, List<String> tags) {
      synchronized (this.points) {
        this.points.put(name, tags);
      }
    }

    @Override
    public void markIncomplete() {
      this.incomplete = true;
    }

    @Override
    public void finish() {
      try (JsonWriter writer = new JsonWriter()) {
        writer.beginObject();
        writer.name("metadata").beginObject();
        synchronized (this.meta) {
          for (int i = 0; i + 1 < this.meta.size(); i = i + 2) {
            writer.name(this.meta.get(i));
            writer.value(this.meta.get(i + 1));
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
        if (!this.incomplete) {
          writer.beginObject().name("name").value("library_entrypoint.complete").endObject();
        }
        writer.endArray();
        writer.endObject();

        this.sender.send(writer.toByteArray());
      } catch (Throwable t) {
        // Since this is the reporting mechanism, there's little recourse here
        // Decided to simply ignore - arguably might want to write to stderr
      }
    }
  }

  public interface JsonSender {
    void send(byte[] payload);
  }

  public static final class ForwarderJsonSender implements JsonSender {
    private final String forwarderPath;

    ForwarderJsonSender(String forwarderPath) {
      this.forwarderPath = forwarderPath;
    }

    @Override
    public void send(byte[] payload) {
      ForwarderJsonSenderThread t = new ForwarderJsonSenderThread(forwarderPath, payload);
      t.setDaemon(true);
      t.start();
    }
  }

  public static final class ForwarderJsonSenderThread extends Thread {
    private final String forwarderPath;
    private final byte[] payload;

    public ForwarderJsonSenderThread(String forwarderPath, byte[] payload) {
      super("dd-forwarder-json-sender");
      this.forwarderPath = forwarderPath;
      this.payload = payload;
    }

    @SuppressForbidden
    @Override
    public void run() {
      ProcessBuilder builder = new ProcessBuilder(forwarderPath, "library_entrypoint");

      // Run forwarder and mute tracing for subprocesses executed in by dd-java-agent.
      try (final Closeable ignored = muteTracing()) {
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
