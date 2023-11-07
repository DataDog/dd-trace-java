package com.datadog.profiling.ddprof;

import com.datadog.profiling.controller.OngoingRecording;
import datadog.trace.api.profiling.ProfilingSnapshot;
import datadog.trace.api.profiling.RecordingData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DatadogProfilerRecording implements OngoingRecording {
  private static final Logger log = LoggerFactory.getLogger(DatadogProfilerRecording.class);

  private final DatadogProfiler profiler;
  private volatile Path recordingFile;
  private final Instant started = Instant.now();

  /**
   * Do not use this constructor directly. Rather use {@linkplain DatadogProfiler#start()}
   *
   * @param profiler the associated profiler
   * @throws IOException
   */
  DatadogProfilerRecording(DatadogProfiler profiler) throws IOException {
    this.profiler = profiler;
    this.recordingFile = profiler.newRecording();
  }

  @Nonnull
  @Override
  public RecordingData stop() {
    profiler.stopProfiler();
    return new DatadogProfilerRecordingData(
        recordingFile, started, Instant.now(), ProfilingSnapshot.Kind.ON_SHUTDOWN);
  }

  // @VisibleForTesting
  final RecordingData snapshot(@Nonnull Instant start) {
    return snapshot(start, ProfilingSnapshot.Kind.PERIODIC);
  }

  @Nonnull
  @Override
  public RecordingData snapshot(@Nonnull Instant start, @Nonnull ProfilingSnapshot.Kind kind) {
    try {
      Path recFile = Files.createTempFile("dd-profiler-snapshot-", ".jfr");
      profiler.dump(recFile);
      return new DatadogProfilerRecordingData(recFile, start, Instant.now(), kind);
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  @Override
  public void close() {
    try {
      Files.deleteIfExists(recordingFile);
    } catch (IOException ignored) {
    }
  }

  // used for tests only
  Path getRecordingFile() {
    return recordingFile;
  }
}
