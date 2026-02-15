package com.datadog.profiling.ddprof;

import datadog.trace.api.profiling.RecordingData;
import datadog.trace.api.profiling.RecordingInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import javax.annotation.Nonnull;

final class DatadogProfilerRecordingData extends RecordingData {
  private final Path recordingFile;

  public DatadogProfilerRecordingData(Path recordingFile, Instant start, Instant end, Kind kind) {
    super(start, end, kind);
    this.recordingFile = recordingFile;
  }

  @Nonnull
  @Override
  public RecordingInputStream getStream() throws IOException {
    return new RecordingInputStream(Files.newInputStream(recordingFile));
  }

  @Override
  protected void doRelease() {
    try {
      Files.deleteIfExists(recordingFile);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  @Nonnull
  @Override
  public String getName() {
    return "ddprof";
  }

  @Override
  public Path getFile() {
    return recordingFile;
  }
}
