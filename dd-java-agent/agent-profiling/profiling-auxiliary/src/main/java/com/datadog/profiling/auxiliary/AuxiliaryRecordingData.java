package com.datadog.profiling.auxiliary;

import datadog.trace.api.profiling.ProfilingSnapshot;
import datadog.trace.api.profiling.RecordingData;

import java.io.IOException;
import java.time.Instant;
import javax.annotation.Nonnull;

/**
 * Allows creating auxiliary multi-part recordings.<br>
 * A multi-part recording consists of several self-standing recordings simply concatenated into one
 * binary file.
 */
public final class AuxiliaryRecordingData extends RecordingData {
  private final RecordingData mainData;
  private final RecordingData[] secondaryData;

  public AuxiliaryRecordingData(
      Instant start,
      Instant end,
      Kind kind,
      @Nonnull RecordingData main,
      RecordingData... secondary) {
    super(start, end, kind);
    if (main == null) {
      throw new IllegalArgumentException("Main data must be specified and not null");
    }
    this.mainData = main;
    this.secondaryData = secondary;
  }

  @Nonnull
  @Override
  public RecordingStream getStream() throws IOException {
    return new ProfilingSnapshot.RecordingStream(
        new AuxiliaryRecordingStreams(mainData, secondaryData).asSequenceInputStream());
  }

  @Override
  public void release() {
    mainData.release();
    for (RecordingData data : secondaryData) {
      data.release();
    }
  }

  @Nonnull
  @Override
  public String getName() {
    return mainData.getName();
  }
}
