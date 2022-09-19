package com.datadog.profiling.leakmonitor;

import com.datadog.profiling.controller.OngoingRecording;

public class ToggleOldObjectSample implements Action {

  private final OngoingRecording recording;

  public ToggleOldObjectSample(OngoingRecording recording) {
    this.recording = recording;
  }

  @Override
  public void apply() {
    recording.enableEvent("jdk.OldObjectSample");
  }

  @Override
  public void revert() {
    recording.disableEvent("jdk.OldObjectSample");
  }
}
