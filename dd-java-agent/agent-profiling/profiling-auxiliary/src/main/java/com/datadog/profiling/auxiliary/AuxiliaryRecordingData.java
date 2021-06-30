package com.datadog.profiling.auxiliary;

import com.datadog.profiling.controller.RecordingData;
import com.datadog.profiling.controller.RecordingInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.time.Instant;
import java.util.Enumeration;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Allows creating auxiliary multi-part recordings.<br>
 * A multi-part recording consists of several self-standing recordings simply concatenated into one
 * binary file.
 */
public final class AuxiliaryRecordingData extends RecordingData {
  private static final Logger log = LoggerFactory.getLogger(AuxiliaryRecordingData.class);

  private static final byte[] EMPTY_ARRAY = new byte[0];

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
    RecordingInputStream mainStream = mainData.getStream();

    if (mainStream.isEmpty()) {
      log.debug("Main stream is empty");
      // do not append the auxiliary data to an empty main stream
      return mainStream;
    }

    Enumeration<InputStream> streams =
        new Enumeration<InputStream>() {
          int position = -1;

          @Override
          public boolean hasMoreElements() {
            return position < secondaryData.length;
          }

          @Override
          public InputStream nextElement() {
            try {
              return position == -1 ? mainStream : secondaryData[position].getStream();
            } catch (IOException e) {
              if (log.isDebugEnabled()) {
                log.warn(
                    "Unable to retrieve {} data stream at position",
                    position == -1 ? "main" : "auxiliary");
              }
            } finally {
              position++;
            }
            return new ByteArrayInputStream(EMPTY_ARRAY);
          }
        };

    return new RecordingInputStream(new SequenceInputStream(streams));
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
