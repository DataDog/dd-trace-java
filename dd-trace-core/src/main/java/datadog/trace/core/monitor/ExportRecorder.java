package datadog.trace.core.monitor;

import datadog.trace.common.writer.RemoteApi;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Reports one exporter's sends to the {@link HealthMetrics} export hooks. It holds the per-send
 * state the hooks need (item count, start time, finished flag) so each send site stays two calls.
 * Wrap each whole send call, retries and backoff included:
 *
 * <pre>{@code
 * ExportRecorder.Export export = recorder.start(items);
 * export.finish(sender.send(payload)); // or export.failure(e) if send throws
 * }</pre>
 */
public final class ExportRecorder {
  /** Reports nothing. */
  public static final ExportRecorder NO_OP = new ExportRecorder(HealthMetrics.NO_OP, null);

  private final HealthMetrics sink;
  private final OtelExporterId exporter;

  private ExportRecorder(HealthMetrics sink, OtelExporterId exporter) {
    this.sink = sink;
    this.exporter = exporter;
  }

  /** Returns the recorder of {@code exporter} reporting to {@code sink}, or {@link #NO_OP}. */
  public static ExportRecorder create(HealthMetrics sink, OtelExporterId exporter) {
    if (sink == HealthMetrics.NO_OP) {
      return NO_OP;
    }
    return new ExportRecorder(sink, Objects.requireNonNull(exporter, "exporter"));
  }

  /** Starts one export of {@code itemCount} items. */
  public Export start(long itemCount) {
    if (this == NO_OP) {
      return Export.FINISHED;
    }
    sink.onExportStart(exporter, itemCount);
    return new Export(this, itemCount, System.nanoTime(), false);
  }

  /**
   * One export in progress, used by the thread doing the send. Finish it exactly once; later calls
   * are ignored.
   */
  public static final class Export {
    /** Shared by {@link ExportRecorder#NO_OP}: already finished, so it reports nothing. */
    static final Export FINISHED = new Export(NO_OP, 0, 0, true);

    private final ExportRecorder recorder;
    private final long itemCount;
    private final long startNanos;
    private boolean finished;

    private Export(ExportRecorder recorder, long itemCount, long startNanos, boolean finished) {
      this.recorder = recorder;
      this.itemCount = itemCount;
      this.startNanos = startNanos;
      this.finished = finished;
    }

    /** Finishes the export with the sender's response, which may report a failure. */
    public void finish(@Nullable RemoteApi.Response response) {
      end(response, null);
    }

    /** Fails the export with the exception thrown by the send. */
    public void failure(Throwable error) {
      end(null, error);
    }

    private void end(@Nullable RemoteApi.Response response, @Nullable Throwable error) {
      if (finished) {
        return;
      }
      finished = true;
      recorder.sink.onExportEnd(
          recorder.exporter, itemCount, System.nanoTime() - startNanos, response, error);
    }
  }
}
