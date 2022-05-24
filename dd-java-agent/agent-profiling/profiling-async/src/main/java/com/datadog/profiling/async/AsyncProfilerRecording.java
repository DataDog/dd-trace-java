package com.datadog.profiling.async;

import com.datadog.profiling.controller.OngoingRecording;
import com.datadog.profiling.controller.RecordingData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AsyncProfilerRecording implements OngoingRecording {
  private static final Logger log = LoggerFactory.getLogger(AsyncProfilerRecording.class);

  private final AsyncProfiler profiler;
  private volatile Path recordingFile;
  private final Instant started = Instant.now();

  /**
   * Do not use this constructor directly. Rather use {@linkplain AuxiliaryAsyncProfiler#start()}
   *
   * @param profiler the associated profiler
   * @throws IOException
   */
  AsyncProfilerRecording(AsyncProfiler profiler) throws IOException {
    this.profiler = profiler;
    this.recordingFile = profiler.newRecording();
  }

  @Nonnull
  @Override
  public RecordingData stop() {
    profiler.stopProfiler();
    return new AsyncProfilerRecordingData(recordingFile, started, Instant.now());
  }

  @Nonnull
  @Override
  public RecordingData snapshot(@Nonnull Instant start) {
    profiler.stop(this);
    RecordingData data = new AsyncProfilerRecordingData(recordingFile, start, Instant.now());
    try {
      recordingFile = profiler.newRecording();
    } catch (IOException | IllegalStateException e) {
      if (log.isDebugEnabled()) {
        log.warn("Unable to start async profiler recording", e);
      } else {
        log.warn("Unable to start async profiler recording: {}", e.getMessage());
      }
    }
    return data;
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
