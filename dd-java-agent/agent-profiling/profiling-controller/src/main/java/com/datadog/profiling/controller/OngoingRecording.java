package com.datadog.profiling.controller;

import datadog.trace.api.profiling.ProfilingSnapshot;
import datadog.trace.api.profiling.RecordingData;
import java.io.Closeable;
import java.time.Instant;
import javax.annotation.Nonnull;

/** Interface that represents ongoing recording in profiling system */
public interface OngoingRecording extends Closeable {

  /**
   * Stop recording.
   *
   * @return {@link RecordingData} with current recording information
   */
  @Nonnull
  RecordingData stop();

  /**
   * Create snapshot from running recording. Note: recording continues to run after this method is
   * called.
   *
   * @param start start time of the snapshot
   * @param kind the snapshot reason
   * @return {@link RecordingData} with snapshot information
   */
  @Nonnull
  RecordingData snapshot(@Nonnull final Instant start, ProfilingSnapshot.Kind kind);

  /** Close recording without capturing any data */
  @Override
  void close();
}
