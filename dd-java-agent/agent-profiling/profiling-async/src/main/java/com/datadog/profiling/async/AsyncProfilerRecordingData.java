package com.datadog.profiling.async;

import com.datadog.profiling.controller.RecordingData;
import com.datadog.profiling.controller.RecordingInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import javax.annotation.Nonnull;

final class AsyncProfilerRecordingData extends RecordingData {
  private final Path recordingFile;

  public AsyncProfilerRecordingData(Path recordingFile, Instant start, Instant end) {
    super(start, end);
    this.recordingFile = recordingFile;
  }

  @Nonnull
  @Override
  public RecordingInputStream getStream() throws IOException {
    return new RecordingInputStream(Files.newInputStream(recordingFile));
  }

  @Override
  public void release() {
    try {
      Files.deleteIfExists(recordingFile);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  @Nonnull
  @Override
  public String getName() {
    return "async-profiler";
  }
}
