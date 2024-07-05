package datadog.trace.bootstrap;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Telemetry class used to relay information about tracer activation. */
abstract class InitializationTelemetry {
  public static final InitializationTelemetry noneInstance() {
    return None.INSTANCE;
  }

  public static final InitializationTelemetry createFromForwarderPath(String forwarderPath) {
    return new ViaForwarderExecutable(forwarderPath);
  }

  public abstract void initMetaInfo(String attr, String value);

  public abstract void onAbort(String reasonCode);

  public abstract void onAbort(Throwable t);

  public abstract void onAbortRuntime(String reasonCode);

  public abstract void onComplete();

  public abstract void flush();

  static final class None extends InitializationTelemetry {
    static final None INSTANCE = new None();

    private None() {}

    @Override
    public void initMetaInfo(String attr, String value) {}

    @Override
    public void onAbort(String reasonCode) {}

    @Override
    public void onAbortRuntime(String reasonCode) {}

    @Override
    public void onAbort(Throwable t) {}

    @Override
    public void onComplete() {}

    @Override
    public void flush() {}
  }

  static final class ViaForwarderExecutable extends InitializationTelemetry {
    private final String forwarderPath;

    private JsonBuffer metaBuffer = new JsonBuffer();
    private JsonBuffer pointsBuffer = new JsonBuffer();

    ViaForwarderExecutable(String forwarderPath) {
      this.forwarderPath = forwarderPath;
    }

    @Override
    public void initMetaInfo(String attr, String value) {
      synchronized (metaBuffer) {
        metaBuffer.name(attr).value(value);
      }
    }

    @Override
    public void onAbort(String reasonCode) {
      onPoint("library_entrypoint.abort", "reason:" + reasonCode);
    }

    @Override
    public void onAbort(Throwable t) {
      onPoint("library_entrypoint.error", "error_type:" + t.getClass().getName());
    }

    @Override
    public void onComplete() {
      onPoint("library_entrypoint.complete");
    }

    @Override
    public void onAbortRuntime(String reasonCode) {}

    void onPoint(String pointName) {
      synchronized (pointsBuffer) {
        pointsBuffer.beginObject();
        pointsBuffer.name("name").value(pointName);
        pointsBuffer.endObject();
      }
    }

    void onPoint(String pointName, String tag) {
      synchronized (pointsBuffer) {
        pointsBuffer.beginObject();
        pointsBuffer.name("name").value(pointName);
        pointsBuffer.name("tags").array(tag);
        pointsBuffer.endObject();
      }
    }

    void onPoint(String pointName, String[] tags) {
      synchronized (pointsBuffer) {
        pointsBuffer.beginObject();
        pointsBuffer.name("name").value(pointName);
        pointsBuffer.name("tags").array(tags);
        pointsBuffer.endObject();
      }
    }

    @Override
    public void flush() {
      JsonBuffer buffer = new JsonBuffer();
      buffer.beginObject();

      buffer.name("metadata");
      synchronized (metaBuffer) {
        buffer.object(metaBuffer);
      }

      buffer.name("points");
      synchronized (pointsBuffer) {
        buffer.array(pointsBuffer);

        pointsBuffer.reset();
      }

      buffer.endObject();

      try {
        send(buffer);
      } catch (Throwable t) {
        // ignore
      }
    }

    void send(JsonBuffer buffer) throws IOException {
      ProcessBuilder builder = new ProcessBuilder(forwarderPath);

      Process process = builder.start();
      process.getOutputStream().write(buffer.toByteArray());

      try {
        process.waitFor(1, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        // just for hygiene, reset the interrupt status
        Thread.currentThread().interrupt();
      }
    }
  }
}
