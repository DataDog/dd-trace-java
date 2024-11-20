package com.datadog.debugger.probe;

public interface Sampled {
  Sampling getSampling();

  String getId();

  boolean isCaptureSnapshot();
}
