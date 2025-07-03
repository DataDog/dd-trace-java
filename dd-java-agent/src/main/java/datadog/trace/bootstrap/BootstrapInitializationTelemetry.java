package datadog.trace.bootstrap;

import datadog.json.JsonWriter;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

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
   * Sets the injection result. 
   */
  public abstract void setInjectResult(String result);

  /**
   * Sets the injection result reason - a customer-facing reason for the injection result
   */
  public abstract void setInjectResultReason(String reason);

  /**
   * Sets the injection result class - a standardized classification type for results across components
   */
  public abstract void setInjectResultClass(String resultClass);

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
    public void setInjectResult(String result) {}

    @Override
    public void setInjectResultReason(String reason) {}

    @Override
    public void setInjectResultClass(String resultClass) {}

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
    private final List<String> points;

    // one way false to true
    private volatile boolean incomplete = false;

    JsonBased(JsonSender sender) {
      this.sender = sender;
      this.meta = new ArrayList<>();
      this.points = new ArrayList<>();
    }

    @Override
    public void initMetaInfo(String attr, String value) {
      synchronized (this.meta) {
        this.meta.add(attr);
        this.meta.add(value);
      }
    }

    @Override
    public void setInjectResult(String result) {
      initMetaInfo("inject_result", result);
    }

    @Override
    public void setInjectResultReason(String reason) {
      initMetaInfo("inject_result_reason", reason);
    }

    @Override
    public void setInjectResultClass(String resultClass) {
      initMetaInfo("inject_result_class", resultClass);
    }

    @Override
    public void onAbort(String reasonCode) {
      onPoint("library_entrypoint.abort", "reason:" + reasonCode);
      setInjectResult("abort");
      setInjectResultReason("Abort condition occurred: " + reasonCode);
      setInjectResultClass("abort");
      markIncomplete();
    }

    @Override
    public void onError(Throwable t) {
      onPoint("library_entrypoint.error", "error_type:" + t.getClass().getName());
      setInjectResult("error");
      setInjectResultReason("Exception occurred: " + t.getMessage());
      setInjectResultClass("exception");
    }

    @Override
    public void onFatalError(Throwable t) {
      onError(t);
      setInjectResult("error");
      setInjectResultReason("Exception occurred: " + t.getMessage());
      setInjectResultClass("fatal error");
      markIncomplete();
    }

    @Override
    public void onError(String reasonCode) {
      onPoint("library_entrypoint.error", "error_type:" + reasonCode);
      setInjectResult("error");
      setInjectResultReason("Error occurred: " + reasonCode);
      setInjectResultClass("error");
    }

    private void onPoint(String name, String tag) {
      synchronized (this.points) {
        this.points.add(name);
        this.points.add(tag);
        setInjectResult("success");
        setInjectResultReason("Success");
        setInjectResultClass("success");
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
          for (int i = 0; i + 1 < this.points.size(); i = i + 2) {
            writer.beginObject();
            writer.name("name").value(this.points.get(i));
            writer.name("tags").beginArray().value(this.points.get(i + 1)).endArray();
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

      try {
        Process process = builder.start();
        try (OutputStream out = process.getOutputStream()) {
          out.write(payload);
        }
      } catch (Throwable e) {
        // We don't have a log manager here, so just print.
        System.err.println("Failed to send telemetry: " + e.getMessage());
      }
    }
  }
}
