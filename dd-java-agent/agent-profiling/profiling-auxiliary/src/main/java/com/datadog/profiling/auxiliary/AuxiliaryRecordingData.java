package com.datadog.profiling.auxiliary;

import com.datadog.profiling.controller.RecordingData;
import com.datadog.profiling.controller.RecordingInputStream;
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
      Instant start, Instant end, @Nonnull RecordingData main, RecordingData... secondary) {
    super(start, end);
    if (main == null) {
      throw new IllegalArgumentException("Main data must be specified and not null");
    }
    this.mainData = main;
    this.secondaryData = secondary;
  }

  @Nonnull
  @Override
  public RecordingInputStream getStream() throws IOException {
    return new RecordingInputStream(
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
