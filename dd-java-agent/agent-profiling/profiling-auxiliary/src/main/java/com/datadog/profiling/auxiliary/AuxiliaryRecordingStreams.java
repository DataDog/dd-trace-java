package com.datadog.profiling.auxiliary;

import com.datadog.profiling.controller.RecordingData;
import com.datadog.profiling.controller.RecordingInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.Enumeration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AuxiliaryRecordingStreams implements Enumeration<InputStream> {
  private static final Logger log = LoggerFactory.getLogger(AuxiliaryRecordingStreams.class);

  private static final byte[] EMPTY_ARRAY = new byte[0];

  private int position = -1;
  private boolean isMainDataEmpty = true;
  private final RecordingData mainData;
  private final RecordingData[] auxiliaryData;

  AuxiliaryRecordingStreams(RecordingData mainData, RecordingData[] auxiliaryData) {
    this.mainData = mainData;
    this.auxiliaryData = auxiliaryData;
  }

  SequenceInputStream asSequenceInputStream() {
    return new SequenceInputStream(this);
  }

  @Override
  public boolean hasMoreElements() {
    if (position == 0 && isMainDataEmpty) {
      // if the main recording is empty do not attach any auxiliary streams
      return false;
    }
    return position < auxiliaryData.length;
  }

  @Override
  public InputStream nextElement() {
    try {
      if (position == -1) {
        RecordingInputStream is = mainData.getStream();
        isMainDataEmpty = is.isEmpty();
        return is;
      }
      return auxiliaryData[position].getStream();
    } catch (IOException e) {
      if (log.isDebugEnabled()) {
        log.warn("Unable to retrieve {} data stream", position == -1 ? "main" : "auxiliary", e);
      }
    } finally {
      position++;
    }
    return new ByteArrayInputStream(EMPTY_ARRAY);
  }
}
