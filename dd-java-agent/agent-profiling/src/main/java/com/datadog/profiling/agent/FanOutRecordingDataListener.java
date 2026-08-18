package com.datadog.profiling.agent;

import datadog.trace.api.profiling.RecordingData;
import datadog.trace.api.profiling.RecordingDataListener;
import datadog.trace.api.profiling.RecordingType;

/**
 * A {@link RecordingDataListener} decorator that fans recording data out to two listeners. {@code
 * secondary} may process (and release) the data asynchronously, so it is given its own reference
 * via {@link RecordingData#retain()}; {@code primary} is invoked with — and releases — the base
 * reference.
 */
final class FanOutRecordingDataListener implements RecordingDataListener {
  private final RecordingDataListener primary;
  private final RecordingDataListener secondary;

  /** Wraps {@code primary} so {@code secondary} also receives every recording. */
  static RecordingDataListener wrap(
      RecordingDataListener primary, RecordingDataListener secondary) {
    return new FanOutRecordingDataListener(primary, secondary);
  }

  FanOutRecordingDataListener(RecordingDataListener primary, RecordingDataListener secondary) {
    this.primary = primary;
    this.secondary = secondary;
  }

  @Override
  public void onNewData(RecordingType type, RecordingData data, boolean handleSynchronously) {
    data.retain();
    secondary.onNewData(type, data, handleSynchronously);
    primary.onNewData(type, data, handleSynchronously);
  }
}
